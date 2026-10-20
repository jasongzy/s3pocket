package com.jasongzy.s3pocket.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.SubcomposeAsyncImage
import com.jasongzy.s3pocket.R
import com.jasongzy.s3pocket.data.ProfileRepository
import com.jasongzy.s3pocket.data.SettingsRepository
import com.jasongzy.s3pocket.data.model.FavoriteKind
import com.jasongzy.s3pocket.data.model.FavoriteItem
import com.jasongzy.s3pocket.data.model.ObjectDetails
import com.jasongzy.s3pocket.data.storage.StorageFactory
import com.jasongzy.s3pocket.data.transfer.TransferManager
import dev.hossain.highlight.ui.rememberHighlightedCode
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectDetailsScreen(
  profileId: String,
  bucket: String,
  key: String,
  repository: ProfileRepository,
  settingsRepository: SettingsRepository,
  storageFactory: StorageFactory,
  transferManager: TransferManager,
  onBack: () -> Unit,
  onRenamed: (String) -> Unit,
) {
  val context = LocalContext.current
  val viewModel: ObjectDetailsViewModel = viewModel {
    ObjectDetailsViewModel(
      profileId, bucket, key, repository, settingsRepository, storageFactory, transferManager,
    )
  }
  val state by viewModel.state.collectAsStateWithLifecycle()
  val favorites by viewModel.favorites.collectAsStateWithLifecycle()
  var editing by remember { mutableStateOf(false) }
  val fileName = key.substringAfterLast('/').ifBlank { "download" }
  val favorite = remember(profileId, bucket, key) { FavoriteItem(profileId, bucket, key, FavoriteKind.FILE) }
  val isFavorite = favorites.any { it.id == favorite.id }
  val download = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument(state.details?.contentType ?: "application/octet-stream")
  ) { uri -> if (uri != null) viewModel.download(uri) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(fileName, maxLines = 1)
            Text(bucket, style = MaterialTheme.typography.labelSmall)
          }
        },
        navigationIcon = { BackButton(onBack) },
        actions = {
          TooltipIconButton(
            stringResource(if (isFavorite) R.string.remove_from_favorites else R.string.add_to_favorites),
            { viewModel.setFavorite(!isFavorite) { context.showFavoriteToast(!isFavorite) } },
          ) {
            AppIcon(if (isFavorite) R.drawable.ms_star_24 else R.drawable.ms_star_outline_24, null)
          }
          state.accessUrl?.let { url ->
            TooltipIconButton(stringResource(R.string.share_link), { context.shareUrl(url) }) {
              AppIcon(R.drawable.ms_ios_share_24, null)
            }
          }
        },
      )
    }
  ) { padding ->
    when {
      state.isLoading && state.details == null -> LoadingPane(padding)
      state.error != null && state.details == null ->
        MessagePane(
          stringResource(R.string.object_load_error),
          state.error.orEmpty(),
          padding,
          stringResource(R.string.try_again),
          viewModel::load,
        )
      state.details != null ->
        DetailsContent(
          state = state,
          onReload = viewModel::load,
          onRefreshUrl = { viewModel.refreshAccessUrl { context.showSignedUrlRefreshedToast() } },
          onDownload = { download.launch(fileName) },
          onCancelDownload = viewModel::cancelDownload,
          onRetryDownload = viewModel::retryDownload,
          onEdit = { editing = true },
          modifier = Modifier.padding(padding),
        )
    }
  }
  if (editing && state.details != null) {
    ObjectEditDialog(
      details = state.details!!,
      onDismiss = { editing = false },
      onSave = { targetKey, contentType, cacheControl, contentDisposition, metadata ->
        editing = false
        viewModel.update(targetKey, contentType, cacheControl, contentDisposition, metadata, onRenamed)
      },
    )
  }
}

