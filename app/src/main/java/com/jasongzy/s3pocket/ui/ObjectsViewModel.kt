package com.jasongzy.s3pocket.ui

import android.content.ContentResolver
import android.net.Uri
import android.os.SystemClock
import java.io.File
import java.net.URL
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jasongzy.s3pocket.data.ProfileRepository
import com.jasongzy.s3pocket.data.AppLog
import com.jasongzy.s3pocket.data.ObjectSortMode
import com.jasongzy.s3pocket.data.SettingsRepository
import com.jasongzy.s3pocket.data.model.ObjectItem
import com.jasongzy.s3pocket.data.model.FavoriteItem
import com.jasongzy.s3pocket.data.model.UploadObject
import com.jasongzy.s3pocket.data.storage.StorageFactory
import com.jasongzy.s3pocket.data.storage.UploadSource
import com.jasongzy.s3pocket.data.storage.DeleteCanceledException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient
import okhttp3.Request

data class DownloadedSource(
  val uri: Uri,
  val name: String,
  val contentType: String,
  val size: Long,
  val sourceUrl: String,
)

data class ObjectsUiState(
  val objects: List<ObjectItem> = emptyList(),
  val isLoading: Boolean = true,
  val isUploading: Boolean = false,
  val isWorking: Boolean = false,
  val error: String? = null,
  val errorRes: Int? = null,
  val deletedCount: Int? = null,
  val isDownloadingSource: Boolean = false,
  val downloadedSource: DownloadedSource? = null,
  val deleteProgress: DeleteProgress? = null,
  val pendingRecursiveDelete: List<ObjectItem>? = null,
  val isDeleting: Boolean = false,
  val isCancelingDelete: Boolean = false,
  val deleteCanceled: Boolean = false,
)

data class DeleteProgress(val completed: Int, val total: Int)

