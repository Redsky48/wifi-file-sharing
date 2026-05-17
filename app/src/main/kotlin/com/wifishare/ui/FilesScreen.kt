package com.wifishare.ui

import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifishare.util.IntentHelpers
import kotlinx.coroutines.launch

@Composable
fun FilesScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by viewModel.settings.collectAsState()
    val files by viewModel.files.collectAsState()
    val folderNames by viewModel.folderNames.collectAsState()
    val storageVolumes by viewModel.storageVolumes.collectAsState()

    var search by rememberSaveable { mutableStateOf("") }
    var gridView by rememberSaveable { mutableStateOf(false) }
    var newFolderDialog by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf<FileItem?>(null) }
    var deleteDialog by remember { mutableStateOf<FileItem?>(null) }
    var storageExpanded by rememberSaveable { mutableStateOf(false) }

    val visible = remember(files, search) {
        if (search.isBlank()) files
        else files.filter { it.name.contains(search, ignoreCase = true) }
    }

    val pickRoot = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.setFolder(uri, friendlyName(uri))
    }

    // Phone back / gesture: pop sub-folder first, then leave to Status.
    BackHandler(enabled = !viewModel.isAtFolderRoot()) {
        viewModel.popFolder()
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FilesHeader(
                onBack = {
                    if (!viewModel.popFolder()) onBack()
                },
            )
            FolderBanner(
                breadcrumb = folderNames,
                itemCount = files.size,
                hasRoot = settings.folderUri != null,
                onRefresh = viewModel::refreshFiles,
                onOpenSystem = {
                    val uri = viewModel.currentFolder.value
                    if (uri != null) IntentHelpers.openFolder(context, uri)
                    else pickRoot.launch(null)
                },
                onPickNewRoot = { pickRoot.launch(null) },
                onPopFolder = { if (folderNames.size > 1) viewModel.popFolder() },
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchField(value = search, onChange = { search = it }, modifier = Modifier.weight(1f))
                ViewToggle(grid = gridView, onChange = { gridView = it })
            }

            Box(Modifier.weight(1f, fill = true)) {
                when {
                    settings.folderUri == null -> EmptyState(
                        "Tap the folder banner above to pick a folder.",
                        onClick = { pickRoot.launch(null) },
                    )
                    visible.isEmpty() -> EmptyState(
                        if (search.isNotBlank()) "Nothing matches \"$search\""
                        else "This folder is empty. Tap + New to create a sub-folder.",
                    )
                    gridView -> FileGrid(
                        items = visible,
                        onItemClick = { item -> handleItemClick(context, viewModel, item) },
                        onItemAction = { item, action ->
                            handleAction(action, item,
                                onRename = { renameDialog = item },
                                onDelete = { deleteDialog = item },
                                onShare = { IntentHelpers.shareFile(context, item.uri, item.mime) })
                        },
                    )
                    else -> FileList(
                        items = visible,
                        onItemClick = { item -> handleItemClick(context, viewModel, item) },
                        onItemAction = { item, action ->
                            handleAction(action, item,
                                onRename = { renameDialog = item },
                                onDelete = { deleteDialog = item },
                                onShare = { IntentHelpers.shareFile(context, item.uri, item.mime) })
                        },
                    )
                }
            }

            StorageCard(
                volumes = storageVolumes,
                expanded = storageExpanded,
                onToggle = { storageExpanded = !storageExpanded },
            )
            Spacer(Modifier.height(8.dp))
        }

        NewFab(
            onClick = { newFolderDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 100.dp),
        )
    }

    if (newFolderDialog) {
        NewFolderDialog(
            onDismiss = { newFolderDialog = false },
            onCreate = { name ->
                scope.launch { viewModel.createFolder(name) }
                newFolderDialog = false
            },
        )
    }
    renameDialog?.let { item ->
        RenameDialog(
            current = item.name,
            onDismiss = { renameDialog = null },
            onRename = { newName ->
                scope.launch { viewModel.renameFile(item, newName) }
                renameDialog = null
            },
        )
    }
    deleteDialog?.let { item ->
        DeleteConfirmDialog(
            item = item,
            onDismiss = { deleteDialog = null },
            onConfirm = {
                scope.launch { viewModel.deleteFile(item) }
                deleteDialog = null
            },
        )
    }
}

