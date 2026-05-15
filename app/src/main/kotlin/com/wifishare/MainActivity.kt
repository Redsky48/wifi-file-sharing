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
import com.wifishare.screen.ScreenCastService
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.wifishare.ui.AdBanner
import com.wifishare.ui.FilesScreen
import com.wifishare.ui.HomeScreen
import com.wifishare.ui.MainViewModel
import com.wifishare.ui.SettingsScreen
import com.wifishare.ui.WiFiShareTheme

private enum class Screen { Home, Files, Settings }

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
    private val mediaProjectionPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCastService.start(this, result.resultCode, result.data!!)
        }
    }

    fun requestScreenCast() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionPermission.launch(mgr.createScreenCaptureIntent())
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
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        when (screen) {
                            Screen.Home -> "WiFi Share"
                            Screen.Files -> "Files"
                            Screen.Settings -> "Settings"
                        }
                    )
                },
                navigationIcon = {
                    if (screen != Screen.Home) {
                        IconButton(onClick = { screen = Screen.Home }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            AdBanner()
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (screen) {
                Screen.Home -> HomeScreen(
                    viewModel,
                    onOpenSettings = { screen = Screen.Settings },
                    onOpenFiles = { screen = Screen.Files },
                )
                Screen.Files -> FilesScreen(viewModel)
                Screen.Settings -> SettingsScreen(viewModel)
            }
        }
    }
}
