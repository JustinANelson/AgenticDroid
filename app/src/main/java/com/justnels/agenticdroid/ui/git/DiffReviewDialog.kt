package com.justnels.agenticdroid.ui.git

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.justnels.agenticdroid.git.DiffParser

/** Full-screen review of the working tree's pending changes: a unified diff plus untracked files. */
@Composable
fun DiffReviewDialog(
    rawDiff: String?,
    untrackedFiles: List<String>,
    isLoading: Boolean,
    isDiscarding: Boolean = false,
    onDismiss: () -> Unit,
    onDiscardAll: () -> Unit = {}
) {
    var showDiscardConfirm by remember { mutableStateOf(false) }
    val hasChanges = !rawDiff.isNullOrBlank() || untrackedFiles.isNotEmpty()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Review Changes", style = MaterialTheme.typography.titleLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (hasChanges) {
                            TextButton(onClick = { showDiscardConfirm = true }, enabled = !isDiscarding) {
                                if (isDiscarding) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Discard All", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                }
                HorizontalDivider()

                when {
                    isLoading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    rawDiff.isNullOrBlank() && untrackedFiles.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No pending changes", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    else -> {
                        val parsedFiles = remember(rawDiff) {
                            rawDiff?.let { DiffParser.parse(it) } ?: emptyList()
                        }
                        VisualDiffView(
                            files = parsedFiles,
                            untrackedFiles = untrackedFiles
                        )
                    }
                }
            }
        }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("Discard all pending changes?") },
            text = {
                Text(
                    "Restores every tracked file shown above to its last commit, and deletes " +
                        "every untracked file (new files an agent created). Untracked files " +
                        "cannot be recovered afterward - anything already committed is unaffected."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDiscardConfirm = false
                        onDiscardAll()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Discard All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