private fun handleItemClick(context: Context, viewModel: MainViewModel, item: FileItem) {
    if (item.isDirectory) viewModel.pushFolder(item)
    else IntentHelpers.openFile(context, item.uri, item.mime)
}

private enum class RowAction { Rename, Delete, Share }

private fun handleAction(
    action: RowAction,
    item: FileItem,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
) {
    when (action) {
        RowAction.Rename -> onRename()
        RowAction.Delete -> onDelete()
        RowAction.Share -> onShare()
    }
}

// ── Header ────────────────────────────────────────────────────────

@Composable
private fun FilesHeader(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        CircleIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Files", fontWeight = FontWeight.Bold, fontSize = 28.sp,
                color = WiFiShareColors.OnSurface)
            Text("Manage files on your device via WiFi",
                color = WiFiShareColors.OnSurfaceMuted, fontSize = 14.sp)
        }
        CircleIconButton(icon = Icons.Default.Tune, onClick = {})
    }
}

@Composable
private fun CircleIconButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(top = 4.dp)
            .size(40.dp)
            .clip(CircleShape)
            .background(WiFiShareColors.PrimaryFaint)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = WiFiShareColors.PrimaryDeep)
    }
}

// ── Folder banner with breadcrumb ─────────────────────────────────

@Composable
private fun FolderBanner(
    breadcrumb: List<String>,
    itemCount: Int,
    hasRoot: Boolean,
    onRefresh: () -> Unit,
    onOpenSystem: () -> Unit,
    onPickNewRoot: () -> Unit,
    onPopFolder: () -> Unit,
) {
    val current = breadcrumb.lastOrNull() ?: "No folder picked"
    val path = if (breadcrumb.size <= 1) null
        else breadcrumb.drop(1).joinToString(" › ")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, WiFiShareColors.OutlineSoft),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = if (hasRoot) onPopFolder else onPickNewRoot)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(WiFiShareColors.PrimaryFaint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Folder, null, Modifier.size(22.dp),
                    tint = WiFiShareColors.PrimaryDeep)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (current == "/") "Root folder" else current,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = WiFiShareColors.OnSurface,
                    maxLines = 1,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (path != null) {
                        Text(
                            path,
                            color = WiFiShareColors.OnSurfaceMuted,
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    } else {
                        Text(
                            "$itemCount item" + if (itemCount == 1) "" else "s",
                            color = WiFiShareColors.OnSurfaceMuted,
                            fontSize = 12.sp,
                        )
                        if (hasRoot) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = WiFiShareColors.LiveGreenBg,
                            ) {
                                Text(
                                    "Default folder",
                                    Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    color = WiFiShareColors.LiveGreen,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
            CircleSoftButton(icon = Icons.Default.Refresh, onClick = onRefresh)
            Spacer(Modifier.width(8.dp))
            OpenPillButton(onClick = onOpenSystem)
            Spacer(Modifier.width(4.dp))
            CircleSoftButton(icon = Icons.Default.MoreVert, onClick = onPickNewRoot)
        }
    }
}

@Composable
private fun CircleSoftButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .border(1.dp, WiFiShareColors.OutlineSoft, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = WiFiShareColors.OnSurfaceMuted)
    }
}

@Composable
private fun OpenPillButton(onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, WiFiShareColors.Primary, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.FolderOpen, null, Modifier.size(16.dp), tint = WiFiShareColors.Primary)
        Spacer(Modifier.width(6.dp))
        Text("Open", color = WiFiShareColors.Primary, fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp)
    }
}

