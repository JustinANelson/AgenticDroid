package com.justnels.agenticdroid.ui.terminal

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.justnels.agenticdroid.env.ExecutionEnvironment
import com.justnels.agenticdroid.env.SSHExecutionEnvironment
import com.justnels.agenticdroid.terminal.TerminalService
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * Owns the interactive PTY session backing the Terminal screen and agent launches. A single
 * [TerminalSession] is forked once (lazily, when a [com.termux.view.TerminalView] first
 * attaches to it) and lives for as long as this ViewModel does - both the Terminal tab and
 * the agent-launch screen share the same session, so switching between them reattaches to
 * the same running shell rather than starting a new one.
 *
 * There is no per-command process spawning here: every command, whether typed by the user
 * or triggered by "Launch", is written as input to this one persistent shell, exactly like a
 * real terminal. That's what makes interactive TUIs (Claude Code, Codex, Antigravity) work -
 * they run as children of this shell and inherit its controlling terminal.
 */
class TerminalViewModel(
    application: Application,
    private val env: ExecutionEnvironment,
    private val workingDirectory: String,
    private val onSessionEnded: () -> Unit = {},
    private val shortenDirectoryNames: Boolean = false
) : AndroidViewModel(application), TerminalSessionClient {

    var session by mutableStateOf<TerminalSession?>(null)
        private set
        
    var unavailableReason by mutableStateOf<String?>(null)
        private set

    var sessionTitle by mutableStateOf<String?>(null)
        private set

    var lastDetectedUrl by mutableStateOf<String?>(null)
        private set

    private var isServiceBound = false
    private var terminalBinder: TerminalService.TerminalServiceBinder? = null
    private val sessionKey = "terminal_${env.getEnvironmentInfo().name}_${workingDirectory.hashCode()}"

    // getOrCreateSession only constructs the TerminalSession object - per this class's own
    // top-of-file doc, the shell itself is forked lazily, when a TerminalView first attaches
    // and reports its size. A caller that sends input right after triggering that attach
    // (the Agents screen's "Launch" does exactly this: send, then navigate to the screen
    // that renders the TerminalView) can hit a gap where `session` is already non-null but
    // the shell hasn't forked yet - Termux's TerminalSession.write() silently drops bytes
    // written before its process exists. This buffers until the shell reports running
    // (isRunning(), not just object-non-null) and flushes on its first output
    // (onTextChanged, below). NOTE: this alone is not sufficient to make a cold-start
    // Launch tap reliable - see the known issue noted on sendCommand/onLaunchAgent.
    private val pendingInput = mutableListOf<String>()
    private var hasNavigatedToWorkingDirectory = false
    private var hasShortenedPrompt = false

    /**
     * Whether the SSH remote's interactive shell is cmd.exe, shared by both the
     * cd-injection and the prompt-shortening below since this class otherwise has no way
     * to know the remote shell's dialect (see the non-interactive-exec equivalent problem
     * in SSHExecutionEnvironment.ensurePosixShellDiscovered, which can't be reused here as
     * that discovery needs a live network round-trip and this runs on the main thread).
     *
     * Two independent signals, either sufficient on its own:
     * 1. [workingDirectory]'s shape (a raw "C:\Users\..." or "C:/Users/..." path - Windows
     *    accepts forward slashes too). This alone is not reliable: it's blind whenever the
     *    configured working directory is a *relative* path (e.g. "./Projects/Foo", which
     *    carries no OS-identifying information at all) - confirmed the hard way, this
     *    exact case sent the POSIX PS1 command to a Windows remote.
     * 2. [session]'s own transcript so far, checked for cmd.exe's standard interactive
     *    startup banner ("Microsoft Windows [Version ...") or its default drive-rooted
     *    prompt shape ("C:\...>") - text the remote itself already sent, needing no extra
     *    round-trip. This is what actually catches the relative-working-directory case
     *    signal 1 misses, since it doesn't depend on the working directory at all.
     */
    private fun isWindowsRemote(session: TerminalSession): Boolean {
        val pathLooksWindows =
            workingDirectory.contains("\\") || Regex("^/?[A-Za-z]:[/\\\\]").containsMatchIn(workingDirectory)
        if (pathLooksWindows) return true
        val transcript = runCatching { session.emulator?.screen?.transcriptText }.getOrNull().orEmpty()
        return transcript.contains("Microsoft Windows") || Regex("[A-Za-z]:\\\\[^>]*>").containsMatchIn(transcript)
    }

    private fun flushPendingInput() {
        if (pendingInput.isEmpty()) return
        val current = session ?: return
        if (!current.isRunning()) return
        val queued = pendingInput.toList()
        pendingInput.clear()
        queued.forEach { current.write(it) }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TerminalService.TerminalServiceBinder
            terminalBinder = binder
            val spec = env.ptyShellSpec(workingDirectory)
            if (spec != null) {
                session = binder.getOrCreateSession(
                    sessionKey,
                    spec.shellPath,
                    spec.cwd,
                    spec.args,
                    spec.env,
                    this@TerminalViewModel
                )
                unavailableReason = null
                // Usually a no-op here (the shell isn't forked yet) - covers the case where
                // this session was already running from an earlier attach (e.g. reattaching
                // after switching screens), where it can flush immediately.
                flushPendingInput()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            terminalBinder = null
            session = null
            unavailableReason = "Terminal service disconnected."
        }
    }

    /** Set by whichever [com.termux.view.TerminalView] is currently attached to [session];
     * [onTextChanged] must explicitly tell it to redraw - it doesn't poll or observe the
     * session on its own. */
    var onScreenUpdate: (() -> Unit)? = null

    init {
        val spec = env.ptyShellSpec(workingDirectory)
        if (spec != null) {
            unavailableReason = null
            val intent = Intent(application, TerminalService::class.java)
            application.startForegroundService(intent)
            isServiceBound = application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } else {
            unavailableReason = "Interactive terminal isn't available for ${env.getEnvironmentInfo().name}."
        }
    }

    /** Sends a full command line, as if the user typed it and pressed Enter. Queued if the
     * PTY session isn't connected yet - see [pendingInput].
     *
     * KNOWN ISSUE: a cold-start call from the Agents screen ("Launch" right after opening a
     * project, before ever visiting the Terminal tab) can still silently fail to reach the
     * visible session even when this reports the write as delivered to a running,
     * correctly-keyed [TerminalSession] (confirmed via session-identity logging: same
     * sessionKey, same identityHashCode, isRunning()==true at write time, yet no process
     * spawns and the on-screen terminal stays blank). Root cause not yet isolated - ruled
     * out both a null/not-yet-running session and a stale/duplicate session object; suspect
     * the AndroidView in TerminalScreen.kt not re-attaching to this exact session in time,
     * but that needs an attached debugger (breakpoints in TerminalScreen's AndroidView
     * `update` block and Termux's own TerminalSession/TerminalView) to confirm, not further
     * blind on-device testing. Warm sessions (Terminal tab visited first, or relaunching
     * after a prior successful launch) are confirmed working. */
    fun sendCommand(command: String) {
        // A real terminal sends carriage return for the Enter key. Raw-mode TUIs (Claude,
        // Codex, Antigravity) do not interpret a bare LF as Enter.
        write("$command\r")
    }

    /** Sends raw bytes (e.g. a control character or escape sequence) with no trailing Enter.
     * Queued if the PTY session isn't connected yet - see [pendingInput]. */
    fun sendRawInput(input: String) {
        Log.v(TAG, "Sending raw input (length: ${input.length})")
        write(input)
    }

    private fun write(input: String) {
        val current = session
        if (current != null && current.isRunning()) {
            current.write(input)
        } else {
            pendingInput.add(input)
        }
    }

    fun runDiagnostics() {
        val info = env.getEnvironmentInfo()
        val tools = listOf("git", "java", "javac", "node", "npm", "python", "sh")
        val checks = tools.joinToString("; ") { tool ->
            "if command -v $tool >/dev/null 2>&1; then echo '  $tool: OK'; else echo '  $tool: MISSING'; fi"
        }
        sendCommand(
            "echo '--- System Diagnostics ---'; " +
                "echo 'Environment: ${info.name}'; echo 'OS: ${info.os}'; echo 'Arch: ${info.architecture}'; " +
                "echo 'Checking for tools...'; $checks; " +
                "echo '-------------------------'"
        )
    }

    /** Kills the forked shell. Public because this ViewModel is manually `remember`'d in
     * Compose rather than lifecycle-scoped, so callers must dispose of it explicitly. */
    fun dispose() {
        onScreenUpdate = null
        terminalBinder?.detachSessionClient(sessionKey, this)
        if (isServiceBound) {
            getApplication<Application>().unbindService(serviceConnection)
            isServiceBound = false
        }
        terminalBinder = null
    }

    fun closeTerminal() {
        terminalBinder?.removeSession(sessionKey)
        session = null
        unavailableReason = "Terminal closed."
    }

    fun reconnect() {
        hasNavigatedToWorkingDirectory = false
        val binder = terminalBinder
        val spec = env.ptyShellSpec(workingDirectory)
        if (binder != null && spec != null) {
            binder.removeSession(sessionKey)
            session = binder.getOrCreateSession(
                sessionKey,
                spec.shellPath,
                spec.cwd,
                spec.args,
                spec.env,
                this
            )
            unavailableReason = null
        } else if (spec != null) {
            unavailableReason = null
            val intent = Intent(getApplication(), TerminalService::class.java)
            getApplication<Application>().startForegroundService(intent)
            isServiceBound = getApplication<Application>().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } else {
            unavailableReason = "Interactive terminal isn't available for ${env.getEnvironmentInfo().name}."
        }
    }

    override fun onCleared() {
        dispose()
    }

    // --- TerminalSessionClient ---

    override fun onTextChanged(changedSession: TerminalSession) {
        // The shell's first output (its prompt) is the real "now running" signal - see
        // pendingInput above. Cheap to call on every text change since it no-ops once empty.
        flushPendingInput()
        onScreenUpdate?.invoke()

        if (!hasNavigatedToWorkingDirectory && workingDirectory.isNotBlank() && workingDirectory != "." && workingDirectory != getApplication<Application>().filesDir.absolutePath) {
            hasNavigatedToWorkingDirectory = true
            if (env is SSHExecutionEnvironment) {
                val isWindows = isWindowsRemote(changedSession)
                // cmd.exe's own `cd /d` rejects a leading slash before the drive letter
                // ("The syntax of the command is incorrect", confirmed directly against
                // cmd.exe) even though it accepts both "C:/Users/..." and "C:\Users\...".
                // Only stripped when isWindows, so a genuinely POSIX path (which
                // legitimately starts with '/') is never touched.
                val cdCmd = if (isWindows) "cd /d \"${workingDirectory.removePrefix("/")}\"\r\n" else "cd \"$workingDirectory\"\r\n"
                changedSession.write(cdCmd)
            }
        }

        if (shortenDirectoryNames && !hasShortenedPrompt) {
            hasShortenedPrompt = true
            changedSession.write(if (isWindowsRemote(changedSession)) SHORTEN_PROMPT_COMMAND_WINDOWS else SHORTEN_PROMPT_COMMAND_POSIX)
        }

        try {
            val emulator = changedSession.emulator
            val screen = emulator.screen
            val rawTranscript = screen.transcriptText
            val detected = extractUrls(rawTranscript)

            // Prioritize auth/login/oauth URLs if present, otherwise pick the latest URL
            val bestUrl = detected.findLast { 
                it.contains("oauth", ignoreCase = true) || 
                it.contains("auth", ignoreCase = true) || 
                it.contains("login", ignoreCase = true) || 
                it.contains("accounts.google", ignoreCase = true) ||
                it.contains("github.com/login", ignoreCase = true)
            } ?: detected.lastOrNull()

            if (bestUrl != null) {
                if (bestUrl != lastDetectedUrl) {
                    lastDetectedUrl = bestUrl
                }
            } else if (lastDetectedUrl != null) {
                lastDetectedUrl = null
            }
        } catch (e: Exception) {
            // Screen access might fail if session is finishing
        }
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        sessionTitle = changedSession.title
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        Log.i(TAG, "Shell exited with status ${finishedSession.exitStatus}")
        terminalBinder?.removeSession(sessionKey)
        session = null
        unavailableReason = "Terminal process exited with status ${finishedSession.exitStatus}."
        onSessionEnded()
    }

    /** OSC 52: a terminal program asked to copy text to the system clipboard. */
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text ?: ""))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(getApplication())?.toString()
        if (!text.isNullOrEmpty()) session.write(text)
    }

    override fun onBell(session: TerminalSession) {}

    override fun onColorsChanged(session: TerminalSession) {}

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag ?: TAG, message ?: "", e) }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "", e) }

    companion object {
        private const val TAG = "TerminalViewModel"

        /**
         * Shortens the shell prompt to just the working directory's own name instead of its
         * full path, mirroring the "shorten directory paths" display setting used elsewhere
         * (RemoteBrowserScreen). Written once as the shell's very first input, before any
         * command the user or an agent sends - a plain PS1 override rather than relying on
         * bash/zsh-specific escapes (\W, %1~), since this session's actual shell varies by
         * environment (Android's mksh-derived /system/bin/sh locally, bash from the bundled
         * Node runtime, or whatever's configured as the remote user's login shell over SSH).
         * `$(...)` inside PS1 is POSIX-mandated to be re-evaluated on every prompt draw, so
         * this works unmodified on every shell tested (sh, mksh, bash, dash); zsh is the one
         * common exception - it needs `setopt PROMPT_SUBST` for command substitution in
         * prompts, so this enables that first when $ZSH_VERSION is set, then falls through
         * to the same PS1 assignment. Only sent when [isWindowsRemote] returns false for
         * the session - a Windows remote's shell is cmd.exe, which can't parse any of this
         * (confirmed the hard way: `2>/dev/null` alone made cmd.exe fail with "The system
         * cannot find the path specified", trying to redirect to a literal file named
         * `/dev/null` since that's not a device path on Windows) - see
         * [SHORTEN_PROMPT_COMMAND_WINDOWS] for that case.
         *
         * Ends in `\r\n`, not a bare `\n`: confirmed the hard way that a bare LF doesn't
         * submit the line on at least one shell this reaches (matches sendCommand's own
         * doc above, which uses `\r` for the same reason) - it instead got silently
         * appended to whatever was sent next, concatenating this command onto the cd
         * command above into one garbled, failing line.
         */
        private val SHORTEN_PROMPT_COMMAND_POSIX =
            "[ -n \"\$ZSH_VERSION\" ] && setopt PROMPT_SUBST; " +
                "PS1='\$(basename \"\$PWD\")\$ '\r\n"

        /**
         * cmd.exe equivalent of [SHORTEN_PROMPT_COMMAND_POSIX]. cmd's PROMPT variable has no
         * "last path segment only" code (unlike bash's \W) and no way to embed a live command
         * substitution the way POSIX PS1 does, so getting a folder-name-only prompt that
         * keeps working after subsequent `cd`s needs two parts, sent as two separate typed
         * lines (matching how a real interactive session would receive them, rather than
         * chained with `&` on one line - `%`-expansion inside a single cmd.exe line happens
         * once at parse time for the whole line, before any earlier `&`-joined command on
         * that same line has run, which silently breaks a one-liner version of this):
         * 1. A `doskey` macro override of `cd` that, after actually changing directory, also
         *    re-derives the new directory's name and calls the `prompt` builtin with it -
         *    doskey macros use `$T` (not a literal `&`) to separate chained commands inside
         *    the macro body, and only take effect for input coming through a real console
         *    (confirmed: they're silently skipped over a plain redirected pipe with no
         *    console allocated) - Windows OpenSSH's `-t`/pty-requested sessions use ConPTY,
         *    which does allocate one, so this is expected to fire the same way it would if
         *    typed directly.
         * 2. The same directory-name-into-prompt command run immediately once, so the very
         *    first prompt (before any `cd` happens) is already shortened rather than only
         *    taking effect after the first directory change.
         */
        private val SHORTEN_PROMPT_COMMAND_WINDOWS =
            "doskey cd=cd \$* \$Tfor %I in (\".\") do @prompt %~nxI\$G\r\n" +
                "for %I in (\".\") do @prompt %~nxI\$G\r\n"

        private val ANSI_REGEX = Regex("\u001B\\[[0-9;?]*[a-zA-Z]|\u001B\\][^\u0007\u001B]*[\u0007\u001B]\\\\?")
        private val URL_START_REGEX = Regex("https?://[a-zA-Z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+", RegexOption.IGNORE_CASE)
        private val STRIP_CHARS = setOf('.', ',', ')', '!', '?', ';', ':', '>', ']', '}', '\'', '"', '\\', '|')

        fun extractUrls(rawText: String?): List<String> {
            if (rawText.isNullOrBlank()) return emptyList()
            // 1. Strip ANSI escape codes
            val cleanText = ANSI_REGEX.replace(rawText, "")
            val rawLines = cleanText.lines()
            val results = mutableListOf<String>()

            var i = 0
            while (i < rawLines.size) {
                val line = rawLines[i].trimEnd()
                val match = URL_START_REGEX.find(line)
                if (match != null) {
                    val sb = StringBuilder()
                    val urlStartIndex = match.range.first
                    val lineAfterUrlStart = line.substring(urlStartIndex)
                    val firstLineToken = lineAfterUrlStart.takeWhile { !it.isWhitespace() }
                    sb.append(firstLineToken)

                    // If this token went all the way to the end of the line, check if subsequent lines continue this wrapped URL
                    if (urlStartIndex + firstLineToken.length >= line.length) {
                        var nextIdx = i + 1
                        while (nextIdx < rawLines.size) {
                            val nextLine = rawLines[nextIdx].trimStart()
                            if (nextLine.isEmpty()) break

                            val nextToken = nextLine.takeWhile { !it.isWhitespace() }
                            val restOfLine = nextLine.substring(nextToken.length).trimStart()

                            val currentUrl = sb.toString()
                            val prevEndsInSep = currentUrl.endsWith("=") || currentUrl.endsWith("&") || currentUrl.endsWith("/") || currentUrl.endsWith("-") || currentUrl.endsWith("_") || currentUrl.endsWith("?") || currentUrl.endsWith("%")
                            val nextHasUrlSyntax = nextToken.contains("&") || nextToken.contains("=") || nextToken.contains("%") || 
                                nextToken.contains(".com") || nextToken.contains(".org") || nextToken.contains(".io") || 
                                nextToken.contains(".net") || nextToken.contains(".gov") || nextToken.contains("?") || nextToken.contains("/")
                            val isPlainEnglishPrompt = nextToken.equals("Enter", ignoreCase = true) || nextToken.equals("Please", ignoreCase = true) || 
                                nextToken.equals("Waiting", ignoreCase = true) || nextToken.equals("Code:", ignoreCase = true) || 
                                nextToken.equals("To", ignoreCase = true) || nextToken.equals("Press", ignoreCase = true) ||
                                (restOfLine.isNotEmpty() && !nextHasUrlSyntax && !prevEndsInSep)

                            if (!isPlainEnglishPrompt && (nextHasUrlSyntax || prevEndsInSep || nextToken.length >= 25)) {
                                sb.append(nextToken)
                                if (restOfLine.isNotEmpty()) {
                                    break
                                }
                                nextIdx++
                            } else {
                                break
                            }
                        }
                    }

                    var url = sb.toString()
                    // Strip any whitespace characters completely to ensure clean URLs
                    url = url.replace("\\s".toRegex(), "")

                    // Strip trailing punctuation
                    while (url.isNotEmpty() && url.last() in STRIP_CHARS) {
                        url = url.dropLast(1)
                    }

                    if (url.length >= 10 && url.contains("://")) {
                        results.add(url)
                    }
                }
                i++
            }

            return results.distinct()
        }
    }
}
