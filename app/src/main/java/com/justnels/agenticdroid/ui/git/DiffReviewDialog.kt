package com.justnels.agenticdroid.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val addedColor = androidx.compose.ui.graphics.Color(0xFF2E7D32)
private val removedColor = androidx.compose.ui.graphics.Color(0xFFC62828)
private val hunkColor = androidx.compose.ui.graphics.Color(0xFF1565C0)

/** Full-screen review of the working tree's pending changes: a unified diff plus untracked files. */
@Composable
fun DiffReviewDialog(
    rawDiff: String?,
    untrackedFiles: List<String>,
    isLoading: Boolean,
    onDismiss: () -> Unit
) {
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
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
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
                        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                            if (untrackedFiles.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "Untracked files",
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                                    )
                                }
                                items(untrackedFiles) { path ->
                                    Text(
                                        text = path,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = addedColor,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                            if (!rawDiff.isNullOrBlank()) {
                                item {
                                    Text(
                                        text = "Diff",
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                                    )
                                }
                                items(rawDiff.lines()) { line ->
                                    DiffLine(line)
                                }
                            }
                            item { Spacer(modifier = Modifier.height(16.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffLine(line: String) {
    val (color, background) = when {
        line.startsWith("+++") || line.startsWith("---") -> MaterialTheme.colorScheme.onSurfaceVariant to androidx.compose.ui.graphics.Color.Transparent
        line.startsWith("+") -> addedColor to addedColor.copy(alpha = 0.12f)
        line.startsWith("-") -> removedColor to removedColor.copy(alpha = 0.12f)
        line.startsWith("@@") -> hunkColor to hunkColor.copy(alpha = 0.12f)
        line.startsWith("diff --git") || line.startsWith("index ") -> MaterialTheme.colorScheme.primary to androidx.compose.ui.graphics.Color.Transparent
        else -> MaterialTheme.colorScheme.onSurface to androidx.compose.ui.graphics.Color.Transparent
    }
    Text(
        text = line.ifEmpty { " " },
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.fillMaxWidth().background(background)
    )
}
