package dev.alice.wgshare

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.alice.wgshare.service.SyncService
import dev.alice.wgshare.ui.AppNav
import dev.alice.wgshare.ui.AppViewModel
import dev.alice.wgshare.ui.theme.WgShareTheme

/**
 * Transport is Tailscale (run separately), so there is no VPN consent to handle here — we just
 * request the notification permission for the foreground service and start it.
 */
class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { SyncService.start(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WgShareTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AppNav(vm) }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else SyncService.start(this)
    }
}
