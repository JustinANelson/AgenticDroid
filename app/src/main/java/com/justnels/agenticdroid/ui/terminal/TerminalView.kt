package com.justnels.agenticdroid.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalViewClient
import com.justnels.agenticdroid.ui.components.HintBox
import com.termux.view.TerminalView as TermuxTerminalView

private const val ESC = ""
private const val CTRL_C = ""
private const val VOICE_INPUT_PREFIX = "VOICE-INPUT:"

/**
 * Renders a real PTY-backed [TerminalSession] via Termux's terminal-emulator/terminal-view
 * libraries (full VT100/ANSI parsing and a proper screen-cell renderer), wrapped in an
 * [AndroidView] since that rendering is a plain Android View, not Compose.
 */
@Composable
fun TerminalScreen(
    modifier: Modifier = Modifier,
    viewModel: TerminalViewModel,
    hintsShown: Set<String> = emptySet(),
    onDismissHint: (String) -> Unit = {},
    keepScreenOn: Boolean = true
) {
    val session = viewModel.session
    val textSizePx = with(LocalDensity.current) { 14.sp.toPx().toInt() }
    val context = androidx.compose.ui.platform.LocalContext.current
    var isShiftActive by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .background(Color.Black)
    ) {
        HintBox(
            hintId = "hint_terminal_persistence",
            title = "Persistent Sessions",
            text = "Your terminal sessions run in a foreground service. They'll keep running even if you switch apps!",
            hintsShown = hintsShown,
            onDismiss = onDismissHint
        )

        if (session == null) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        text = viewModel.unavailableReason ?: "Connecting to terminal…",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                    if (viewModel.unavailableReason != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.reconnect() }) {
                            Text("Reconnect")
                        }
                    }
                }
            }
        } else {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { context ->
                    TermuxTerminalView(context, null).apply {
                        setTextSize(textSizePx)
                        setTypeface(Typeface.MONOSPACE)
                        this.keepScreenOn = keepScreenOn
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setTerminalViewClient(defaultTerminalViewClient(context, this))
                        // notifyScreenUpdate() on the session only calls back into the
                        // client (this ViewModel) - the View doesn't observe the session
                        // on its own, so we have to explicitly forward the redraw.
                        viewModel.onScreenUpdate = { onScreenUpdated() }
                        attachSession(session)
                    }
                },
                update = { view ->
                    view.keepScreenOn = keepScreenOn
                    viewModel.onScreenUpdate = { view.onScreenUpdated() }
                    view.attachSession(session)
                }
            )
        }

        TerminalAccessoryRow(
            lastUrl = viewModel.lastDetectedUrl,
            isShiftActive = isShiftActive,
            onKeyClick = { key ->
                when (key) {
                    "ESC" -> viewModel.sendRawInput(ESC)
                    "CTRL-C" -> viewModel.sendRawInput(CTRL_C)
                    "TAB" -> viewModel.sendRawInput(if (isShiftActive) "$ESC[Z" else "\t")
                    "ENTER" -> viewModel.sendRawInput("\r")
                    "SHIFT" -> isShiftActive = !isShiftActive
                    "↑" -> {
                        viewModel.sendRawInput(if (isShiftActive) "$ESC[1;2A" else "$ESC[A")
                        isShiftActive = false
                    }
                    "↓" -> {
                        viewModel.sendRawInput(if (isShiftActive) "$ESC[1;2B" else "$ESC[B")
                        isShiftActive = false
                    }
                    "←" -> {
                        viewModel.sendRawInput(if (isShiftActive) "$ESC[1;2D" else "$ESC[D")
                        isShiftActive = false
                    }
                    "→" -> {
                        viewModel.sendRawInput(if (isShiftActive) "$ESC[1;2C" else "$ESC[C")
                        isShiftActive = false
                    }
                    "DIAG" -> viewModel.runDiagnostics()
                    "CLOSE" -> viewModel.closeTerminal()
                    "OPEN-LINK" -> {
                        viewModel.lastDetectedUrl?.let { url ->
                            try {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri()).apply {
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "Could not open link: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    "COPY" -> {
                        viewModel.lastDetectedUrl?.let { url ->
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("terminal-url", url))
                            android.widget.Toast.makeText(context, "Link copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    "Y" -> viewModel.sendRawInput(if (isShiftActive) "Y" else "y")
                    "N" -> viewModel.sendRawInput(if (isShiftActive) "N" else "n")
                    else -> when {
                        // Dictated text arrives prefixed so it can never collide with a
                        // named key above (e.g. a user dictating the literal word "enter").
                        // Typed, not auto-submitted, so the user can review/edit before
                        // sending it - matches paste behavior (onPasteTextFromClipboard).
                        key.startsWith(VOICE_INPUT_PREFIX) -> viewModel.sendRawInput(key.removePrefix(VOICE_INPUT_PREFIX))
                        else -> viewModel.sendRawInput(key)
                    }
                }
            }
        )
    }
}

/**
 * Minimal [TerminalViewClient]: lets [TermuxTerminalView] handle key/codepoint input with its
 * own default behavior (our accessory row sends control sequences directly to the session
 * instead, bypassing this entirely) and wires up focus/IME on tap plus logging.
 */
private fun defaultTerminalViewClient(context: Context, view: TermuxTerminalView): TerminalViewClient =
    object : TerminalViewClient {
        override fun onScale(scale: Float): Float = 1f

        override fun onSingleTapUp(e: MotionEvent) {
            // Try to find a URL at the tapped location using several common Termux library methods
            val url = try {
                val method = view.javaClass.getMethod("getStoredURL", MotionEvent::class.java)
                method.invoke(view, e) as? String
            } catch (ex: Exception) {
                try {
                    val method = view.javaClass.getMethod("getURLAtLocation", Float::class.java, Float::class.java)
                    method.invoke(view, e.x, e.y) as? String
                } catch (ex2: Exception) { null }
            }

            if (url != null) {
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri()).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return // Handled
                } catch (ex: Exception) {
                    android.util.Log.e("TerminalView", "Failed to open tapped URL: $url", ex)
                }
            }

            view.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(view, 0)
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = false
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = view.hasFocus()

        override fun copyModeChanged(copyMode: Boolean) {}

        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false

        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

        override fun onEmulatorSet() {}

        override fun logError(tag: String?, message: String?) { android.util.Log.e(tag, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { android.util.Log.w(tag, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { android.util.Log.i(tag, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { android.util.Log.d(tag, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { android.util.Log.v(tag, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { android.util.Log.e(tag, message ?: "", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { android.util.Log.e(tag, "", e) }
    }

@Composable
fun TerminalAccessoryRow(
    lastUrl: String? = null,
    isShiftActive: Boolean = false,
    onKeyClick: (String) -> Unit
) {
    // Ordered by expected usage: ENTER (submit) and CTRL-C (interrupt) come first as the
    // two most frequent actions, then ESC and history/completion (agent TUIs lean on ESC
    // to back out of input modes), then cursor movement, then the least-frequent
    // confirm/modifier/utility keys.
    val keys = mutableListOf("ENTER", "CTRL-C", "ESC", "↑", "↓", "TAB", "←", "→", "Y", "N", "SHIFT", "DIAG", "CLOSE")

    val displayUrl = remember(lastUrl) {
        lastUrl?.substringAfter("://")?.take(10)?.let { "$it.." }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!text.isNullOrBlank()) {
            onKeyClick("$VOICE_INPUT_PREFIX$text")
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Color.DarkGray)
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            onClick = {
                val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to send to the terminal")
                }
                try {
                    voiceLauncher.launch(intent)
                } catch (e: android.content.ActivityNotFoundException) {
                    android.widget.Toast.makeText(context, "No speech recognizer available on this device", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            color = MaterialTheme.colorScheme.primary,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.padding(start = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "Voice input",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).size(16.dp),
                tint = Color.White
            )
        }

        if (lastUrl != null) {
            Surface(
                onClick = { onKeyClick("OPEN-LINK") },
                color = MaterialTheme.colorScheme.primary,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInBrowser,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.White
                    )
                    if (displayUrl != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = displayUrl,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Compact COPY button
            Surface(
                onClick = { onKeyClick("COPY") },
                color = Color.Gray,
                shape = MaterialTheme.shapes.small
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy Link",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).size(16.dp),
                    tint = Color.White
                )
            }
        }

        keys.forEach { key ->
            val isActive = (key == "SHIFT" && isShiftActive)
            Surface(
                onClick = { onKeyClick(key) },
                color = if (isActive) MaterialTheme.colorScheme.primary else Color.Gray,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = key,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
