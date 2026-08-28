package com.justnels.agenticdroid.ui.workspace

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.justnels.agenticdroid.workspace.FileNode

@Composable
fun FileTree(
    nodes: List<FileNode>,
    onFileSelected: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onCopy: (String, String) -> Unit,
    onDownload: ((String, String) -> Unit)? = null,
    onDownloadArchive: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(nodes) { node ->
            FileNodeItem(node, onFileSelected, onDelete, onRename, onCopy, onDownload, onDownloadArchive)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FileNodeItem(
    node: FileNode,
    onFileSelected: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onCopy: (String, String) -> Unit,
    onDownload: ((String, String) -> Unit)? = null,
    onDownloadArchive: ((String) -> Unit)? = null,
    depth: Int = 0
) {
    var expanded by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showCopyDialog by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (node.isDirectory) {
                            expanded = !expanded
                        } else {
                            onFileSelected(node.path)
                        }
                    },
                    onLongClick = { showMenu = true }
                )
                .padding(8.dp)
                .padding(start = (depth * 16).dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (node.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (node.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "More", modifier = Modifier.size(16.dp))
            }

            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                if (!node.isDirectory && onDownload != null) {
                    DropdownMenuItem(
                        text = { Text("Download to Phone") },
                        onClick = {
                            showMenu = false
                            onDownload(node.path, node.name)
                        },
                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) }
                    )
                }
                if (node.isDirectory && onDownloadArchive != null) {
                    DropdownMenuItem(
                        text = { Text("Download as .tar.gz") },
                        onClick = {
                            showMenu = false
                            onDownloadArchive(node.path)
                        },
                        leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = {
                        showMenu = false
                        showRenameDialog = true
                    },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text("Copy") },
                    onClick = {
                        showMenu = false
                        showCopyDialog = true
                    },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        showMenu = false
                        onDelete(node.path)
                    },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) }
                )
            }
        }

        if (expanded && node.isDirectory) {
            node.children.forEach { child ->
                FileNodeItem(child, onFileSelected, onDelete, onRename, onCopy, onDownload, onDownloadArchive, depth + 1)
            }
        }
    }

    if (showRenameDialog) {
        var newName by remember { mutableStateOf(node.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("New Name") })
            },
            confirmButton = {
                Button(onClick = {
                    onRename(node.path, newName)
                    showRenameDialog = false
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showCopyDialog) {
        var copyName by remember { mutableStateOf("${node.name}_copy") }
        AlertDialog(
            onDismissRequest = { showCopyDialog = false },
            title = { Text("Copy") },
            text = {
                OutlinedTextField(value = copyName, onValueChange = { copyName = it }, label = { Text("Copy Name") })
            },
            confirmButton = {
                Button(onClick = {
                    onCopy(node.path, copyName)
                    showCopyDialog = false
                }) { Text("Copy") }
            },
            dismissButton = {
                TextButton(onClick = { showCopyDialog = false }) { Text("Cancel") }
            }
        )
    }
}
