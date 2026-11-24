package com.deepaksigroha.imageclean

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepaksigroha.imageclean.ui.AppRoot
import com.deepaksigroha.imageclean.ui.MainViewModel
import com.deepaksigroha.imageclean.ui.theme.ImageCleanTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)
        setContent {
            val theme by vm.prefs.theme.collectAsStateWithLifecycle()
            val dynamic by vm.prefs.dynamicColor.collectAsStateWithLifecycle()
            ImageCleanTheme(theme, dynamic) { AppRoot(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { vm.open(it) }
        }
    }

    companion object {
        val MEDIA_LOCATION_PERMISSION: String? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Manifest.permission.ACCESS_MEDIA_LOCATION else null
    }
}