// ── Search + view toggle ──────────────────────────────────────────

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text("Search files and folders", color = WiFiShareColors.OnSurfaceMuted) },
        leadingIcon = {
            Icon(Icons.Default.Search, null, tint = WiFiShareColors.OnSurfaceMuted)
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedIndicatorColor = WiFiShareColors.OutlineSoft,
            focusedIndicatorColor = WiFiShareColors.Primary,
        ),
        modifier = modifier,
    )
}

@Composable
private fun ViewToggle(grid: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, WiFiShareColors.OutlineSoft, RoundedCornerShape(12.dp)),
    ) {
        ToggleSegment(icon = Icons.Default.ViewList, active = !grid, onClick = { onChange(false) })
        ToggleSegment(icon = Icons.Default.ViewModule, active = grid, onClick = { onChange(true) })
    }
}

@Composable
private fun ToggleSegment(icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) WiFiShareColors.PrimaryFaint else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(20.dp),
            tint = if (active) WiFiShareColors.PrimaryDeep else WiFiShareColors.OnSurfaceMuted)
    }
}

// ── List view ─────────────────────────────────────────────────────

@Composable
private fun FileList(
    items: List<FileItem>,
    onItemClick: (FileItem) -> Unit,
    onItemAction: (FileItem, RowAction) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, WiFiShareColors.OutlineSoft),
    ) {
        LazyColumn {
            items(items, key = { it.uri.toString() }) { item ->
                Column {
                    FileRow(
                        item = item,
                        onClick = { onItemClick(item) },
                        onAction = { onItemAction(item, it) },
                    )
                    if (item != items.last()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 64.dp, end = 14.dp)
                                .height(1.dp)
                                .background(WiFiShareColors.OutlineSoft.copy(alpha = 0.6f)),
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun FileRow(
    item: FileItem,
    onClick: () -> Unit,
    onAction: (RowAction) -> Unit,
) {
    val context = LocalContext.current
    val palette = paletteForItem(item)
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.bg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(palette.icon, null, Modifier.size(20.dp), tint = palette.fg)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                maxLines = 1, color = WiFiShareColors.OnSurface)
            Text(
                buildString {
                    if (item.isDirectory) append("Folder")
                    else append(Formatter.formatShortFileSize(context, item.size))
                    if (item.modified > 0) {
                        append("  ·  ")
                        append(
                            DateUtils.getRelativeTimeSpanString(
                                item.modified,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS,
                            )
                        )
                    }
                },
                color = WiFiShareColors.OnSurfaceMuted,
                fontSize = 12.sp,
            )
        }
        Box {
            Icon(
                Icons.Default.MoreHoriz, null,
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable { menuOpen = true }
                    .padding(4.dp),
                tint = WiFiShareColors.OnSurfaceMuted,
            )
            RowActionsMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                onPick = { onAction(it); menuOpen = false },
                isDirectory = item.isDirectory,
            )
        }
        if (item.isDirectory) {
            Icon(
                Icons.Default.ChevronRight, null,
                Modifier.size(22.dp),
                tint = WiFiShareColors.OnSurfaceMuted,
            )
        }
    }
}

@Composable
private fun RowActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPick: (RowAction) -> Unit,
    isDirectory: Boolean,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.background(MaterialTheme.colorScheme.surface),
    ) {
        DropdownMenuItem(
            text = { Text("Rename") },
            leadingIcon = { Icon(Icons.Default.Edit, null) },
            onClick = { onPick(RowAction.Rename) },
        )
        if (!isDirectory) {
            DropdownMenuItem(
                text = { Text("Share") },
                leadingIcon = { Icon(Icons.Default.Share, null) },
                onClick = { onPick(RowAction.Share) },
            )
        }
        DropdownMenuItem(
            text = { Text("Delete", color = Color(0xFFE54D6F)) },
            leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color(0xFFE54D6F)) },
            onClick = { onPick(RowAction.Delete) },
        )
    }
}

// ── Grid view ─────────────────────────────────────────────────────

