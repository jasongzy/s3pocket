package com.jasongzy.s3pocket

import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.jasongzy.s3pocket.data.AppLog
import com.jasongzy.s3pocket.data.ThemeMode
import com.jasongzy.s3pocket.theme.S3PocketTheme
import dev.hossain.highlight.ui.HighlightThemeProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
  private var sharedUris by mutableStateOf(emptyList<Uri>())
  private var openSharedImmediately by mutableStateOf(false)
  private var isUploadDestinationOpen = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (savedInstanceState == null) {
      acceptIncomingFiles(intent, openImmediately = false)
    } else {
      sharedUris = BundleCompat.getParcelableArrayList(savedInstanceState, SHARED_URIS_KEY, Uri::class.java).orEmpty()
      openSharedImmediately = savedInstanceState.getBoolean(OPEN_SHARED_IMMEDIATELY_KEY)
    }

    enableEdgeToEdge()
    lifecycleScope.launch {
      val initialSettings = container.settingsRepository.settings.first()
      AppLog.level = initialSettings.logLevel
      setContent {
        val settings = container.settingsRepository.settings.collectAsStateWithLifecycle(initialSettings).value
        SideEffect { AppLog.level = settings.logLevel }
        LaunchedEffect(settings.openWithEnabled) {
          withContext(Dispatchers.IO) {
            val component = ComponentName(this@MainActivity, "$packageName.OpenWithActivity")
            val state = if (settings.openWithEnabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
              else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (packageManager.getComponentEnabledSetting(component) != state) {
              packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
          }
        }
        val darkTheme =
          when (settings.themeMode) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
          }
        S3PocketTheme(darkTheme = darkTheme, paletteMode = settings.paletteMode) {
          HighlightThemeProvider(darkTheme = darkTheme) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
              MainNavigation(
                settings,
                sharedUris,
                openSharedImmediately,
                onUploadDestinationChanged = { isUploadDestinationOpen = it },
              ) { consumedUris ->
                if (sharedUris == consumedUris) {
                  sharedUris = emptyList()
                  openSharedImmediately = false
                  true
                } else false
              }
            }
          }
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    acceptIncomingFiles(intent, openImmediately = isUploadDestinationOpen)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putParcelableArrayList(SHARED_URIS_KEY, ArrayList(sharedUris))
    outState.putBoolean(OPEN_SHARED_IMMEDIATELY_KEY, openSharedImmediately)
    super.onSaveInstanceState(outState)
  }

  private fun acceptIncomingFiles(intent: Intent, openImmediately: Boolean) {
    val streams = when (intent.action) {
      Intent.ACTION_VIEW -> listOfNotNull(intent.data)
      Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
      Intent.ACTION_SEND_MULTIPLE ->
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
      else -> return
    }
    sharedUris = streams.ifEmpty {
      intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
    }.distinct()
    openSharedImmediately = openImmediately && intent.action != Intent.ACTION_VIEW
  }

  private val container: AppContainer
    get() = (application as S3PocketApplication).container

  private companion object {
    const val SHARED_URIS_KEY = "shared_uris"
    const val OPEN_SHARED_IMMEDIATELY_KEY = "open_shared_immediately"
  }
}