class ObjectsViewModel(
  private val profileId: String,
  private val bucket: String,
  private val prefix: String,
  private val profiles: ProfileRepository,
  private val settings: SettingsRepository,
  private val storageFactory: StorageFactory,
  private val contentResolver: ContentResolver,
  private val cacheDir: File,
) : ViewModel() {
  @Volatile private var deleteCancelRequested = false
  private val httpClient = OkHttpClient()
  private val _state = MutableStateFlow(ObjectsUiState())
  val state: StateFlow<ObjectsUiState> = _state
  val favorites: StateFlow<List<FavoriteItem>> =
    profiles.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  init {
    refresh()
  }

  fun refresh() {
    viewModelScope.launch {
      val startedAt = SystemClock.elapsedRealtime()
      AppLog.debug(TAG) { "ListObjects started" }
      _state.update { it.copy(isLoading = true, error = null, errorRes = null) }
      runSuspendCatching {
          val profile = profiles.get(profileId)
          storageFactory.create(profile).listObjects(bucket, prefix)
        }
        .onSuccess { objects ->
          AppLog.debug(TAG) { "ListObjects returned ${objects.size} items in ${SystemClock.elapsedRealtime() - startedAt} ms" }
          _state.update { it.copy(objects = objects, isLoading = false) }
        }
        .onFailure { error ->
          AppLog.error(TAG, error) { "ListObjects failed in ${SystemClock.elapsedRealtime() - startedAt} ms" }
          _state.update { it.copy(isLoading = false, error = error.userMessage()) }
        }
    }
  }

  fun upload(uri: Uri, key: String, contentType: String, contentLength: Long, metadata: Map<String, String>) {
    viewModelScope.launch {
      val startedAt = SystemClock.elapsedRealtime()
      AppLog.debug(TAG) { "Upload started ($contentLength bytes)" }
      _state.update { it.copy(isUploading = true, error = null, errorRes = null) }
      runSuspendCatching {
          val profile = profiles.get(profileId)
          withContext(Dispatchers.IO) {
            storageFactory.create(profile).upload(
              bucket = bucket,
              request =
                UploadObject(
                  key = key,
                  contentType = contentType.ifBlank { "application/octet-stream" },
                  contentLength = contentLength,
                  metadata = metadata,
                ),
              source = UploadSource { contentResolver.openInputStream(uri) ?: error("Unable to open selected file") },
            )
          }
        }
        .onSuccess {
          AppLog.debug(TAG) { "Upload succeeded in ${SystemClock.elapsedRealtime() - startedAt} ms" }
          _state.update { it.copy(isUploading = false) }
          refresh()
        }
        .onFailure { error ->
          AppLog.error(TAG, error) { "Upload failed in ${SystemClock.elapsedRealtime() - startedAt} ms" }
          _state.update { it.copy(isUploading = false, error = error.userMessage()) }
        }
    }
  }

  fun createFolder(name: String) {
    runOperation("CreateFolder") {
      require(name.isNotBlank() && '/' !in name.trim('/')) { "Invalid folder name" }
      it.createFolder(bucket, prefix + name.trim('/'))
    }
  }

  fun downloadSource(url: String) {
    viewModelScope.launch {
      _state.update { it.copy(isDownloadingSource = true, downloadedSource = null, error = null) }
      runSuspendCatching {
          withContext(Dispatchers.IO) {
            val parsed = URL(url.trim())
            require(parsed.protocol == "http" || parsed.protocol == "https") { "Enter an HTTP or HTTPS URL" }
            val request = Request.Builder().url(parsed).get().build()
            httpClient.newCall(request).execute().use { response ->
              if (!response.isSuccessful) error("Download failed (${response.code})")
              val body = response.body
              val name = response.header("Content-Disposition")?.substringAfter("filename=", "")
                ?.trim(' ', '"')?.takeIf { it.isNotBlank() }
                ?: parsed.path.substringAfterLast('/').takeIf { it.isNotBlank() }
                ?: "download"
              val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
              val file = File.createTempFile("url-", "-$safeName", cacheDir)
              file.outputStream().use { output -> body.byteStream().use { it.copyTo(output) } }
              DownloadedSource(
                Uri.fromFile(file),
                safeName,
                body.contentType()?.toString() ?: "application/octet-stream",
                file.length(),
                url.trim(),
              )
            }
          }
        }
        .onSuccess { source -> _state.update { it.copy(isDownloadingSource = false, downloadedSource = source) } }
        .onFailure { error -> _state.update { it.copy(isDownloadingSource = false, error = error.userMessage()) } }
    }
  }

  fun requestDelete(items: List<ObjectItem>) {
    viewModelScope.launch {
      deleteCancelRequested = false
      val startedAt = SystemClock.elapsedRealtime()
      AppLog.debug(TAG) { "PrepareDelete started (${items.size} items)" }
      _state.update {
        it.copy(
          isWorking = true,
          isDeleting = true,
          isCancelingDelete = false,
          deleteCanceled = false,
          error = null,
          errorRes = null,
          deleteProgress = null,
        )
      }
      runSuspendCatching {
          val storage = createStorage()
          val plan = buildDeletePlan(storage, items)
          if (plan.hasNonEmptyFolder) {
            require(settings.allowRecursiveFolderDelete.first()) { "Recursive folder deletion is disabled" }
            _state.update { it.copy(isWorking = false, isDeleting = false, pendingRecursiveDelete = items) }
          } else {
            deletePlan(storage, items, plan)
          }
        }
        .onFailure { error -> handleDeleteFailure(error) }
      AppLog.debug(TAG) { "PrepareDelete finished in ${SystemClock.elapsedRealtime() - startedAt} ms" }
    }
  }

  fun confirmRecursiveDelete() {
    val items = _state.value.pendingRecursiveDelete ?: return
    viewModelScope.launch {
      deleteCancelRequested = false
      val startedAt = SystemClock.elapsedRealtime()
      AppLog.debug(TAG) { "RecursiveDelete started (${items.size} selected items)" }
      _state.update {
        it.copy(
          isWorking = true,
          isDeleting = true,
          isCancelingDelete = false,
          deleteCanceled = false,
          pendingRecursiveDelete = null,
          error = null,
          errorRes = null,
          deleteProgress = null,
        )
      }
      runSuspendCatching {
          require(settings.allowRecursiveFolderDelete.first()) { "Recursive folder deletion is disabled" }
          val storage = createStorage()
          deletePlan(storage, items, buildDeletePlan(storage, items))
        }
        .onFailure { error -> handleDeleteFailure(error) }
      AppLog.debug(TAG) { "RecursiveDelete finished in ${SystemClock.elapsedRealtime() - startedAt} ms" }
    }
  }

  fun cancelRecursiveDelete() = _state.update { it.copy(pendingRecursiveDelete = null) }

  fun cancelDelete() {
    if (!_state.value.isDeleting) return
    deleteCancelRequested = true
    _state.update { it.copy(isCancelingDelete = true) }
  }

  fun move(items: List<ObjectItem>, destinationPrefix: String) {
    runOperation("Move", items.size) { storage ->
      require(items.none { it.isFolder }) { "Only files can be moved" }
      storage.moveObjects(bucket, items.map { it.key }, destinationPrefix)
    }
  }

  private fun runOperation(
    operation: String,
    itemCount: Int = 1,
    block: suspend (com.jasongzy.s3pocket.data.storage.ObjectStorage) -> Unit,
  ) {
    viewModelScope.launch {
      val startedAt = SystemClock.elapsedRealtime()
      AppLog.debug(TAG) { "$operation started ($itemCount items)" }
      _state.update { it.copy(isWorking = true, error = null, errorRes = null) }
      runSuspendCatching {
          val profile = profiles.get(profileId)
          block(storageFactory.create(profile))
        }
        .onSuccess {
          AppLog.debug(TAG) { "$operation succeeded in ${SystemClock.elapsedRealtime() - startedAt} ms" }
          _state.update { it.copy(isWorking = false) }
          refresh()
        }
        .onFailure { error ->
          AppLog.error(TAG, error) { "$operation failed in ${SystemClock.elapsedRealtime() - startedAt} ms" }
          val errorRes = error.operationErrorRes()
          _state.update {
            it.copy(isWorking = false, error = if (errorRes == null) error.userMessage() else null, errorRes = errorRes)
          }
        }
    }
  }

  private suspend fun createStorage(): com.jasongzy.s3pocket.data.storage.ObjectStorage {
    val profile = profiles.get(profileId)
    return storageFactory.create(profile)
  }

  private suspend fun buildDeletePlan(
    storage: com.jasongzy.s3pocket.data.storage.ObjectStorage,
    items: List<ObjectItem>,
  ): DeletePlan {
    val keys = items.filterNot { it.isFolder }.mapTo(mutableListOf()) { it.key }
    var hasNonEmptyFolder = false
    items.filter { it.isFolder }.forEach { folder ->
      val folderPrefix = folder.key.trimEnd('/') + "/"
      val folderKeys = storage.listObjectKeys(bucket, folderPrefix) { deleteCancelRequested }
      hasNonEmptyFolder = hasNonEmptyFolder || folderKeys.any { it != folderPrefix }
      keys += folderKeys
    }
    return DeletePlan(keys.distinct(), hasNonEmptyFolder)
  }

  private suspend fun deletePlan(
    storage: com.jasongzy.s3pocket.data.storage.ObjectStorage,
    items: List<ObjectItem>,
    plan: DeletePlan,
  ) {
    storage.deleteObjects(bucket, plan.keys, { deleteCancelRequested }) { completed, total ->
      _state.update { it.copy(deleteProgress = DeleteProgress(completed, total)) }
    }
    removeDeletedFavorites(items)
    val deletedCount = maxOf(items.size, plan.keys.size)
    _state.update {
      it.copy(
        isWorking = false,
        isDeleting = false,
        isCancelingDelete = false,
        deleteProgress = null,
        deletedCount = deletedCount,
      )
    }
    refresh()
  }

  private suspend fun removeDeletedFavorites(items: List<ObjectItem>) {
    val fileKeys = items.filterNot { it.isFolder }.mapTo(mutableSetOf()) { it.key }
    val folderPrefixes = items.filter { it.isFolder }.map { it.key.trimEnd('/') + "/" }
    val deletedFavorites = profiles.favorites.first().filter { favorite ->
      favorite.profileId == profileId && favorite.bucket == bucket &&
        (favorite.key in fileKeys || folderPrefixes.any { favorite.key.startsWith(it) })
    }
    profiles.setFavorites(deletedFavorites, false)
  }

  private fun handleDeleteFailure(error: Throwable) {
    if (error is DeleteCanceledException) {
      AppLog.debug(TAG) { "Delete canceled" }
      _state.update {
        it.copy(
          isWorking = false,
          isDeleting = false,
          isCancelingDelete = false,
          pendingRecursiveDelete = null,
          deleteProgress = null,
          deleteCanceled = true,
        )
      }
      refresh()
      return
    }
    AppLog.error(TAG, error) { "Delete failed" }
    val errorRes = error.operationErrorRes()
    _state.update {
      it.copy(
        isWorking = false,
        isDeleting = false,
        isCancelingDelete = false,
        pendingRecursiveDelete = null,
        deleteProgress = null,
        error = if (errorRes == null) error.userMessage() else null,
        errorRes = errorRes,
      )
    }
  }

  fun clearError() = _state.update { it.copy(error = null, errorRes = null) }

  fun consumeDeletedCount() = _state.update { it.copy(deletedCount = null) }

  fun consumeDeleteCanceled() = _state.update { it.copy(deleteCanceled = false) }

  fun consumeDownloadedSource() = _state.update { it.copy(downloadedSource = null) }

  fun setSort(repository: SettingsRepository, mode: ObjectSortMode, ascending: Boolean) {
    viewModelScope.launch { repository.setObjectSort(mode, ascending) }
  }

  fun setFavorites(items: List<FavoriteItem>, enabled: Boolean, onSaved: () -> Unit) {
    viewModelScope.launch {
      profiles.setFavorites(items, enabled)
      onSaved()
    }
  }

  private companion object {
    const val TAG = "S3Pocket.Objects"
  }
}

private fun ObjectItem.favorite(profileId: String, bucket: String): FavoriteItem =
  FavoriteItem(
    profileId = profileId,
    bucket = bucket,
    key = key,
    kind = if (isFolder) com.jasongzy.s3pocket.data.model.FavoriteKind.FOLDER
    else com.jasongzy.s3pocket.data.model.FavoriteKind.FILE,
  )

private fun Throwable.operationErrorRes(): Int? =
  when {
    message == "Invalid folder name" -> com.jasongzy.s3pocket.R.string.error_invalid_folder_name
    message == "Recursive folder deletion is disabled" -> com.jasongzy.s3pocket.R.string.error_recursive_delete_disabled
    message == "Only files can be moved" -> com.jasongzy.s3pocket.R.string.error_only_files_move
    message == "Source and destination are the same" -> com.jasongzy.s3pocket.R.string.error_same_destination
    message?.startsWith("Object already exists:") == true -> com.jasongzy.s3pocket.R.string.error_destination_exists
    else -> null
  }

private data class DeletePlan(val keys: List<String>, val hasNonEmptyFolder: Boolean)
