package com.jasongzy.s3pocket

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jasongzy.s3pocket.data.ThemeMode
import com.jasongzy.s3pocket.data.AppLog
import com.jasongzy.s3pocket.data.LogLevel
import com.jasongzy.s3pocket.data.PaletteMode
import com.jasongzy.s3pocket.theme.S3PocketTheme
import dev.hossain.highlight.ui.HighlightThemeProvider

class MainActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

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
            MainNavigation(predictiveBack)
          }
        }
      }
    }
  }

  private val container: AppContainer
    get() = (application as S3PocketApplication).container
}
