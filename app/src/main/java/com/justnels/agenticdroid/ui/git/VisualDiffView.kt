package com.justnels.agenticdroid.ui.git

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justnels.agenticdroid.git.DiffFile
import com.justnels.agenticdroid.git.DiffLine

private val addedColor = Color(0xFF2E7D32)
private val removedColor = Color(0xFFC62828)
private val addedBackground = addedColor.copy(alpha = 0.15f)
private val removedBackground = removedColor.copy(alpha = 0.15f)
private val hunkHeaderBackground = Color(0xFF1565C0).copy(alpha = 0.1f)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VisualDiffView(
    files: List<DiffFile>,
    untrackedFiles: List<String> = emptyList(),
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        if (untrackedFiles.isNotEmpty()) {
            item {
                Text(
                    text = "Untracked Files",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(16.dp)
                )
            }
            items(untrackedFiles) { path ->
                Text(
                    text = path,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = addedColor,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }

        files.forEach { file ->
            stickyHeader {
                FileHeader(file.fileName, file.newPath)
            }
            
            file.hunks.forEach { hunk ->
                item {
                    HunkHeader(hunk.header)
                }
                items(hunk.lines) { line ->
                    DiffLineRow(line)
                }
            }
            
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun FileHeader(fileName: String, fullPath: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = fullPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HunkHeader(header: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(hunkHeaderBackground)
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Text(
            text = header,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun DiffLineRow(line: DiffLine) {
    val backgroundColor = when (line.type) {
        DiffLine.Type.ADDED -> addedBackground
        DiffLine.Type.REMOVED -> removedBackground
        DiffLine.Type.NEUTRAL -> Color.Transparent
    }
    
    val indicatorColor = when (line.type) {
        DiffLine.Type.ADDED -> addedColor
        DiffLine.Type.REMOVED -> removedColor
        DiffLine.Type.NEUTRAL -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .height(IntrinsicSize.Min)
    ) {
        // Line Numbers
        LineNumberColumn(line.oldLineNo, line.newLineNo)
        
        // Change Indicator
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(indicatorColor)
        )
        
        // Content
        Text(
            text = (if (line.type == DiffLine.Type.ADDED) "+" else if (line.type == DiffLine.Type.REMOVED) "-" else " ") + line.content,
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 1.dp)
                .weight(1f),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = when (line.type) {
                DiffLine.Type.ADDED -> addedColor
                DiffLine.Type.REMOVED -> removedColor
                DiffLine.Type.NEUTRAL -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

@Composable
private fun LineNumberColumn(oldNo: Int?, newNo: Int?) {
    Row(
        modifier = Modifier
            .width(64.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = oldNo?.toString() ?: "",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = newNo?.toString() ?: "",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )
    }
}
