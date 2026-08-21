package com.justnels.agenticdroid.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justnels.agenticdroid.EditorSession

@Composable
fun EditorTabs(
    sessions: List<EditorSession>,
    activeIndex: Int,
    onTabSelected: (Int) -> Unit,
    onTabClosed: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(Color(0xFF252526)) // VS Code tab bar background
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.Bottom
    ) {
        sessions.forEachIndexed { index, session ->
            val isActive = index == activeIndex
            TabItem(
                name = session.file.name,
                isActive = isActive,
                isDirty = session.isDirty,
                onClick = { onTabSelected(index) },
                onClose = { onTabClosed(index) }
            )
        }
    }
}

@Composable
fun TabItem(
    name: String,
    isActive: Boolean,
    isDirty: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (isActive) Color(0xFF1E1E1E) else Color(0xFF2D2D2D),
        modifier = Modifier.height(40.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = name + if (isDirty) "*" else "",
                    color = if (isActive) Color.White else Color(0xFF969696),
                    fontSize = 13.sp,
                    fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(16.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close tab",
                        tint = if (isActive) Color.White else Color(0xFF969696),
                        modifier = Modifier.size(12.dp)
                    )
                }
            }

            // Active indicator at the top (like VS Code)
            if (isActive) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(Color(0xFF007ACC))
                        .align(Alignment.TopCenter)
                )
            }
        }
    }
}
