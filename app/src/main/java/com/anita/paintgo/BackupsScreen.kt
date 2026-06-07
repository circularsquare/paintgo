package com.anita.paintgo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anita.paintgo.backup.BackupFile
import com.anita.paintgo.backup.BackupManager
import com.anita.paintgo.backup.RestoreResult
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.system.exitProcess

private val DISPLAY_FMT: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.getDefault())

private fun formatTimestamp(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(DISPLAY_FMT)

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

// Restore swaps the DB file out from under Room, so the cleanest way to rebind the whole
// app (Room instance + every Compose Flow) is a full process restart: relaunch the entry
// activity in a fresh task and exit the current process. The new process reopens the
// restored database from scratch.
private fun restartApp(context: Context): Nothing {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(launch)
    exitProcess(0)
}

@Composable
fun BackupsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var backups by remember { mutableStateOf<List<BackupFile>?>(null) } // null = still loading
    var creating by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<BackupFile?>(null) }
    var pendingRestore by remember { mutableStateOf<BackupFile?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        backups = BackupManager.listBackups(context)
    }

    LaunchedEffect(Unit) { reload() }

    // Any DB operation in flight disables the others. BackupManager also serializes them
    // with a mutex; this just keeps the UI from queueing a second one.
    val busy = creating || restoring

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Button(
            onClick = {
                scope.launch {
                    creating = true
                    message = try {
                        val b = BackupManager.createBackup(context)
                        reload()
                        "Backup created (${formatSize(b.sizeBytes)})"
                    } catch (e: Exception) {
                        "Backup failed: ${e.message ?: e.javaClass.simpleName}"
                    } finally {
                        creating = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (creating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.size(8.dp))
                Text("Creating backup…")
            } else {
                Text("Create backup")
            }
        }

        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        val list = backups
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No backups yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(list, key = { it.name }) { backup ->
                    BackupRow(
                        backup,
                        enabled = !busy,
                        onRestore = { pendingRestore = backup },
                        onDelete = { pendingDelete = backup },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete backup?") },
            text = { Text("This permanently removes the backup from ${formatTimestamp(target.lastModified)}. Your current walks are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        val ok = BackupManager.deleteBackup(context, target.name)
                        reload()
                        message = if (ok) "Backup deleted" else "Couldn't delete backup"
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }

    pendingRestore?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "This replaces all your current walks with the backup from " +
                        "${formatTimestamp(target.lastModified)}, then restarts the app. " +
                        "This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    scope.launch {
                        restoring = true
                        when (val r = BackupManager.restoreBackup(context, target.name)) {
                            // Success never returns to the UI — the process exits and relaunches.
                            RestoreResult.Success -> restartApp(context)
                            is RestoreResult.Failure -> {
                                message = r.reason
                                restoring = false
                            }
                        }
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BackupRow(
    backup: BackupFile,
    enabled: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(formatTimestamp(backup.lastModified), style = MaterialTheme.typography.titleMedium)
            Text(
                "${backup.name} · ${formatSize(backup.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Backup options")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Restore") },
                    onClick = { menuOpen = false; onRestore() },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}
