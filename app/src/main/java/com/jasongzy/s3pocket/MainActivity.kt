package com.jasongzy.s3pocket

import android.content.Intent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jasongzy.s3pocket.data.ThemeMode
import com.jasongzy.s3pocket.data.AppLog
import com.jasongzy.s3pocket.data.LogLevel
import com.jasongzy.s3pocket.data.PaletteMode
import com.jasongzy.s3pocket.theme.S3PocketTheme
import dev.hossain.highlight.ui.HighlightThemeProvider

class MainActivity : AppCompatActivity() {
  private var sharedUris by mutableStateOf(emptyList<Uri>())

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    acceptSharedFile(intent)

    enableEdgeToEdge()
    setContent {
      val themeMode = container.settingsRepository.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM).value
      val logLevel = container.settingsRepository.logLevel.collectAsStateWithLifecycle(LogLevel.ERRORS).value
      val paletteMode = container.settingsRepository.paletteMode.collectAsStateWithLifecycle(PaletteMode.DYNAMIC).value
      val predictiveBack = container.settingsRepository.predictiveBack.collectAsStateWithLifecycle(false).value
      SideEffect { AppLog.level = logLevel }
      val darkTheme =
        when (themeMode) {
          ThemeMode.SYSTEM -> isSystemInDarkTheme()
          ThemeMode.LIGHT -> false
          ThemeMode.DARK -> true
        }
      S3PocketTheme(darkTheme = darkTheme, paletteMode = paletteMode) {
        HighlightThemeProvider(darkTheme = darkTheme) {
          Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            MainNavigation(predictiveBack, sharedUris) { sharedUris = emptyList() }
          }
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    acceptSharedFile(intent)
  }

  private fun acceptSharedFile(intent: Intent) {
    sharedUris = when (intent.action) {
      Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
      Intent.ACTION_SEND_MULTIPLE ->
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
      else -> return
    }
  }

  private val container: AppContainer
    get() = (application as S3PocketApplication).container
}
