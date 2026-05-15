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
)

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

    val files: StateFlow<List<FileItem>> = settings
        .map { it.folderUri }
        .distinctUntilChanged()
        .combine(refreshSignals) { uri, _ -> uri }
        .map { uri -> if (uri == null) emptyList() else loadFiles(Uri.parse(uri)) }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private fun loadFiles(treeUri: Uri): List<FileItem> {
        val root = DocumentFile.fromTreeUri(getApplication(), treeUri) ?: return emptyList()
        return root.listFiles()
            .asSequence()
            .filter { it.isFile }
            .map {
                FileItem(
                    name = it.name ?: "?",
                    size = it.length(),
                    modified = it.lastModified(),
                    mime = it.type ?: "application/octet-stream",
                    uri = it.uri,
                )
            }
            .sortedByDescending { it.modified }
            .toList()
    }

    fun refreshFiles() {
        refreshTrigger.tryEmit(Unit)
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
