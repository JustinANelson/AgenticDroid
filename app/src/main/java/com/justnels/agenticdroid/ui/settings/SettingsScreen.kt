package com.justnels.agenticdroid.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    onNavigateToEnvironments: () -> Unit,
    onWipeHistory: () -> Unit,
    onInstallApkFromFiles: () -> Unit,
    onRestoreLastKnownGood: () -> Unit,
    hasLastKnownGoodBackup: Boolean,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        item {
            SettingItem(
                title = "Environments",
                description = "Configure Local, Node Toolchain, or SSH",
                onClick = onNavigateToEnvironments
            )
        }

        item {
            SettingItem(
                title = "Install APK from Files",
                description = "Pick an .apk from device storage and install/update it",
                onClick = onInstallApkFromFiles
            )
        }

        item {
            SettingItem(
                title = "Restore Last Working APK",
                description = if (hasLastKnownGoodBackup)
                    "Reinstall the build that was running before your last self-update"
                else
                    "No backup available yet - one is made automatically before each self-update",
                onClick = onRestoreLastKnownGood
            )
        }

        item {
            SettingItem(
                title = "Wipe App Data (Debug)",
                description = "CLEARS ALL projects, tools, and history. App will close.",
                onClick = onWipeHistory
            )
        }
    }
}

@Composable
fun SettingItem(
    title: String,
    description: String,
    onClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(text = description, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}
