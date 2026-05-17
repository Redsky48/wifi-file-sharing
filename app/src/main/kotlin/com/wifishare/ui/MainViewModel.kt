package com.wifishare.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wifishare.data.AppSettings
import com.wifishare.data.SettingsRepository
import com.wifishare.server.Clients
import com.wifishare.server.Queue
import com.wifishare.server.ServerService
import com.wifishare.server.Transfers
import com.wifishare.util.NetIp
import com.wifishare.util.WifiMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FileItem(
    val name: String,
    val size: Long,
    val modified: Long,
    val mime: String,
    val uri: Uri,
    val isDirectory: Boolean = false,
)

/** One mounted storage volume (internal, SD card, USB), with usage. */
data class StorageVolumeInfo(
    val label: String,
    val path: String,
    val isPrimary: Boolean,
    val isRemovable: Boolean,
    val usedBytes: Long,
    val totalBytes: Long,
) {
    val freeBytes: Long get() = totalBytes - usedBytes
    val ratio: Float get() = if (totalBytes > 0) usedBytes.toFloat() / totalBytes else 0f
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)

    val settings = repo.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings(),
    )

    val serverState = ServerService.state
    val wifiState = WifiMonitor.state
    val clients = Clients.state
    val pendingQueue = Queue.state

    /**
     * Best reachable LAN IPv4 across all interfaces — WiFi STA, softap
     * (when phone hosts a hotspot), USB tether, etc. Polled every 3 s
     * because Android does not surface ConnectivityManager callbacks
     * for softap interfaces.
     */
    private val _currentIp = MutableStateFlow<String?>(NetIp.preferredIp(WifiMonitor.currentIp()))
    val currentIp: StateFlow<String?> = _currentIp.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                _currentIp.value = NetIp.preferredIp(WifiMonitor.currentIp())
                delay(3_000)
            }
        }
    }

    fun cancelPending(itemId: String) {
        Queue.cancel(itemId)
    }

    fun cancelAllPending() {
        Queue.clearAll()
    }

    private val refreshTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    private val refreshSignals = merge(
        refreshTrigger,
        Transfers.events.map { Unit },
    ).onStart { emit(Unit) }

    /**
     * Folder breadcrumb stack. Index 0 = root (the user-picked folder),
     * each next entry is a nested sub-folder the user drilled into.
     * popFolder() / resetFolderStack() manage navigation; the [files]
     * flow always loads the top of stack.
     */
    private val _folderStack = MutableStateFlow<List<Uri>>(emptyList())
    val folderStack: StateFlow<List<Uri>> = _folderStack.asStateFlow()
    private val _folderNames = MutableStateFlow<List<String>>(emptyList())
    val folderNames: StateFlow<List<String>> = _folderNames.asStateFlow()

    val currentFolder: StateFlow<Uri?> = _folderStack
        .map { it.lastOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // Seed root from settings — and rebase the stack whenever the
        // user picks a different root folder.
        viewModelScope.launch {
            settings.map { it.folderUri }.distinctUntilChanged().collect { rootStr ->
                val rootUri = rootStr?.let(Uri::parse)
                if (rootUri == null) {
                    _folderStack.value = emptyList()
                    _folderNames.value = emptyList()
                } else if (_folderStack.value.firstOrNull() != rootUri) {
                    _folderStack.value = listOf(rootUri)
                    _folderNames.value = listOf("/")
                }
            }
        }
    }

    val files: StateFlow<List<FileItem>> = currentFolder
        .combine(refreshSignals) { uri, _ -> uri }
        .map { uri -> if (uri == null) emptyList() else loadFolderContents(uri) }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private fun loadFolderContents(treeUri: Uri): List<FileItem> {
        val folder = DocumentFile.fromTreeUri(getApplication(), treeUri) ?: return emptyList()
        return folder.listFiles()
            .asSequence()
            .map {
                FileItem(
                    name = it.name ?: "?",
                    size = if (it.isFile) it.length() else 0L,
                    modified = it.lastModified(),
                    mime = it.type ?: if (it.isDirectory) "inode/directory" else "application/octet-stream",
                    uri = it.uri,
                    isDirectory = it.isDirectory,
                )
            }
            // Folders first, then by modified date desc
            .sortedWith(
                compareByDescending<FileItem> { it.isDirectory }
                    .thenByDescending { it.modified },
            )
            .toList()
    }

    fun refreshFiles() {
        refreshTrigger.tryEmit(Unit)
    }

    fun pushFolder(item: FileItem) {
        if (!item.isDirectory) return
        _folderStack.value = _folderStack.value + item.uri
        _folderNames.value = _folderNames.value + item.name
        refreshFiles()
    }

    fun popFolder(): Boolean {
        if (_folderStack.value.size <= 1) return false
        _folderStack.value = _folderStack.value.dropLast(1)
        _folderNames.value = _folderNames.value.dropLast(1)
        refreshFiles()
        return true
    }

    fun isAtFolderRoot(): Boolean = _folderStack.value.size <= 1

    // ── File operations (rename / delete / new folder) ────────────

    suspend fun renameFile(item: FileItem, newName: String): Boolean = withContext(Dispatchers.IO) {
        val file = DocumentFile.fromTreeUri(getApplication(), item.uri) ?: return@withContext false
        val ok = runCatching { file.renameTo(newName) }.getOrDefault(false)
        if (ok) refreshFiles()
        ok
    }

    suspend fun deleteFile(item: FileItem): Boolean = withContext(Dispatchers.IO) {
        val file = DocumentFile.fromTreeUri(getApplication(), item.uri) ?: return@withContext false
        val ok = runCatching { file.delete() }.getOrDefault(false)
        if (ok) refreshFiles()
        ok
    }

    suspend fun createFolder(name: String): Boolean = withContext(Dispatchers.IO) {
        val parentUri = currentFolder.value ?: return@withContext false
        val parent = DocumentFile.fromTreeUri(getApplication(), parentUri)
            ?: return@withContext false
        val ok = runCatching { parent.createDirectory(name) != null }.getOrDefault(false)
        if (ok) refreshFiles()
        ok
    }

    // ── Storage volumes ───────────────────────────────────────────

    val storageVolumes: StateFlow<List<StorageVolumeInfo>> =
        kotlinx.coroutines.flow.flow {
            emit(loadStorageVolumes())
        }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun refreshStorageVolumes() {
        viewModelScope.launch(Dispatchers.IO) {
            // Just trigger re-collection via the flow — but since it's a
            // single-emit flow, we re-load manually:
            _manualStorageRefresh.tryEmit(Unit)
        }
    }

    private val _manualStorageRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private fun loadStorageVolumes(): List<StorageVolumeInfo> {
        val ctx = getApplication<Application>()
        val sm = ctx.getSystemService(android.content.Context.STORAGE_SERVICE)
            as android.os.storage.StorageManager
        val out = mutableListOf<StorageVolumeInfo>()
        runCatching {
            for (v in sm.storageVolumes) {
                val dir = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                    v.directory else null
                val path = dir?.absolutePath ?: "/storage/emulated/0".takeIf { v.isPrimary }
                if (path == null) continue
                val st = runCatching { android.os.StatFs(path) }.getOrNull() ?: continue
                val total = st.totalBytes
                val free = st.availableBytes
                out += StorageVolumeInfo(
                    label = v.getDescription(ctx)
                        ?: (if (v.isPrimary) "Internal storage" else "External"),
                    path = path,
                    isPrimary = v.isPrimary,
                    isRemovable = v.isRemovable,
                    usedBytes = total - free,
                    totalBytes = total,
                )
            }
        }
        // Always include internal as a fallback (some older devices
        // return empty storageVolumes for non-system apps).
        if (out.none { it.isPrimary }) {
            val st = runCatching { android.os.StatFs("/storage/emulated/0") }.getOrNull()
            if (st != null) {
                val total = st.totalBytes
                val free = st.availableBytes
                out += StorageVolumeInfo(
                    label = "Internal storage",
                    path = "/storage/emulated/0",
                    isPrimary = true,
                    isRemovable = false,
                    usedBytes = total - free,
                    totalBytes = total,
                )
            }
        }
        return out
    }

    fun setFolder(uri: Uri, display: String) {
        getApplication<Application>().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        viewModelScope.launch { repo.setFolder(uri.toString(), display) }
    }

    fun setPort(value: Int) = viewModelScope.launch { repo.setPort(value) }
    fun setAllowUploads(value: Boolean) = viewModelScope.launch { repo.setAllowUploads(value) }
    fun setAllowDelete(value: Boolean) = viewModelScope.launch { repo.setAllowDelete(value) }
    fun setAutoStart(value: Boolean) = viewModelScope.launch { repo.setAutoStart(value) }
    fun setPasswordEnabled(value: Boolean) = viewModelScope.launch { repo.setPasswordEnabled(value) }
    fun setPassword(value: String) = viewModelScope.launch { repo.setPassword(value) }
    fun setQuickConnectVisible(value: Boolean) = viewModelScope.launch { repo.setQuickConnectVisible(value) }
    fun setUseBiometric(value: Boolean) = viewModelScope.launch { repo.setUseBiometric(value) }
    fun setAdaptiveBitrate(value: Boolean) = viewModelScope.launch { repo.setAdaptiveBitrate(value) }
    fun setNotificationsEnabled(value: Boolean) = viewModelScope.launch { repo.setNotificationsEnabled(value) }

    fun startServer() {
        if (_currentIp.value == null) return
        val s = settings.value
        val uri = s.folderUri ?: return
        // Only enforce auth when the PIN is fully entered (6 digits) — half
        // a PIN would lock out everyone, including the user.
        val password = if (s.passwordEnabled && s.password.length == 6) s.password else ""
        ServerService.start(
            getApplication(),
            Uri.parse(uri),
            s.port,
            s.allowUploads,
            s.allowDelete,
            password,
        )
    }

    fun stopServer() = ServerService.stop(getApplication())
}
