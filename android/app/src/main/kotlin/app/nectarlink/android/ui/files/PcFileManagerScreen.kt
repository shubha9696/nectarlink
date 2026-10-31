// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.ui.files

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nectarlink.android.R
import app.nectarlink.android.core.Core
import app.nectarlink.android.core.CoreState
import app.nectarlink.android.core.Device
import app.nectarlink.core.RemoteStorageEntry
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun RemoteStorageEntry.isDirectory(): Boolean =
    kind.equals("dir", ignoreCase = true) ||
        kind.equals("directory", ignoreCase = true) ||
        kind.equals("folder", ignoreCase = true)

private fun parentPath(path: String): String {
    val clean = path.trim('/').replace('\\', '/')
    val lastSlash = clean.lastIndexOf('/')
    return if (lastSlash <= 0) "" else clean.substring(0, lastSlash)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcFileManagerScreen(
    state: CoreState,
    core: Core,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onlinePcs = state.devices.filter { it.online }
    var selectedPcId by remember(onlinePcs) {
        mutableStateOf(onlinePcs.firstOrNull()?.id ?: state.devices.firstOrNull()?.id.orEmpty())
    }
    val currentPc = state.devices.firstOrNull { it.id == selectedPcId }

    var currentPath by remember(selectedPcId) { mutableStateOf("") }
    var entries by remember(selectedPcId) { mutableStateOf<List<RemoteStorageEntry>>(emptyList()) }
    var availableDrives by remember(selectedPcId) { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember(selectedPcId) { mutableStateOf(false) }
    var searchQuery by remember(selectedPcId) { mutableStateOf("") }
    var refreshTick by remember(selectedPcId) { mutableStateOf(0) }

    // Dialog states
    var deleteTarget by remember { mutableStateOf<RemoteStorageEntry?>(null) }
    var renameTarget by remember { mutableStateOf<RemoteStorageEntry?>(null) }
    var renameNewName by remember { mutableStateOf("") }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var downloadingFile by remember { mutableStateOf<String?>(null) }
    var uploadingFile by remember { mutableStateOf(false) }

    // File picker launcher for uploads
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null && currentPc != null) {
            uploadingFile = true
            scope.launch(Dispatchers.IO) {
                try {
                    val fileName = runCatching {
                        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (cursor.moveToFirst() && nameIdx >= 0) cursor.getString(nameIdx) else null
                        }
                    }.getOrNull() ?: "upload_${System.currentTimeMillis()}"

                    val cacheFile = File(context.cacheDir, fileName)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(cacheFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    val destPath = if (currentPath.isEmpty()) fileName else "$currentPath/$fileName"
                    core.uploadPcFile(currentPc.id, destPath, cacheFile) { success ->
                        uploadingFile = false
                        cacheFile.delete()
                        if (success) {
                            Toast.makeText(context, "Uploaded $fileName to PC", Toast.LENGTH_SHORT).show()
                            refreshTick++
                        } else {
                            Toast.makeText(context, "Upload failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    uploadingFile = false
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Load folder contents
    LaunchedEffect(selectedPcId, currentPath, refreshTick) {
        if (selectedPcId.isEmpty()) return@LaunchedEffect
        loading = true
        core.listPcFiles(selectedPcId, currentPath) { folder ->
            val items = folder.entries.sortedWith(
                compareBy<RemoteStorageEntry> { !it.isDirectory() }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
            )
            entries = items
            loading = false

            // Discover drives if at root
            if (currentPath.isEmpty()) {
                val drives = items.filter { it.isDirectory() && it.name.contains(':') }.map { it.name }
                if (drives.isNotEmpty()) {
                    availableDrives = drives
                }
            }
        }
    }

    val filteredEntries = remember(entries, searchQuery) {
        if (searchQuery.isBlank()) entries
        else entries.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // Header / PC Switcher
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "PC Files",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = currentPc?.name ?: "No PC connected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { refreshTick++ }) {
                    Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Refresh")
                }
            }

            Spacer(Modifier.height(8.dp))

            // Drive & Quick folder navigation chips
            val quickChips = remember(availableDrives) {
                val list = mutableListOf<Pair<String, String>>()
                list.add("" to "All Roots")
                for (drive in availableDrives) {
                    list.add(drive to drive)
                }
                if (!availableDrives.contains("C:")) {
                    list.add("C:" to "C:")
                }
                list.add("Downloads" to "Downloads")
                list.add("Documents" to "Documents")
                list.add("Desktop" to "Desktop")
                list.add("Pictures" to "Pictures")
                list
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                quickChips.forEach { (path, label) ->
                    val isSelected = currentPath.equals(path, ignoreCase = true)
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            currentPath = path
                            searchQuery = ""
                        },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Breadcrumb path navigation bar
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (currentPath.isNotEmpty()) {
                        OutlinedButton(
                            onClick = {
                                currentPath = parentPath(currentPath)
                                searchQuery = ""
                            },
                            contentPadding = ButtonDefaults.TextButtonContentPadding,
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text("Up", fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                    }

                    Text(
                        text = if (currentPath.isEmpty()) "Root" else currentPath.replace('\\', '/'),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Filter files & folders…") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            if (loading || downloadingFile != null || uploadingFile) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(8.dp))

            // Files & Folders List
            if (filteredEntries.isEmpty() && !loading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (searchQuery.isNotEmpty()) "No matching files" else "Folder is empty",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(filteredEntries, key = { it.path.ifEmpty { it.name } }) { entry ->
                        val isDir = entry.isDirectory()
                        val fullPath = entry.path.ifEmpty {
                            if (currentPath.isEmpty()) entry.name else "$currentPath/${entry.name}"
                        }

                        FileEntryCard(
                            entry = entry,
                            isDir = isDir,
                            onClick = {
                                if (isDir) {
                                    currentPath = fullPath
                                    searchQuery = ""
                                } else if (currentPc != null) {
                                    downloadingFile = entry.name
                                    core.downloadPcFile(currentPc.id, fullPath, entry.name) { savedUri ->
                                        downloadingFile = null
                                        if (savedUri != null) {
                                            Toast.makeText(context, "Saved to Downloads", Toast.LENGTH_SHORT).show()
                                            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(savedUri, context.contentResolver.getType(savedUri) ?: "*/*")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            runCatching { context.startActivity(viewIntent) }
                                        } else {
                                            Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            onDelete = { deleteTarget = entry },
                            onRename = {
                                renameTarget = entry
                                renameNewName = entry.name
                            },
                            onCopyPath = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("PC Path", fullPath))
                                Toast.makeText(context, "Path copied", Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                    // Bottom spacer for floating buttons & dock
                    item { Spacer(Modifier.height(110.dp)) }
                }
            }
        }

        // Floating action buttons for New Folder and Upload
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FloatingActionButton(
                onClick = {
                    newFolderName = ""
                    showNewFolderDialog = true
                },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(48.dp),
            ) {
                Text("+📁", fontSize = 18.sp)
            }

            FloatingActionButton(
                onClick = { uploadPicker.launch("*/*") },
                containerColor = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
                modifier = Modifier.size(56.dp),
            ) {
                Text("⬆", fontSize = 24.sp, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }

    // Delete Confirmation Dialog
    deleteTarget?.let { entry ->
        val fullPath = entry.path.ifEmpty {
            if (currentPath.isEmpty()) entry.name else "$currentPath/${entry.name}"
        }
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${if (entry.isDirectory()) "folder" else "file"}?") },
            text = { Text("Are you sure you want to delete '${entry.name}' from your PC? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        val pcId = currentPc?.id ?: return@Button
                        deleteTarget = null
                        core.deletePcFile(pcId, fullPath) { success ->
                            if (success) {
                                Toast.makeText(context, "Deleted ${entry.name}", Toast.LENGTH_SHORT).show()
                                refreshTick++
                            } else {
                                Toast.makeText(context, "Delete failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }

    // Rename Dialog
    renameTarget?.let { entry ->
        val fromPath = entry.path.ifEmpty {
            if (currentPath.isEmpty()) entry.name else "$currentPath/${entry.name}"
        }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename '${entry.name}'") },
            text = {
                OutlinedTextField(
                    value = renameNewName,
                    onValueChange = { renameNewName = it },
                    label = { Text("New name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pcId = currentPc?.id ?: return@Button
                        val trimmed = renameNewName.trim()
                        renameTarget = null
                        if (trimmed.isNotEmpty() && trimmed != entry.name) {
                            val toPath = if (currentPath.isEmpty()) trimmed else "$currentPath/$trimmed"
                            core.renamePcFile(pcId, fromPath, toPath) { success ->
                                if (success) {
                                    Toast.makeText(context, "Renamed to $trimmed", Toast.LENGTH_SHORT).show()
                                    refreshTick++
                                } else {
                                    Toast.makeText(context, "Rename failed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            },
        )
    }

    // New Folder Dialog
    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("New Folder") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pcId = currentPc?.id ?: return@Button
                        val name = newFolderName.trim()
                        showNewFolderDialog = false
                        if (name.isNotEmpty()) {
                            val folderPath = if (currentPath.isEmpty()) name else "$currentPath/$name"
                            core.mkdirPcFile(pcId, folderPath) { success ->
                                if (success) {
                                    Toast.makeText(context, "Folder created", Toast.LENGTH_SHORT).show()
                                    refreshTick++
                                } else {
                                    Toast.makeText(context, "Failed to create folder", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun FileEntryCard(
    entry: RemoteStorageEntry,
    isDir: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onCopyPath: () -> Unit,
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }

    val formattedSize = remember(entry.size, isDir) {
        if (isDir) "Folder" else Formatter.formatFileSize(context, entry.size.toLong())
    }

    val formattedDate = remember(entry.modified) {
        if (entry.modified > 0) {
            SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(entry.modified))
        } else ""
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isDir) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isDir) {
                    Icon(
                        painter = painterResource(R.drawable.ic_folder),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_file),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (formattedDate.isNotEmpty()) "$formattedSize • $formattedDate" else formattedSize,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 3-dot overflow menu
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Text("⋮", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    if (!isDir) {
                        DropdownMenuItem(
                            text = { Text("Download & Open") },
                            onClick = {
                                menuExpanded = false
                                onClick()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Copy Path") },
                        onClick = {
                            menuExpanded = false
                            onCopyPath()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

