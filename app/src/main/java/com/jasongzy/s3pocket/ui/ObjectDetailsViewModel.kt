package com.jasongzy.s3pocket.ui

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jasongzy.s3pocket.data.ProfileRepository
import com.jasongzy.s3pocket.data.SettingsRepository
import com.jasongzy.s3pocket.data.AppLog
import com.jasongzy.s3pocket.data.model.ObjectDetails
import com.jasongzy.s3pocket.data.model.UpdateObject
import com.jasongzy.s3pocket.data.model.FavoriteItem
import com.jasongzy.s3pocket.data.model.FavoriteKind
import com.jasongzy.s3pocket.data.storage.StorageFactory
import com.jasongzy.s3pocket.data.transfer.DownloadTransfer
import com.jasongzy.s3pocket.data.transfer.TransferManager
import com.jasongzy.s3pocket.data.transfer.TransferStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Instant

data class ObjectDetailsUiState(
  val details: ObjectDetails? = null,
  val previewUrl: String? = null,
  val textPreview: String? = null,
  val videoPreviewUrl: String? = null,
  val accessUrl: String? = null,
  val isTemporaryUrl: Boolean = true,
  val previewEnabled: Boolean = true,
  val presignedUrlExpiryMinutes: Int = 15,
  val accessUrlExpiresAt: Instant? = null,
  val isRefreshingUrl: Boolean = false,
  val isLoading: Boolean = true,
  val isDownloading: Boolean = false,
  val downloadComplete: Boolean = false,
  val downloadProgress: Float? = null,
  val downloadError: String? = null,
  val error: String? = null,
)

