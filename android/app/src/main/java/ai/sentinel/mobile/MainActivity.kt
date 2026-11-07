package ai.sentinel.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import ai.sentinel.mobile.net.VpnController
import ai.sentinel.mobile.ui.adaptive.toWindowSize
import ai.sentinel.mobile.ui.nav.SentinelRoot
import ai.sentinel.mobile.ui.theme.SentinelTheme
import ai.sentinel.mobile.ui.vm.AppViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.startProtection()
        }
    }

    private val notificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            // Width size class drives navigation shape and spacing; height shrinks pads in
            // landscape. The activity handles configuration changes itself, so rotating does
            // not restart it.
            val windowSize = calculateWindowSizeClass(this).widthSizeClass.toWindowSize()
            SentinelTheme(size = windowSize) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // targetSdk 35 draws edge to edge: keep content clear of the status bar,
                    // navigation bar and display cutouts so no control is unreachable.
                    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                        SentinelRoot(viewModel, windowSize) { requestProtection() }
                    }
                }
            }
        }
    }

    /**
     * Always goes through the system consent dialog. If consent was granted before, prepare()
     * returns null and the tunnel starts directly.
     */
    private fun requestProtection() {
        val consent = VpnController.consentIntent(this)
        if (consent != null) {
            vpnConsent.launch(consent)
        } else {
            viewModel.startProtection()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            (application as SentinelApp).container.detector.release()
        }
    }
}
