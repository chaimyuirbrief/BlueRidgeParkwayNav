package com.blueridge.parkwaynav.ui

import android.app.PictureInPictureParams
import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.blueridge.parkwaynav.nav.ActiveRoute
import com.blueridge.parkwaynav.ui.theme.BlueRidgeTheme
import com.blueridge.parkwaynav.util.LocaleHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        // Apply the user's chosen display language before any views inflate.
        val lang = LocaleHelper.storedLanguageBlocking(newBase)
        super.attachBaseContext(LocaleHelper.wrap(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleDeepLink(intent)

        // Apply "mute on open" / "set volume on open" and keep the cached locale fresh.
        lifecycleScope.launch {
            val settings = com.blueridge.parkwaynav.di.AppContainer.get(applicationContext)
                .settingsRepo.settings.first()
            LocaleHelper.cacheLanguage(applicationContext, settings.language)
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            when {
                settings.muteOnOpen -> am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                settings.setVolumeOnOpen ->
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, (settings.volumeOnOpen * max).toInt(), 0)
            }
        }

        setContent {
            val app: MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
            val settings by app.settings.collectAsState()

            // Keep screen on during use unless the user asked us to ignore it.
            ApplyKeepScreenOn(keepOn = settings.keepScreenOn && !settings.keepScreenOnIgnore)

            BlueRidgeTheme(appearance = settings.appearance) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost(app = app)
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ApplyKeepScreenOn(keepOn: Boolean) {
        androidx.compose.runtime.DisposableEffect(keepOn) {
            if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }

    // Enter picture-in-picture when leaving the app mid-navigation (if enabled).
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ActiveRoute.current.value != null) {
            // Cheap synchronous read of the cached PiP preference (mirrored from DataStore).
            val pipEnabled = getSharedPreferences("brp_locale", Context.MODE_PRIVATE)
                .getBoolean("pip_enabled", true)
            if (pipEnabled) {
                runCatching {
                    enterPictureInPictureMode(
                        PictureInPictureParams.Builder()
                            .setAspectRatio(Rational(3, 4))
                            .build()
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: android.content.Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "brpnav" && data.host == "route") {
            data.getQueryParameter("id")?.let { PendingDeepLink.routeId.value = it }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }
}
