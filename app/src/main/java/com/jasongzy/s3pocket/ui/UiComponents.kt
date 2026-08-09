package com.jasongzy.s3pocket.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.DrawableRes
import com.jasongzy.s3pocket.R
import com.jasongzy.s3pocket.data.model.StorageProvider
import coil3.compose.SubcomposeAsyncImage
import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun AppIcon(
  @DrawableRes resourceId: Int,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  tint: Color = LocalContentColor.current,
) {
  Icon(painterResource(resourceId), contentDescription, modifier, tint)
}

@Composable
fun BackButton(onClick: () -> Unit) {
  TooltipIconButton(stringResource(R.string.back), onClick) {
    AppIcon(R.drawable.ms_arrow_back_24, null)
  }
}

@Composable
fun RefreshButton(onClick: () -> Unit) {
  TooltipIconButton(stringResource(R.string.refresh), onClick) {
    AppIcon(R.drawable.ms_refresh_24, null)
  }
}

@Composable
fun ProviderBadge(provider: StorageProvider, modifier: Modifier = Modifier, isAmazonS3: Boolean = false) {
  val isR2 = provider == StorageProvider.CLOUDFLARE_R2
  Surface(
    modifier = modifier.size(48.dp),
    shape = RoundedCornerShape(14.dp),
    color = if (isR2) MaterialTheme.colorScheme.surfaceContainerHighest else Color(0xFF232F3E),
  ) {
    if (isR2) {
      SubcomposeAsyncImage(
        model = CLOUDFLARE_LOGO_URL,
        contentDescription = stringResource(R.string.provider_cloudflare_r2),
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        loading = { ProviderText("R2", Color(0xFFF6821F)) },
        error = { ProviderText("R2", Color(0xFFF6821F)) },
      )
    } else if (isAmazonS3) {
      SubcomposeAsyncImage(
        model = AWS_LOGO_URL,
        contentDescription = stringResource(R.string.amazon_s3),
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        loading = { ProviderText("S3", Color.White) },
        error = { ProviderText("S3", Color.White) },
      )
    } else {
      ProviderText("S3", Color.White)
    }
  }
}

@Composable
private fun ProviderText(text: String, color: Color) {
  Column(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(text, color = color, style = MaterialTheme.typography.titleMedium)
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipIconButton(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  content: @Composable () -> Unit,
) {
  TooltipBox(
    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
    tooltip = { PlainTooltip { Text(label) } },
    state = rememberTooltipState(),
  ) {
    IconButton(onClick = onClick, modifier = modifier, enabled = enabled) { content() }
  }
}

private const val CLOUDFLARE_LOGO_URL = "https://www.cloudflare.com/favicon.ico"
private const val AWS_LOGO_URL = "https://a0.awsstatic.com/libra-css/images/logos/aws_logo_smile_1200x630.png"

@Composable
fun LoadingPane(padding: PaddingValues) {
  Column(
    modifier = Modifier.fillMaxSize().padding(padding),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    CircularProgressIndicator()
  }
}

@Composable
fun MessagePane(
  title: String,
  message: String,
  padding: PaddingValues,
  action: String? = null,
  onAction: () -> Unit = {},
) {
  Column(
    modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(32.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    SelectionContainer {
      Text(message, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (action != null) Button(onClick = onAction, modifier = Modifier.padding(top = 20.dp)) { Text(action) }
  }
}

fun formatBytes(bytes: Long): String {
  if (bytes < 1_000) return "$bytes B"
  val units = listOf("KB", "MB", "GB", "TB")
  var value = bytes / 1_000.0
  var unit = units.first()
  for (next in units.drop(1)) {
    if (value < 1_000) break
    value /= 1_000
    unit = next
  }
  return "${DecimalFormat("0.#").format(value)} $unit"
}

fun formatInstant(instant: Instant?): String =
  instant?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'GMT'xxx")) ?: "—"

fun Context.showFavoriteToast(added: Boolean) {
  Toast.makeText(this, getString(if (added) R.string.added_to_favorites else R.string.removed_from_favorites), Toast.LENGTH_SHORT).show()
}
