package com.justnels.agenticdroid.ui.terminal

import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalViewClient
import com.justnels.agenticdroid.ui.components.HintBox
import com.termux.view.TerminalView as TermuxTerminalView

private const val ESC = ""
private const val CTRL_C = ""

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
    onDismissHint: (String) -> Unit = {}
) {
    val session = viewModel.session
    val textSizePx = with(LocalDensity.current) { 14.sp.toPx().toInt() }

    Column(
        modifier = modifier
            .fillMaxSize()
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
                Text(
                    text = viewModel.unavailableReason ?: "No terminal session",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace
                )
            }
        } else {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { context ->
                    TermuxTerminalView(context, null).apply {
                        setTextSize(textSizePx)
                        setTypeface(Typeface.MONOSPACE)
                        keepScreenOn = true
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
                    viewModel.onScreenUpdate = { view.onScreenUpdated() }
                    view.attachSession(session)
                }
            )
        }

        TerminalAccessoryRow(
            onKeyClick = { key ->
                when (key) {
                    "ESC" -> viewModel.sendRawInput(ESC)
                    "CTRL-C" -> viewModel.sendRawInput(CTRL_C)
                    "TAB" -> viewModel.sendRawInput("\t")
                    "↑" -> viewModel.sendRawInput("$ESC[A")
                    "↓" -> viewModel.sendRawInput("$ESC[B")
                    "←" -> viewModel.sendRawInput("$ESC[D")
                    "→" -> viewModel.sendRawInput("$ESC[C")
                    "DIAG" -> viewModel.runDiagnostics()
                    else -> viewModel.sendRawInput(key)
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
            view.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = false
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

        override fun copyModeChanged(copyMode: Boolean) {}

        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false

        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false

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
    onKeyClick: (String) -> Unit
) {
    val keys = listOf("ESC", "CTRL-C", "TAB", "↑", "↓", "←", "→", "DIAG")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(Color.DarkGray)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        keys.forEach { key ->
            Surface(
                onClick = { onKeyClick(key) },
                color = Color.Gray,
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