@Composable
private fun DetailsContent(
  state: ObjectDetailsUiState,
  onReload: () -> Unit,
  onRefreshUrl: () -> Unit,
  onDownload: () -> Unit,
  onCancelDownload: () -> Unit,
  onRetryDownload: () -> Unit,
  onEdit: () -> Unit,
  modifier: Modifier = Modifier,
) = with(state) {
  val details = requireNotNull(details)
  Column(
    modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    if (isLoading || isLoadingPreview) {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    (error ?: previewError)?.let { message ->
      SelectionContainer { Text(message, color = MaterialTheme.colorScheme.error) }
      TextButton(onClick = onReload, enabled = !isLoading) { Text(stringResource(R.string.try_again)) }
    }
    if (previewUrl != null) {
      SubcomposeAsyncImage(
        model = rememberObjectImageRequest(previewUrl, details.etag, details.lastModified),
        contentDescription = details.key.substringAfterLast('/'),
        modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(20.dp)),
        contentScale = ContentScale.Fit,
        loading = { LoadingPane(PaddingValues()) },
        error = { error ->
          MessagePane(
            stringResource(R.string.image_preview_error),
            error.result.throwable.userMessage(),
            PaddingValues(),
            stringResource(R.string.try_again),
            painter::restart,
          )
        },
      )
    }
    if (videoPreviewUrl != null) {
      SectionCard(stringResource(R.string.video_preview)) {
        VideoPreview(videoPreviewUrl)
      }
    } else if (previewEnabled && details.isVideo() && details.size > VIDEO_PREVIEW_MAX_BYTES) {
      Text(stringResource(R.string.video_preview_limit), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (textPreview != null) {
      val context = LocalContext.current
      SectionCard(stringResource(R.string.text_preview)) {
        val language = details.syntaxLanguage()
        if (language != null) {
          CodePreview(textPreview, language)
        } else {
          SelectionContainer { Text(textPreview, style = MaterialTheme.typography.bodySmall) }
          TextButton(onClick = { context.copyText(textPreview) }) {
            AppIcon(R.drawable.ms_content_copy_24, null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.copy_text))
          }
        }
      }
    } else if (previewEnabled && details.isText() && details.size > TEXT_PREVIEW_MAX_BYTES) {
      Text(stringResource(R.string.text_preview_limit), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (isDownloading) {
      LinearProgressIndicator(
        progress = { downloadProgress ?: 0f },
        modifier = Modifier.fillMaxWidth(),
      )
      TextButton(onClick = onCancelDownload, modifier = Modifier.align(Alignment.End)) {
        Text(stringResource(R.string.cancel))
      }
    }
    Button(onClick = onDownload, enabled = !isDownloading, modifier = Modifier.fillMaxWidth()) {
      AppIcon(R.drawable.ms_download_24, null, Modifier.size(18.dp))
      Spacer(Modifier.size(8.dp))
      Text(stringResource(if (isDownloading) R.string.downloading else R.string.download))
    }
    if (downloadComplete) Text(stringResource(R.string.saved_successfully), color = MaterialTheme.colorScheme.primary)
    if (downloadError != null) {
      SelectionContainer { Text(downloadError, color = MaterialTheme.colorScheme.error) }
      TextButton(onClick = onRetryDownload, modifier = Modifier.align(Alignment.End)) {
        Text(stringResource(R.string.retry))
      }
    }

    Button(onClick = onEdit, enabled = !isLoading, modifier = Modifier.fillMaxWidth()) {
      AppIcon(R.drawable.ms_edit_24, null, Modifier.size(18.dp))
      Spacer(Modifier.size(8.dp))
      Text(stringResource(R.string.modify))
    }
    accessUrl?.let { url ->
      val context = LocalContext.current
      SectionCard(stringResource(R.string.access_link)) {
        SelectionContainer { Text(url, style = MaterialTheme.typography.bodySmall) }
        if (isTemporaryUrl) {
          SignedUrlExpiry(accessUrlExpiresAt)
        } else {
          Text(
            stringResource(R.string.public_link_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Row {
          TextButton(onClick = { context.copyText(url) }) {
            AppIcon(R.drawable.ms_content_copy_24, null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.copy_link))
          }
          if (isTemporaryUrl) {
            TextButton(onClick = onRefreshUrl, enabled = !isRefreshingUrl) {
              AppIcon(R.drawable.ms_refresh_24, null, Modifier.size(18.dp))
              Spacer(Modifier.size(8.dp))
              Text(stringResource(R.string.refresh_link))
            }
          }
        }
      }
    }

    SectionCard(stringResource(R.string.object_section)) {
      DetailRow(stringResource(R.string.object_key), details.key)
      DetailRow(stringResource(R.string.size), formatBytes(details.size))
      DetailRow(stringResource(R.string.content_type), details.contentType ?: stringResource(R.string.not_available))
      DetailRow(stringResource(R.string.modified), formatInstant(details.lastModified))
      DetailRow(stringResource(R.string.etag), details.etag?.trim('"') ?: stringResource(R.string.not_available))
    }
    if (details.cacheControl != null || details.contentDisposition != null) {
      SectionCard(stringResource(R.string.http_metadata)) {
        details.cacheControl?.let { DetailRow("Cache-Control", it) }
        details.contentDisposition?.let { DetailRow("Content-Disposition", it) }
      }
    }
    SectionCard(stringResource(R.string.custom_metadata)) {
      if (details.metadata.isEmpty()) {
        Text(stringResource(R.string.no_custom_metadata), color = MaterialTheme.colorScheme.onSurfaceVariant)
      } else {
        details.metadata.forEach { (key, value) -> DetailRow(key, value) }
      }
    }
  }
}

@Composable
private fun CodePreview(code: String, language: String) {
  var softWrap by remember { mutableStateOf(false) }
  val highlighted by rememberHighlightedCode(code = code, language = language)
  val context = LocalContext.current
  val horizontalScroll = rememberScrollState()
  Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
    Text(language, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
    TooltipIconButton(
      stringResource(if (softWrap) R.string.disable_word_wrap else R.string.enable_word_wrap),
      { softWrap = !softWrap },
    ) {
      AppIcon(
        R.drawable.ms_wrap_text_24,
        null,
        tint = if (softWrap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    TooltipIconButton(stringResource(R.string.copy_text), { context.copyText(code) }) {
      AppIcon(R.drawable.ms_content_copy_24, null)
    }
  }
  var lineNumbers by remember(code, softWrap) { mutableStateOf(logicalLineNumbers(code)) }
  SelectionContainer {
    Row(
      modifier = if (softWrap) Modifier.fillMaxWidth() else Modifier.horizontalScroll(horizontalScroll),
      verticalAlignment = Alignment.Top,
    ) {
      Text(
        text = lineNumbers,
        modifier = Modifier.width(36.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.End,
        softWrap = false,
      )
      Spacer(Modifier.width(12.dp))
      Text(
        text = highlighted ?: AnnotatedString(code),
        modifier = if (softWrap) Modifier.weight(1f) else Modifier,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        softWrap = softWrap,
        onTextLayout = { layout ->
          val updated = layout.visualLineNumbers(code)
          if (updated != lineNumbers) lineNumbers = updated
        },
      )
    }
  }
}

private fun logicalLineNumbers(code: String): String = (1..code.lines().size).joinToString("\n")

private fun TextLayoutResult.visualLineNumbers(code: String): String {
  var logicalLine = 1
  var previousLine = 0
  var newlineIndex = code.indexOf('\n')
  return buildString {
    repeat(lineCount) { visualLine ->
      val lineStart = getLineStart(visualLine)
      while (newlineIndex >= 0 && newlineIndex < lineStart) {
        logicalLine++
        newlineIndex = code.indexOf('\n', newlineIndex + 1)
      }
      if (visualLine > 0) append('\n')
      if (logicalLine != previousLine) {
        append(logicalLine)
        previousLine = logicalLine
      }
    }
  }
}

@Composable
private fun SignedUrlExpiry(expiresAt: Instant?) {
  if (expiresAt == null) return
  val remainingSeconds by produceState(remainingSeconds(expiresAt), expiresAt) {
    value = remainingSeconds(expiresAt)
    while (value > 0L) {
      delay(1_000)
      value = remainingSeconds(expiresAt)
    }
  }
  Text(
    text = if (remainingSeconds > 0L) {
      stringResource(R.string.access_link_expiry, DateUtils.formatElapsedTime(remainingSeconds))
    } else {
      stringResource(R.string.access_link_expired)
    },
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

private fun remainingSeconds(expiresAt: Instant): Long =
  Duration.between(Instant.now(), expiresAt).seconds.coerceAtLeast(0L)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoPreview(url: String) {
  val context = LocalContext.current
  var fullscreen by remember { mutableStateOf(false) }
  val player = remember(url) {
    ExoPlayer.Builder(context).build().apply {
      setMediaItem(MediaItem.fromUri(url))
      prepare()
    }
  }
  DisposableEffect(player) { onDispose { player.release() } }
  LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
  if (fullscreen) {
    FullscreenVideoPlayer(player, onFullscreenChange = { fullscreen = it })
  } else {
    PlayerSurface(player, false, onFullscreenChange = { fullscreen = it }, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
  }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun PlayerSurface(player: ExoPlayer, fullscreen: Boolean, onFullscreenChange: (Boolean) -> Unit, modifier: Modifier) {
  AndroidView(
    factory = { viewContext ->
      PlayerView(viewContext).apply {
        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        keepScreenOn = true
      }
    },
    update = {
      it.player = player
      it.setFullscreenButtonClickListener(onFullscreenChange)
      it.setFullscreenButtonState(fullscreen)
    },
    onRelease = { it.player = null },
    modifier = modifier,
  )
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun FullscreenVideoPlayer(player: ExoPlayer, onFullscreenChange: (Boolean) -> Unit) {
  val context = LocalContext.current
  val activity = remember(context) { context.findActivity() }
  val previousOrientation = remember(activity) { activity?.requestedOrientation }
  DisposableEffect(activity) {
    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    onDispose {
      if (activity != null && previousOrientation != null) activity.requestedOrientation = previousOrientation
    }
  }
  Dialog(
    onDismissRequest = { onFullscreenChange(false) },
    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
  ) {
    val view = LocalView.current
    DisposableEffect(view) {
      val window = (view.parent as? DialogWindowProvider)?.window
      window?.let {
        WindowCompat.setDecorFitsSystemWindows(it, false)
        WindowCompat.getInsetsController(it, view).hide(WindowInsetsCompat.Type.systemBars())
      }
      onDispose { window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) } }
    }
    PlayerSurface(
      player = player,
      fullscreen = true,
      onFullscreenChange = onFullscreenChange,
      modifier = Modifier.fillMaxSize().background(Color.Black),
    )
  }
}

private tailrec fun Context.findActivity(): Activity? =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
  }

private fun Context.copyText(value: String) {
  val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  clipboard.setPrimaryClip(ClipData.newPlainText("S3 Pocket", value))
}

private fun Context.showSignedUrlRefreshedToast() {
  android.widget.Toast.makeText(this, R.string.signed_url_refreshed, android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
fun formatExpiry(minutes: Int): String = when {
  minutes % (24 * 60) == 0 -> {
    val days = minutes / (24 * 60)
    pluralStringResource(R.plurals.expiry_days, days, days)
  }
  minutes % 60 == 0 -> {
    val hours = minutes / 60
    pluralStringResource(R.plurals.expiry_hours, hours, hours)
  }
  else -> pluralStringResource(R.plurals.expiry_minutes, minutes, minutes)
}

private fun Context.shareUrl(value: String) {
  startActivity(
    Intent.createChooser(
      Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, value)
      },
      getString(R.string.share_link),
    )
  )
}

private fun ObjectDetails.syntaxLanguage(): String? = when (key.substringAfterLast('.', "").lowercase()) {
  "kt", "kts" -> "kotlin"
  "java" -> "java"
  "py" -> "python"
  "js", "mjs", "cjs" -> "javascript"
  "ts", "tsx" -> "typescript"
  "html", "htm" -> "html"
  "xml" -> "xml"
  "css" -> "css"
  "json" -> "json"
  "yaml", "yml" -> "yaml"
  "sh", "bash" -> "bash"
  "sql" -> "sql"
  "md" -> "markdown"
  "c", "h" -> "c"
  "cc", "cpp", "cxx", "hpp" -> "cpp"
  "cs" -> "csharp"
  "go" -> "go"
  "rs" -> "rust"
  "swift" -> "swift"
  "dart" -> "dart"
  "php" -> "php"
  "rb" -> "ruby"
  else -> null
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      content()
    }
  }
}

@Composable
private fun DetailRow(label: String, value: String) {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Text(
      label,
      modifier = Modifier.weight(0.38f),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(value, modifier = Modifier.weight(0.62f), style = MaterialTheme.typography.bodyMedium)
  }
}
