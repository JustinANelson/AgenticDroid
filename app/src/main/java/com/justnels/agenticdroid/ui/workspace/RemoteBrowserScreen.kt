package com.justnels.agenticdroid.ui.workspace

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.justnels.agenticdroid.env.FileSystemAccess
import com.justnels.agenticdroid.env.FileSystemEntry
import com.justnels.agenticdroid.ui.components.TransferProgressBanner
import com.justnels.agenticdroid.util.FileTransferManager
import com.justnels.agenticdroid.util.TransferProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Browses a remote (e.g. SSH) filesystem with full upload, download, and file management support. */
@Composable
fun RemoteBrowserScreen(
    filesystem: FileSystemAccess,
    rootPath: String,
    onOpenFile: (String) -> Unit,
    onOpenProject: (String) -> Unit,
    onUploadFile: ((Uri, String) -> Unit)? = null,
    onDownloadFile: ((String, String) -> Unit)? = null,
    onDownloadDirectoryArchive: ((String) -> Unit)? = null,
    onDeleteFile: ((String) -> Unit)? = null,
    onRenameFile: ((String, String) -> Unit)? = null,
    transfers: List<TransferProgress> = emptyList(),
    onCancelTransfer: (String) -> Unit = {},
    onDismissTransfer: (String) -> Unit = {},
    shortenDirectoryNames: Boolean = false,
    modifier: Modifier = Modifier
) {
    var currentPath by remember(rootPath) { mutableStateOf(rootPath) }
    var entries by remember { mutableStateOf<List<FileSystemEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshToken by remember { mutableIntStateOf(0) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null && onUploadFile != null) {
            onUploadFile(uri, currentPath)
            refreshToken++
        }
    }

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
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
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
                text = if (shortenDirectoryNames) {
                    currentPath.trimEnd('/').substringAfterLast('/').ifEmpty { currentPath }
                } else {
                    currentPath
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )

            if (onUploadFile != null) {
                IconButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Default.UploadFile, contentDescription = "Upload to current folder", tint = MaterialTheme.colorScheme.primary)
                }
            }

            if (onDownloadDirectoryArchive != null && currentPath != "/") {
                IconButton(onClick = { onDownloadDirectoryArchive(currentPath) }) {
                    Icon(Icons.Default.Archive, contentDescription = "Download folder archive (.tar.gz)")
                }
            }

            IconButton(onClick = { onOpenProject(currentPath) }) {
                Icon(Icons.Default.Folder, contentDescription = "Open as Project", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { refreshToken++ }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }

        if (transfers.isNotEmpty()) {
            TransferProgressBanner(
                transfers = transfers,
                onCancel = onCancelTransfer,
                onDismiss = onDismissTransfer
            )
        }

        when {
            isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Error: $error",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = { refreshToken++ }) {
                            Text("Retry")
                        }
                    }
                }
            }
            entries.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Empty directory", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (onUploadFile != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            FilledTonalButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) {
                                Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Upload File Here")
                            }
                        }
                    }
                }
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries) { entry ->
                        RemoteEntryRow(
                            entry = entry,
                            onOpen = {
                                if (entry.isDirectory) {
                                    currentPath = entry.path
                                } else {
                                    onOpenFile(entry.path)
                                }
                            },
                            onOpenProject = { onOpenProject(entry.path) },
                            onDownload = if (onDownloadFile != null) { { onDownloadFile(entry.path, entry.name) } } else null,
                            onDownloadArchive = if (onDownloadDirectoryArchive != null) { { onDownloadDirectoryArchive(entry.path) } } else null,
                            onDelete = if (onDeleteFile != null) { { onDeleteFile(entry.path); refreshToken++ } } else null,
                            onRename = if (onRenameFile != null) { { newName -> onRenameFile(entry.path, newName); refreshToken++ } } else null
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteEntryRow(
    entry: FileSystemEntry,
    onOpen: () -> Unit,
    onOpenProject: () -> Unit,
    onDownload: (() -> Unit)? = null,
    onDownloadArchive: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onRename: ((String) -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (!entry.isDirectory && entry.size >= 0) {
                Text(
                    text = FileTransferManager.formatBytes(entry.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!entry.isDirectory && onDownload != null) {
            IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Download to phone",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Box {
            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "Entry actions", modifier = Modifier.size(18.dp))
            }

            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                if (entry.isDirectory) {
                    DropdownMenuItem(
                        text = { Text("Open as Project") },
                        onClick = { showMenu = false; onOpenProject() },
                        leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) }
                    )
                    if (onDownloadArchive != null) {
                        DropdownMenuItem(
                            text = { Text("Download as .tar.gz") },
                            onClick = { showMenu = false; onDownloadArchive() },
                            leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) }
                        )
                    }
                } else {
                    if (onDownload != null) {
                        DropdownMenuItem(
                            text = { Text("Download to Phone") },
                            onClick = { showMenu = false; onDownload() },
                            leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Open in Editor") },
                        onClick = { showMenu = false; onOpen() },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                    )
                }

                if (onRename != null) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = { showMenu = false; showRenameDialog = true },
                        leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) }
                    )
                }

                if (onDelete != null) {
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { showMenu = false; onDelete() },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                    )
                }
            }
        }
    }

    if (showRenameDialog && onRename != null) {
        var newName by remember { mutableStateOf(entry.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newName.isNotBlank() && newName != entry.name) {
                        onRename(newName)
                    }
                    showRenameDialog = false
                }) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }
}
