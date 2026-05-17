package com.wifishare

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.wifishare.input.RemoteInputService
import com.wifishare.screen.ScreenCast
import com.wifishare.screen.ScreenCastService
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.wifishare.ui.AdBanner
import com.wifishare.ui.DevicesScreen
import com.wifishare.ui.FilesScreen
import com.wifishare.ui.HomeScreen
import com.wifishare.ui.MainViewModel
import com.wifishare.ui.SettingsScreen
import com.wifishare.ui.Tab
import com.wifishare.ui.WiFiShareBottomBar
import com.wifishare.ui.WiFiShareTheme


class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored */ }

    /**
     * MediaProjection permission flow — system shows a "Start now /
     * Cancel" dialog; on accept we hand the result Intent to the screen
     * cast service, which uses it to spawn the projection.
     */
    @Volatile
    private var pendingCastMode: ScreenCast.Mode = ScreenCast.Mode.Balanced

    private val mediaProjectionPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCastService.start(this, result.resultCode, result.data!!, pendingCastMode)
            // The user just gave us screen capture — natural moment to
            // also nudge them into enabling remote PC input, since the
            // two features only make sense together. Skips silently if
            // already enabled, or if they declined before in this session.
            maybeOfferRemoteInput()
        }
    }

    @Volatile
    private var remoteInputPromptShown = false

    fun requestScreenCast(mode: ScreenCast.Mode = ScreenCast.Mode.Balanced) {
        pendingCastMode = mode
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionPermission.launch(mgr.createScreenCaptureIntent())
    }

    private fun maybeOfferRemoteInput() {
        if (remoteInputPromptShown) return
        if (RemoteInputService.isEnabled(this)) return
        remoteInputPromptShown = true
        android.app.AlertDialog.Builder(this)
            .setTitle("Enable PC remote input?")
            .setMessage(
                "WiFi Share can let your paired PC click, swipe, and scroll on the phone " +
                    "while you're casting the screen. It uses Android's Accessibility API " +
                    "(no root needed).\n\n" +
                    "Tap Open Settings → find \"WiFi Share remote input\" → flip the switch on. " +
                    "You can revoke it the same way anytime."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                RemoteInputService.openSettings(this)
            }
            .setNegativeButton("Skip", null)
            .setCancelable(true)
            .show()
    }

    fun stopScreenCast() {
        ScreenCastService.stop(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            WiFiShareTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScaffold(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(viewModel: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.Status) }

    Scaffold(
        bottomBar = {
            androidx.compose.foundation.layout.Column {
                AdBanner()
                WiFiShareBottomBar(selected = tab, onSelect = { tab = it })
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.Status -> HomeScreen(
                    viewModel,
                    onOpenSettings = { tab = Tab.Settings },
                    onOpenFiles = { tab = Tab.Files },
                )
                Tab.Files -> FilesScreen(
                    viewModel,
                    onBack = { tab = Tab.Status },
                )
                Tab.Devices -> DevicesScreen(
                    viewModel,
                    onBack = { tab = Tab.Status },
                )
                Tab.Settings -> SettingsScreen(
                    viewModel,
                    onBack = { tab = Tab.Status },
                )
            }
        }
    }
}