@Composable
private fun FileGrid(
    items: List<FileItem>,
    onItemClick: (FileItem) -> Unit,
    onItemAction: (FileItem, RowAction) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.uri.toString() }) { item ->
            FileGridCell(
                item = item,
                onClick = { onItemClick(item) },
                onAction = { onItemAction(item, it) },
            )
        }
    }
}

@Composable
private fun FileGridCell(
    item: FileItem,
    onClick: () -> Unit,
    onAction: (RowAction) -> Unit,
) {
    val context = LocalContext.current
    val palette = paletteForItem(item)
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, WiFiShareColors.OutlineSoft),
    ) {
        Column(
            Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.bg)
                        .align(Alignment.Center),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(palette.icon, null, Modifier.size(28.dp), tint = palette.fg)
                }
                Box {
                    Icon(
                        Icons.Default.MoreVert, null,
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable { menuOpen = true }
                            .padding(4.dp),
                        tint = WiFiShareColors.OnSurfaceMuted,
                    )
                    RowActionsMenu(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        onPick = { onAction(it); menuOpen = false },
                        isDirectory = item.isDirectory,
                    )
                }
            }
            Text(
                item.name,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                maxLines = 2,
                color = WiFiShareColors.OnSurface,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Text(
                if (item.isDirectory) "Folder"
                else Formatter.formatShortFileSize(context, item.size),
                color = WiFiShareColors.OnSurfaceMuted,
                fontSize = 11.sp,
            )
        }
    }
}

// ── Icons by mime / extension ─────────────────────────────────────

private data class IconPalette(val icon: ImageVector, val fg: Color, val bg: Color)

private fun paletteForItem(item: FileItem): IconPalette {
    if (item.isDirectory) return IconPalette(
        Icons.Default.Folder, WiFiShareColors.PrimaryDeep, WiFiShareColors.PrimaryFaint)
    val ext = item.name.substringAfterLast('.', "").lowercase()
    val m = item.mime.lowercase()
    return when {
        m.startsWith("image/") -> IconPalette(
            Icons.Default.Image, Color(0xFFE85A8E), Color(0xFFFEE7EF))
        m.startsWith("video/") -> IconPalette(
            Icons.Default.VideoFile, Color(0xFFC4528A), Color(0xFFF8E5F0))
        m.startsWith("audio/") -> IconPalette(
            Icons.Default.Audiotrack, Color(0xFFD17C00), Color(0xFFFFEFD6))
        ext == "apk" -> IconPalette(
            Icons.Default.Android, Color(0xFF1AB071), WiFiShareColors.LiveGreenBg)
        ext in setOf("json", "jsonl", "yaml", "yml", "xml", "html", "csv", "log") -> IconPalette(
            Icons.Default.Description, Color(0xFF3B82F6), Color(0xFFE5EEFE))
        ext in setOf("txt", "md", "rtf", "pdf", "doc", "docx") -> IconPalette(
            Icons.Default.Description, WiFiShareColors.PrimaryDeep, WiFiShareColors.PrimaryFaint)
        else -> IconPalette(
            Icons.Default.InsertDriveFile, WiFiShareColors.OnSurfaceMuted,
            WiFiShareColors.SurfaceVariant)
    }
}

// ── Storage card with expandable volumes list ─────────────────────

