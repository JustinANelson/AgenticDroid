package com.justnels.agenticdroid.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.justnels.agenticdroid.git.DiffLineType
import com.justnels.agenticdroid.git.FileDiff
import com.justnels.agenticdroid.git.parseUnifiedDiff

private val AddBackground = Color(0xFF1B3A24)
private val AddText = Color(0xFF6FCF87)
private val RemoveBackground = Color(0xFF3A1F1F)
private val RemoveText = Color(0xFFE07A7A)
private val ContextText = Color(0xFFB0B0B0)
private val EditorBackground = Color(0xFF1E1E1E)
private val GutterBackground = Color(0xFF252526)


@Composable
private fun DiffContent(rawDiff: String, untrackedFiles: List<String>) {
    val files = remember(rawDiff) { parseUnifiedDiff(rawDiff) }
    val totalAdditions = files.sumOf { it.additions }
    val totalDeletions = files.sumOf { it.deletions }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Row(modifier = Modifier.padding(bottom = 12.dp)) {
                Text(
                    "${files.size + untrackedFiles.size} file${if (files.size + untrackedFiles.size == 1) "" else "s"} changed",
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.width(12.dp))
                Text("+$totalAdditions", color = AddText, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Text("-$totalDeletions", color = RemoveText, style = MaterialTheme.typography.labelLarge)
            }
        }

        items(untrackedFiles) { path -> UntrackedFileCard(path) }
        items(files) { file -> FileDiffCard(file) }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun UntrackedFileCard(path: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("NEW", color = AddText, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Text(path, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun FileDiffCard(file: FileDiff) {
    var expanded by remember { mutableStateOf(true) }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        file.displayPath,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                    Row {
                        if (file.isNew) Text("new file", style = MaterialTheme.typography.labelSmall, color = AddText)
                        if (file.isDeleted) Text("deleted", style = MaterialTheme.typography.labelSmall, color = RemoveText)
                        Spacer(Modifier.width(8.dp))
                        Text("+${file.additions}", style = MaterialTheme.typography.labelSmall, color = AddText)
                        Spacer(Modifier.width(4.dp))
                        Text("-${file.deletions}", style = MaterialTheme.typography.labelSmall, color = RemoveText)
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }

            if (expanded) {
                Column(modifier = Modifier.background(EditorBackground).fillMaxWidth()) {
                    file.hunks.forEach { hunk ->
                        Text(
                            hunk.header,
                            color = Color(0xFF569CD6),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.background(GutterBackground).fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                        val scroll = rememberScrollState()
                        Column(modifier = Modifier.horizontalScroll(scroll).fillMaxWidth()) {
                            hunk.lines.forEach { line ->
                                val (bg, fg, prefix) = when (line.type) {
                                    DiffLineType.ADD -> Triple(AddBackground, AddText, "+")
                                    DiffLineType.REMOVE -> Triple(RemoveBackground, RemoveText, "-")
                                    DiffLineType.CONTEXT -> Triple(EditorBackground, ContextText, " ")
                                }
                                Text(
                                    "$prefix${line.text}",
                                    color = fg,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    softWrap = false,
                                    modifier = Modifier.background(bg).fillMaxWidth().padding(horizontal = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
