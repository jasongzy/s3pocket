package com.jasongzy.s3pocket.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import com.jasongzy.s3pocket.data.PaletteMode

private val DarkColorScheme = darkColorScheme(primary = Purple80, secondary = PurpleGrey80, tertiary = Pink80)

private val LightColorScheme =
  lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
  )

@Composable
fun S3PocketTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  paletteMode: PaletteMode = PaletteMode.DYNAMIC,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      paletteMode == PaletteMode.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      else -> paletteColorScheme(paletteMode, darkTheme)
    }

  MaterialTheme(colorScheme = colorScheme, content = content)
}

private fun paletteColorScheme(mode: PaletteMode, dark: Boolean) = when (mode) {
  PaletteMode.DYNAMIC, PaletteMode.PURPLE -> if (dark) DarkColorScheme else LightColorScheme
  PaletteMode.BLUE -> if (dark) darkColorScheme(primary = Color(0xFFA8C7FA), secondary = Color(0xFFB7C8E6), tertiary = Color(0xFFD4BBE8))
    else lightColorScheme(primary = Color(0xFF0B57D0), secondary = Color(0xFF4C6080), tertiary = Color(0xFF67507A))
  PaletteMode.GREEN -> if (dark) darkColorScheme(primary = Color(0xFF75DB8A), secondary = Color(0xFFA5CCAD), tertiary = Color(0xFF8FD4CE))
    else lightColorScheme(primary = Color(0xFF146C2E), secondary = Color(0xFF3F6650), tertiary = Color(0xFF286762))
  PaletteMode.ORANGE -> if (dark) darkColorScheme(primary = Color(0xFFFFB77D), secondary = Color(0xFFE6C19F), tertiary = Color(0xFFD8C58D))
    else lightColorScheme(primary = Color(0xFF9A4600), secondary = Color(0xFF765839), tertiary = Color(0xFF685E2F))
}
