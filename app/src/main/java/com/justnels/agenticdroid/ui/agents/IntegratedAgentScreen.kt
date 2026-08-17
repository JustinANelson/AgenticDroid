package com.justnels.agenticdroid.ui.agents

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.justnels.agenticdroid.ui.terminal.TerminalScreen
import com.justnels.agenticdroid.ui.terminal.TerminalViewModel

@Composable
fun IntegratedAgentScreen(
    terminalViewModel: TerminalViewModel,
    modifier: Modifier = Modifier,
    hintsShown: Set<String> = emptySet(),
    onDismissHint: (String) -> Unit = {}
) {
    TerminalScreen(
        modifier = modifier.fillMaxSize(),
        viewModel = terminalViewModel,
        hintsShown = hintsShown,
        onDismissHint = onDismissHint
    )
}
