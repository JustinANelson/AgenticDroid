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
    private val workingDirectory: String
) : AndroidViewModel(application), TerminalSessionClient {

    var session by mutableStateOf<TerminalSession?>(null)
        private set
        
    val unavailableReason: String?

    var sessionTitle by mutableStateOf<String?>(null)
        private set

    var lastDetectedUrl by mutableStateOf<String?>(null)
        private set

    private var isServiceBound = false
    private var terminalBinder: TerminalService.TerminalServiceBinder? = null
    private val sessionKey = "terminal_${env.getEnvironmentInfo().name}_${workingDirectory.hashCode()}"

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
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            terminalBinder = null
            session = null
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

    /** Sends a full command line, as if the user typed it and pressed Enter. */
    fun sendCommand(command: String) {
        // A real terminal sends carriage return for the Enter key. Raw-mode TUIs (Claude,
        // Codex, Antigravity) do not interpret a bare LF as Enter.
        session?.write("$command\r")
    }

    /** Sends raw bytes (e.g. a control character or escape sequence) with no trailing Enter. */
    fun sendRawInput(input: String) {
        Log.v(TAG, "Sending raw input (length: ${input.length})")
        session?.write(input)
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
    }

    override fun onCleared() {
        dispose()
    }

    // --- TerminalSessionClient ---

    override fun onTextChanged(changedSession: TerminalSession) {
        onScreenUpdate?.invoke()
        
        try {
            val emulator = changedSession.emulator
            val screen = emulator.screen
            
            // 1. Get the raw text and split into lines.
            val rawTranscript = screen.transcriptText
            val lines = rawTranscript.lines()
            
            // 2. Join lines for the URL scanner.
            // We join with NO spaces, but we trim each line to remove 
            // both leading indentation and trailing padding.
            val joinedText = lines.joinToString("") { it.trim() }
            
            // 3. Greedy regex to capture EVERYTHING until whitespace or terminal symbols
            val urlRegex = Regex("https?://[^\\s\"\'<>|\\[\\]]+", RegexOption.IGNORE_CASE)
            val matches = urlRegex.findAll(joinedText)
            
            // 4. Pick the longest match (auth URLs are significantly longer than others)
            val match = matches.maxByOrNull { it.value.length }

            if (match != null) {
                var cleanUrl = match.value
                // Remove trailing punctuation that commonly gets trapped by greedy regex
                val toStrip = setOf('.', ',', ')', '!', '?', ';', ':', '>', ']', '}', '\'')
                while (cleanUrl.isNotEmpty() && cleanUrl.last() in toStrip) {
                    cleanUrl = cleanUrl.dropLast(1)
                }
                
                if (cleanUrl.length > 15 && cleanUrl != lastDetectedUrl) {
                    lastDetectedUrl = cleanUrl
                }
            } else if (lastDetectedUrl != null) {
                // Clear the URL if it's no longer on screen
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
    }
}