@Composable
private fun StorageCard(
    volumes: List<StorageVolumeInfo>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    val primary = volumes.firstOrNull { it.isPrimary } ?: volumes.firstOrNull()
    val usedAll = volumes.sumOf { it.usedBytes }
    val totalAll = volumes.sumOf { it.totalBytes }
    val ratio = if (totalAll > 0) usedAll.toFloat() / totalAll else 0f
    val used = Formatter.formatShortFileSize(context, usedAll)
    val total = Formatter.formatShortFileSize(context, totalAll)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, WiFiShareColors.OutlineSoft),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Canvas(Modifier.size(46.dp)) {
                        val stroke = 6.dp.toPx()
                        drawArc(
                            color = WiFiShareColors.PrimaryFaint,
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = stroke),
                        )
                        drawArc(
                            color = WiFiShareColors.Primary,
                            startAngle = -90f,
                            sweepAngle = 360f * ratio,
                            useCenter = false,
                            style = Stroke(width = stroke),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Storage usage", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("$used used of $total",
                        color = WiFiShareColors.OnSurfaceMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(WiFiShareColors.SurfaceVariant),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(ratio.coerceIn(0f, 1f))
                                .height(6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(WiFiShareColors.Primary),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "${(ratio * 100).toInt()}% used",
                    color = WiFiShareColors.Primary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    Modifier.size(20.dp),
                    tint = WiFiShareColors.OnSurfaceMuted,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(WiFiShareColors.OutlineSoft),
                    )
                    if (volumes.isEmpty()) {
                        Text(
                            "No mounted volumes detected.",
                            color = WiFiShareColors.OnSurfaceMuted,
                            fontSize = 12.sp,
                        )
                    } else {
                        volumes.forEach { v -> StorageVolumeRow(v) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageVolumeRow(v: StorageVolumeInfo) {
    val context = LocalContext.current
    val used = Formatter.formatShortFileSize(context, v.usedBytes)
    val total = Formatter.formatShortFileSize(context, v.totalBytes)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(WiFiShareColors.PrimaryFaint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (v.isRemovable) Icons.Default.SdStorage else Icons.Default.Smartphone,
                null,
                Modifier.size(18.dp),
                tint = WiFiShareColors.PrimaryDeep,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(v.label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text("$used of $total", color = WiFiShareColors.OnSurfaceMuted, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(WiFiShareColors.SurfaceVariant),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(v.ratio.coerceIn(0f, 1f))
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(WiFiShareColors.Primary),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "${(v.ratio * 100).toInt()}%",
            color = WiFiShareColors.Primary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
        )
    }
}

// ── FAB + dialogs ─────────────────────────────────────────────────

@Composable
private fun NewFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
        color = Color.Transparent,
    ) {
        Row(
            Modifier
                .background(
                    Brush.horizontalGradient(
                        listOf(WiFiShareColors.Primary, Color(0xFF8E6FFC)),
                    ),
                )
                .padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("New", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun NewFolderDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New folder", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Folder name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text("Create", color = WiFiShareColors.Primary, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = WiFiShareColors.OnSurfaceMuted)
            }
        },
    )
}

@Composable
private fun RenameDialog(
    current: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(name.trim()) },
                enabled = name.isNotBlank() && name != current,
            ) {
                Text("Save", color = WiFiShareColors.Primary, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = WiFiShareColors.OnSurfaceMuted)
            }
        },
    )
}

@Composable
private fun DeleteConfirmDialog(
    item: FileItem,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (item.isDirectory) "Delete folder?" else "Delete file?",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                if (item.isDirectory)
                    "\"${item.name}\" and everything inside it will be permanently deleted."
                else
                    "\"${item.name}\" will be permanently deleted.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = Color(0xFFE54D6F), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = WiFiShareColors.OnSurfaceMuted)
            }
        },
    )
}

// ── Empty state ───────────────────────────────────────────────────

@Composable
private fun EmptyState(text: String, onClick: (() -> Unit)? = null) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, WiFiShareColors.OutlineSoft),
    ) {
        Column(
            Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(WiFiShareColors.PrimaryFaint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Folder, null, Modifier.size(28.dp),
                    tint = WiFiShareColors.PrimaryDeep)
            }
            Spacer(Modifier.height(10.dp))
            Text(text, color = WiFiShareColors.OnSurfaceMuted, fontSize = 13.sp)
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────

private fun friendlyName(uri: Uri): String {
    val docId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }
        .getOrNull() ?: return uri.toString()
    val parts = docId.split(":")
    return if (parts.size == 2) "${parts[0]}: ${parts[1].ifBlank { "/" }}" else docId
}
