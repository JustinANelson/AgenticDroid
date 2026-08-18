package com.justnels.agenticdroid.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justnels.agenticdroid.env.FileSystemAccess
import com.justnels.agenticdroid.env.FileSystemEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Browses a remote (e.g. SSH) filesystem, since remote projects can't use the local [FileTree]. */
@Composable
fun RemoteBrowserScreen(
    filesystem: FileSystemAccess,
    rootPath: String,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var currentPath by remember(rootPath) { mutableStateOf(rootPath) }
    var entries by remember { mutableStateOf<List<FileSystemEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(currentPath, refreshToken) {
        isLoading = true
        error = null
        try {
            val listed = withContext(Dispatchers.IO) { filesystem.listEntries(currentPath) }
            entries = listed.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        } catch (e: Exception) {
            error = e.message ?: "Failed to list directory"
        } finally {
            isLoading = false
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (currentPath != rootPath) {
                IconButton(onClick = {
                    currentPath = currentPath.substringBeforeLast('/').ifEmpty { "/" }
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                }
            }
            Text(
                text = currentPath,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
            IconButton(onClick = { refreshToken++ }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }

        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Error: $error",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            entries.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Empty directory")
                }
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (entry.isDirectory) {
                                        currentPath = entry.path
                                    } else {
                                        onOpenFile(entry.path)
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = entry.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}
