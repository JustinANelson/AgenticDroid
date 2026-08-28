package com.justnels.agenticdroid.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justnels.agenticdroid.util.FileShare
import com.justnels.agenticdroid.util.FileTransferManager
import com.justnels.agenticdroid.util.TransferDirection
import com.justnels.agenticdroid.util.TransferProgress
import com.justnels.agenticdroid.util.TransferStatus
import java.io.File

@Composable
fun TransferProgressBanner(
    transfers: List<TransferProgress>,
    onCancel: (String) -> Unit,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (transfers.isEmpty()) return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            transfers.take(3).forEachIndexed { index, transfer ->
                if (index > 0) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
                TransferItemView(
                    transfer = transfer,
                    onCancel = { onCancel(transfer.id) },
                    onDismiss = { onDismiss(transfer.id) }
                )
            }
            if (transfers.size > 3) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "+ ${transfers.size - 3} more transfer(s)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TransferItemView(
    transfer: TransferProgress,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val icon = when (transfer.status) {
                TransferStatus.COMPLETED -> Icons.Default.CheckCircle
                TransferStatus.FAILED -> Icons.Default.Error
                TransferStatus.CANCELLED -> Icons.Default.Cancel
                TransferStatus.IN_PROGRESS, TransferStatus.PENDING -> {
                    if (transfer.direction == TransferDirection.UPLOAD) Icons.Default.CloudUpload
                    else Icons.Default.CloudDownload
                }
            }

            val iconColor = when (transfer.status) {
                TransferStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                TransferStatus.FAILED -> MaterialTheme.colorScheme.error
                TransferStatus.CANCELLED -> MaterialTheme.colorScheme.outline
                TransferStatus.IN_PROGRESS, TransferStatus.PENDING -> MaterialTheme.colorScheme.primary
            }

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transfer.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when (transfer.status) {
                        TransferStatus.PENDING -> "Preparing transfer..."
                        TransferStatus.IN_PROGRESS -> {
                            val dirText = if (transfer.direction == TransferDirection.UPLOAD) "Uploading" else "Downloading"
                            val speedText = if (transfer.bytesPerSecond > 0) " • ${FileTransferManager.formatSpeed(transfer.bytesPerSecond)}" else ""
                            val etaText = FileTransferManager.formatEta(transfer.etaSeconds).let { if (it.isNotBlank()) " • ETA $it" else "" }
                            val sizeText = if (transfer.totalBytes > 0) {
                                "${FileTransferManager.formatBytes(transfer.bytesTransferred)} / ${FileTransferManager.formatBytes(transfer.totalBytes)} (${transfer.percentage}%)"
                            } else {
                                FileTransferManager.formatBytes(transfer.bytesTransferred)
                            }
                            "$dirText $sizeText$speedText$etaText"
                        }
                        TransferStatus.COMPLETED -> "Transfer completed"
                        TransferStatus.CANCELLED -> "Transfer cancelled"
                        TransferStatus.FAILED -> "Failed: ${transfer.errorMessage ?: "Unknown error"}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (transfer.status == TransferStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (transfer.status == TransferStatus.IN_PROGRESS || transfer.status == TransferStatus.PENDING) {
                IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel transfer",
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                if (transfer.status == TransferStatus.COMPLETED && transfer.direction == TransferDirection.DOWNLOAD) {
                    val context = LocalContext.current
                    IconButton(
                        onClick = { FileShare.shareFile(context, File(transfer.localDescription)) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Save or share downloaded file",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        if (transfer.status == TransferStatus.IN_PROGRESS || transfer.status == TransferStatus.PENDING) {
            Spacer(modifier = Modifier.height(6.dp))
            if (transfer.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { transfer.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                )
            }
        }
    }
}