class ObjectDetailsViewModel(
  private val profileId: String,
  private val bucket: String,
  private val key: String,
  private val profiles: ProfileRepository,
  private val settings: SettingsRepository,
  private val storageFactory: StorageFactory,
  private val transferManager: TransferManager,
) : ViewModel() {
  private var currentKey = key
  private var downloadId: String? = null
  private val _state = MutableStateFlow(ObjectDetailsUiState())
  val state: StateFlow<ObjectDetailsUiState> = _state
  val favorites: StateFlow<List<FavoriteItem>> =
    profiles.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  init {
    load()
    viewModelScope.launch {
      transferManager.items.collect { transfers ->
        val transfer = transfers.firstOrNull { it.id == downloadId } ?: return@collect
        when (transfer.status) {
          TransferStatus.QUEUED, TransferStatus.RUNNING -> _state.update {
            it.copy(
              isDownloading = true,
              downloadProgress = transfer.completedBytes.toFloat() / transfer.totalBytes.coerceAtLeast(1),
            )
          }
          TransferStatus.COMPLETED -> _state.update {
            it.copy(isDownloading = false, downloadProgress = null, downloadComplete = true)
          }
          TransferStatus.FAILED -> _state.update {
            it.copy(isDownloading = false, downloadProgress = null, downloadError = transfer.error)
          }
          TransferStatus.CANCELED -> _state.update { it.copy(isDownloading = false, downloadProgress = null) }
        }
        if (transfer.status == TransferStatus.COMPLETED || transfer.status == TransferStatus.CANCELED) {
          transferManager.dismissFinished(listOf(transfer.id))
          downloadId = null
        }
      }
    }
  }

  fun load() {
    viewModelScope.launch {
      val startedAt = SystemClock.elapsedRealtime()
      AppLog.debug(TAG) { "LoadObjectDetails started" }
      _state.update { it.copy(isLoading = true, error = null) }
      runSuspendCatching {
          val profile = profiles.get(profileId)
          val storage = storageFactory.create(profile)
          val details = storage.getObjectDetails(bucket, currentKey)
          val appSettings = settings.settings.first()
          val previewEnabled = appSettings.filePreviewEnabled
          val expiryMinutes = appSettings.presignedUrlExpiryMinutes
          val publicUrl = profile.publicObjectUrl(bucket, currentKey)
          val accessUrl = publicUrl ?: storage.createPreviewUrl(bucket, currentKey, expiryMinutes)
          val textPreview = if (previewEnabled && details.isText() && details.size <= TEXT_PREVIEW_MAX_BYTES) {
            withContext(Dispatchers.IO) {
              ByteArrayOutputStream(details.size.toInt()).use { output ->
                storage.download(bucket, currentKey, output)
                output.toString(Charsets.UTF_8.name())
              }
            }
          } else null
          DetailsLoadResult(
            details = details,
            imagePreviewUrl = accessUrl.takeIf { previewEnabled && details.isImage() },
            textPreview = textPreview,
            videoPreviewUrl = accessUrl.takeIf { previewEnabled && details.isVideo() && details.size <= VIDEO_PREVIEW_MAX_BYTES },
            accessUrl = accessUrl,
            isTemporaryUrl = publicUrl == null,
            previewEnabled = previewEnabled,
            presignedUrlExpiryMinutes = expiryMinutes,
            accessUrlExpiresAt = Instant.now().plusSeconds(expiryMinutes * 60L).takeIf { publicUrl == null },
          )
        }
        .onSuccess { result ->
          AppLog.debug(TAG) { "LoadObjectDetails succeeded in ${SystemClock.elapsedRealtime() - startedAt} ms" }
          _state.value = ObjectDetailsUiState(
            details = result.details,
            previewUrl = result.imagePreviewUrl,
            textPreview = result.textPreview,
            videoPreviewUrl = result.videoPreviewUrl,
            accessUrl = result.accessUrl,
            isTemporaryUrl = result.isTemporaryUrl,
            previewEnabled = result.previewEnabled,
            presignedUrlExpiryMinutes = result.presignedUrlExpiryMinutes,
            accessUrlExpiresAt = result.accessUrlExpiresAt,
            isLoading = false,
          )
        }
        .onFailure { error ->
          AppLog.error(TAG, error) { "LoadObjectDetails failed in ${SystemClock.elapsedRealtime() - startedAt} ms" }
          _state.update { it.copy(isLoading = false, error = error.userMessage()) }
        }
    }
  }

  fun refreshAccessUrl(onRefreshed: () -> Unit) {
    if (!_state.value.isTemporaryUrl || _state.value.isRefreshingUrl) return
    viewModelScope.launch {
      _state.update { it.copy(isRefreshingUrl = true, error = null) }
      runSuspendCatching {
          val profile = profiles.get(profileId)
          val expiryMinutes = settings.settings.first().presignedUrlExpiryMinutes
          storageFactory.create(profile).createPreviewUrl(bucket, currentKey, expiryMinutes) to expiryMinutes
        }
        .onSuccess { (url, expiryMinutes) ->
          _state.update { current ->
            current.copy(
              accessUrl = url,
              previewUrl = url.takeIf { current.previewUrl != null },
              videoPreviewUrl = url.takeIf { current.videoPreviewUrl != null },
              presignedUrlExpiryMinutes = expiryMinutes,
              accessUrlExpiresAt = Instant.now().plusSeconds(expiryMinutes * 60L),
              isRefreshingUrl = false,
            )
          }
          onRefreshed()
        }
        .onFailure { error -> _state.update { it.copy(isRefreshingUrl = false, error = error.userMessage()) } }
    }
  }

  fun download(uri: Uri) {
    val details = _state.value.details ?: return
    _state.update {
      it.copy(isDownloading = true, downloadComplete = false, downloadProgress = 0f, downloadError = null, error = null)
    }
    downloadId = transferManager.enqueueDownload(DownloadTransfer(profileId, bucket, uri, currentKey, details.size))
  }

  fun cancelDownload() {
    downloadId?.let(transferManager::cancel)
  }

  fun retryDownload() {
    downloadId?.let(transferManager::retry)
    _state.update { it.copy(isDownloading = true, downloadError = null) }
  }

  override fun onCleared() {
    downloadId?.let { transferManager.dismissFinished(listOf(it)) }
    super.onCleared()
  }

  fun update(
    targetKey: String,
    contentType: String?,
    cacheControl: String?,
    contentDisposition: String?,
    metadata: Map<String, String>,
    onSaved: (String) -> Unit,
  ) {
    if (state.value.details == null) return
    viewModelScope.launch {
      _state.update { it.copy(isLoading = true, error = null) }
      runSuspendCatching {
          val profile = profiles.get(profileId)
          storageFactory.create(profile).updateObject(
            bucket,
            UpdateObject(
              sourceKey = currentKey,
              targetKey = targetKey,
              contentType = contentType,
              cacheControl = cacheControl,
              contentDisposition = contentDisposition,
              metadata = metadata,
            ),
          )
        }
        .onSuccess {
          val oldKey = currentKey
          currentKey = targetKey
          if (oldKey == targetKey) load() else onSaved(targetKey)
        }
        .onFailure { error -> _state.update { it.copy(isLoading = false, error = error.userMessage()) } }
    }
  }

  fun setFavorite(enabled: Boolean, onSaved: () -> Unit) {
    viewModelScope.launch {
      profiles.setFavorite(FavoriteItem(profileId, bucket, currentKey, FavoriteKind.FILE), enabled)
      onSaved()
    }
  }

  private companion object {
    const val TAG = "S3Pocket.ObjectDetails"
  }
}

private data class DetailsLoadResult(
  val details: ObjectDetails,
  val imagePreviewUrl: String?,
  val textPreview: String?,
  val videoPreviewUrl: String?,
  val accessUrl: String,
  val isTemporaryUrl: Boolean,
  val previewEnabled: Boolean,
  val presignedUrlExpiryMinutes: Int,
  val accessUrlExpiresAt: Instant?,
)

fun ObjectDetails.isImage(): Boolean =
  contentType?.startsWith("image/") == true ||
    key.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "avif")

fun ObjectDetails.isText(): Boolean =
  contentType?.startsWith("text/") == true ||
    contentType?.substringBefore(';') in setOf("application/json", "application/xml", "application/javascript", "application/yaml") ||
    key.substringAfterLast('.', "").lowercase() in setOf(
      "txt", "md", "csv", "json", "xml", "yaml", "yml", "html", "htm", "css", "js", "mjs", "cjs", "ts", "tsx",
      "kt", "kts", "java", "py", "sh", "bash", "sql", "c", "h", "cc", "cpp", "cxx", "hpp", "cs", "go", "rs",
      "swift", "dart", "php", "rb", "log",
    )

fun ObjectDetails.isVideo(): Boolean =
  contentType?.startsWith("video/") == true ||
    key.substringAfterLast('.', "").lowercase() in setOf("mp4", "mkv", "mov", "webm", "m4v", "avi")

const val TEXT_PREVIEW_MAX_BYTES = 1_000_000L
const val VIDEO_PREVIEW_MAX_BYTES = 250_000_000L
