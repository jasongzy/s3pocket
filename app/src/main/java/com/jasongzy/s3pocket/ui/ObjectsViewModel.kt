package com.jasongzy.s3pocket.ui

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jasongzy.s3pocket.R
import java.io.File
import com.jasongzy.s3pocket.data.AppLog
import com.jasongzy.s3pocket.data.downloadFileName
import com.jasongzy.s3pocket.data.useResponse
import com.jasongzy.s3pocket.data.GalleryImageScale
import com.jasongzy.s3pocket.data.GalleryThumbnailSize
import com.jasongzy.s3pocket.data.ObjectSortMode
import com.jasongzy.s3pocket.data.ObjectViewMode
import com.jasongzy.s3pocket.data.ProfileRepository
import com.jasongzy.s3pocket.data.SettingsRepository
import com.jasongzy.s3pocket.data.model.FavoriteItem
import com.jasongzy.s3pocket.data.model.FavoriteKind
import com.jasongzy.s3pocket.data.model.ObjectItem
import com.jasongzy.s3pocket.data.model.SharedUploadSource
import com.jasongzy.s3pocket.data.storage.ObjectMove
import com.jasongzy.s3pocket.data.storage.ObjectPage
import com.jasongzy.s3pocket.data.storage.ObjectStorage
import com.jasongzy.s3pocket.data.storage.StorageOperationCanceledException
import com.jasongzy.s3pocket.data.storage.StorageFactory
import com.jasongzy.s3pocket.data.transfer.TransferManager
import com.jasongzy.s3pocket.data.transfer.TransferItem
import com.jasongzy.s3pocket.data.transfer.TransferStatus
import com.jasongzy.s3pocket.data.transfer.UploadTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
  val deleteProgress: ItemProgress? = null,
  val pendingRecursiveDelete: List<ObjectItem>? = null,
  val isDeleting: Boolean = false,
  val isCancelingDelete: Boolean = false,
  val deleteCanceled: Boolean = false,
  val moveProgress: ItemProgress? = null,
  val isMoving: Boolean = false,
  val isCancelingMove: Boolean = false,
  val moveCanceled: Boolean = false,
  val partialProgress: ItemProgress? = null,
  val uploadProgress: UploadProgress? = null,
  val uploadResult: UploadResult? = null,
  val uploadKeyToOpen: String? = null,
  val nextContinuationToken: String? = null,
  val isLoadingAll: Boolean = false,
) {
  val showListingProgress: Boolean
    get() = isLoadingAll && !isLoading && objects.isNotEmpty()

  val uploadedObjectKey: String?
    get() = uploadKeyToOpen.takeIf { uploadResult?.completed == 1 && uploadResult?.total == 1 }
}

data class ItemProgress(val completed: Int, val total: Int)

// Separate completions must stay distinct even when their counts match.
class UploadResult(val completed: Int, val total: Int)

data class UploadProgress(
  val completed: Int,
  val total: Int,
  val completedBytes: Long = 0,
  val totalBytes: Long = 0,
  val error: String? = null,
  val active: Boolean = false,
)

data class BatchUploadItem(
  val uri: Uri,
  val key: String,
  val contentType: String,
  val contentLength: Long,
  val metadata: Map<String, String>,
  val sharedSource: SharedUploadSource? = null,
)

class ObjectsViewModel(
  private val profileId: String,
  private val bucket: String,
  private val prefix: String,
  private val profiles: ProfileRepository,
  private val settings: SettingsRepository,
  private val storageFactory: StorageFactory,
  private val transferManager: TransferManager,
) : ViewModel() {
  @Volatile private var deleteCancelRequested = false
  @Volatile private var moveCancelRequested = false
  private var listingJob: Job? = null
  private var loadedRevision: Long? = null
  private var completeListingRequired = false
  private val httpClient = OkHttpClient()
  private val stagingSession = transferManager.staging.openSession()
  private var sourceJob: Job? = null
  private val _state = MutableStateFlow(ObjectsUiState())
  private val uploadIds = MutableStateFlow(emptySet<String>())
  private var openAfterUpload = false
  val state: StateFlow<ObjectsUiState> = _state
  val favorites: StateFlow<List<FavoriteItem>> =
    profiles.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  init {
    viewModelScope.launch {
      transferManager.items.combine(uploadIds) { transfers, ids -> transfers.filter { it.id in ids } }.collect { uploads ->
        if (uploads.isEmpty()) return@collect
        val progress = uploadProgress(uploads)
        _state.update {
          it.copy(
            isUploading = progress.active,
            uploadProgress = progress.takeIf { it.active || it.error != null },
          )
        }
        if (!progress.active && progress.error == null) {
          _state.update { it.copy(uploadResult = UploadResult(progress.completed, progress.total)) }
          refresh()
          val finishedIds = uploads.mapTo(mutableSetOf()) { it.id }
          transferManager.dismissFinished(finishedIds)
          uploadIds.update { it - finishedIds }
        }
      }
    }
  }

  fun refreshIfChanged() {
    if (loadedRevision != storageFactory.revision(profileId, bucket)) refresh()
  }

  fun refresh() {
    _state.update { it.copy(partialProgress = null) }
    refreshListing()
  }

  private fun refreshListing(preserveError: Boolean = false) {
    listingJob?.cancel()
    _state.update {
      it.copy(
        isLoading = true,
        nextContinuationToken = null,
        error = it.error.takeIf { preserveError },
        errorRes = it.errorRes.takeIf { preserveError },
      )
    }
    loadPages(refresh = true)
  }

  fun loadMore() {
    if (listingJob?.isActive == true || _state.value.nextContinuationToken == null) return
    loadPages(refresh = false)
  }

  fun setCompleteListingRequired(required: Boolean) {
    completeListingRequired = required
    _state.update { it.copy(isLoadingAll = required && listingJob?.isActive == true) }
    if (required) loadMore()
  }

  private fun loadPages(refresh: Boolean) {
    listingJob = viewModelScope.launch {
      _state.update { it.copy(isLoadingAll = completeListingRequired) }
      runSuspendCatching {
        val revision = storageFactory.revision(profileId, bucket)
        val storage = createStorage()
        var replace = refresh
        loadObjectPages(
          initialToken = _state.value.nextContinuationToken,
          loadAll = { completeListingRequired },
          load = { storage.listObjects(bucket, prefix, it) },
        ) { page ->
          if (replace) loadedRevision = revision
          _state.update {
            it.copy(
              objects = if (replace) page.items else (it.objects + page.items).distinctBy(ObjectItem::key),
              nextContinuationToken = page.nextToken,
              isLoading = false,
            )
          }
          replace = false
        }
      }.onFailure { error ->
        AppLog.error(TAG, error) { "ListObjects failed" }
        _state.update { it.copy(error = it.error ?: error.userMessage()) }
      }
      _state.update { it.copy(isLoading = false, isLoadingAll = false) }
    }
  }

  fun upload(
    uri: Uri, key: String, contentType: String, contentLength: Long, metadata: Map<String, String>, overwrite: Boolean = false,
    sharedSource: SharedUploadSource? = null,
  ) {
    enqueueUploads(listOf(BatchUploadItem(uri, key, contentType, contentLength, metadata, sharedSource)), overwrite)
  }

  fun uploadBatch(items: List<BatchUploadItem>, overwrite: Boolean = false) {
    enqueueUploads(items, overwrite)
  }

  suspend fun hasUploadConflicts(keys: List<String>): Boolean {
    if (keys.size != keys.distinct().size) return true
    val reserved = transferManager.reservedUploadKeys(profileId, bucket)
    if (keys.any { it in reserved }) return true
    val storage = createStorage()
    return storage.existingObjectKeys(bucket, keys).isNotEmpty()
  }

  suspend fun availableUploadKeys(keys: List<String>): List<String> {
    val storage = createStorage()
    val queried = keys.toSet()
    val occupied = storage.existingObjectKeys(bucket, keys) + transferManager.reservedUploadKeys(profileId, bucket)
    return uniqueUploadKeys(keys) { it in occupied || it !in queried && storage.objectExists(bucket, it) }
  }

  private fun enqueueUploads(items: List<BatchUploadItem>, overwrite: Boolean) {
    if (items.isEmpty()) return
    val ids = transferManager.enqueue(items.map {
      UploadTransfer(profileId, bucket, it.uri, it.key, it.contentType, it.contentLength, it.metadata, overwrite, it.sharedSource)
    })
    _state.update {
      it.copy(
        isUploading = true,
        uploadProgress = UploadProgress(0, items.size, active = true),
        uploadResult = null,
        uploadKeyToOpen = items.singleOrNull()?.key.takeIf { openAfterUpload && uploadIds.value.isEmpty() },
      )
    }
    openAfterUpload = false
    uploadIds.update { it + ids }
  }

  fun cancelUploads() {
    cancelUploadNavigation()
    if (transferManager.items.value.any {
        it.id in uploadIds.value && (it.status == TransferStatus.QUEUED || it.status == TransferStatus.RUNNING)
      }) {
      transferManager.cancel(uploadIds.value)
    } else {
      transferManager.dismissFinished(uploadIds.value)
      uploadIds.value = emptySet()
      _state.update { it.copy(isUploading = false, uploadProgress = null) }
      refresh()
    }
  }

  fun retryUploads() {
    transferManager.items.value.filter { it.id in uploadIds.value && it.status == TransferStatus.FAILED }
      .forEach { transferManager.retry(it.id) }
  }

  fun prepareUploadNavigation() {
    cancelUploadNavigation()
    openAfterUpload = true
  }

  fun cancelUploadNavigation() {
    openAfterUpload = false
    _state.update { it.copy(uploadKeyToOpen = null) }
  }

  fun consumeUploadResult(result: UploadResult): String? = _state.consumeUploadResult(result)

  fun createFolder(name: String) {
    if (_state.value.isWorking) return
    viewModelScope.launch {
      _state.update { it.copy(isWorking = true, error = null, errorRes = null) }
      runSuspendCatching {
        require(name.isNotBlank() && '/' !in name) { "Invalid folder name" }
        createStorage().createFolder(bucket, prefix + name)
      }.onSuccess {
        _state.update { it.copy(isWorking = false) }
        refresh()
      }.onFailure { error ->
        AppLog.error(TAG, error) { "CreateFolder failed" }
        val errorRes = error.operationErrorRes()
        _state.update {
          it.copy(isWorking = false, error = if (errorRes == null) error.userMessage() else null, errorRes = errorRes)
        }
      }
    }
  }

  fun downloadSource(url: String) {
    val parsed = parseDownloadUrl(url)
    if (parsed == null) {
      _state.update { it.copy(error = null, errorRes = R.string.error_source_url) }
      return
    }
    sourceJob?.cancel()
    sourceJob = viewModelScope.launch {
      var stagedFile: File? = null
      var delivered = false
      _state.update { it.copy(isDownloadingSource = true, downloadedSource = null, error = null, errorRes = null) }
      try {
        runSuspendCatching {
          withContext(Dispatchers.IO) {
            val request = Request.Builder().url(parsed).get().build()
            httpClient.newCall(request).useResponse { response ->
              if (!response.isSuccessful) error("Download failed (${response.code})")
              val body = response.body
              val name = downloadFileName(response.request.url, response.header("Content-Disposition"))
              val file = stagingSession.create("url-", ".tmp").also { stagedFile = it }
              file.outputStream().use { output -> body.byteStream().use { it.copyTo(output) } }
              DownloadedSource(
                Uri.fromFile(file),
                name,
                body.contentType()?.toString() ?: "application/octet-stream",
                file.length(),
                url.trim(),
              )
            }
          }
        }
        .onSuccess { source ->
          _state.update { it.copy(isDownloadingSource = false, downloadedSource = source) }
          delivered = true
        }
        .onFailure { error -> _state.update { it.copy(isDownloadingSource = false, error = error.userMessage()) } }
      } finally {
        if (!delivered) stagedFile?.let { transferManager.staging.discard(Uri.fromFile(it)) }
      }
    }
  }

  fun requestDelete(items: List<ObjectItem>) {
    if (_state.value.isWorking || items.isEmpty()) return
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
          partialProgress = null,
          deleteProgress = null,
        )
      }
      runSuspendCatching {
          val storage = createStorage()
          val plan = buildDeletePlan(storage, bucket, items) { deleteCancelRequested }
          if (plan.hasNonEmptyFolder) {
            require(settings.settings.first().allowRecursiveFolderDelete) { "Recursive folder deletion is disabled" }
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
    if (_state.value.isWorking) return
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
          partialProgress = null,
          deleteProgress = null,
        )
      }
      runSuspendCatching {
          require(settings.settings.first().allowRecursiveFolderDelete) { "Recursive folder deletion is disabled" }
          val storage = createStorage()
          deletePlan(storage, items, buildDeletePlan(storage, bucket, items) { deleteCancelRequested })
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
    if (items.isEmpty() || _state.value.isWorking) return
    viewModelScope.launch {
      moveCancelRequested = false
      val startedAt = SystemClock.elapsedRealtime()
      AppLog.debug(TAG) { "Move started (${items.size} selected items)" }
      _state.update {
        it.copy(
          isWorking = true,
          isMoving = true,
          isCancelingMove = false,
          moveCanceled = false,
          moveProgress = null,
          error = null,
          errorRes = null,
          partialProgress = null,
        )
      }
      runSuspendCatching {
          val storage = createStorage()
          val plan = buildMovePlan(storage, bucket, items, destinationPrefix) { moveCancelRequested }
          executeMovePlan(storage, plan)
        }
        .onSuccess {
          AppLog.debug(TAG) { "Move succeeded in ${SystemClock.elapsedRealtime() - startedAt} ms" }
          _state.update {
            it.copy(isWorking = false, isMoving = false, isCancelingMove = false, moveProgress = null)
          }
          refresh()
        }
        .onFailure { error -> handleMoveFailure(error, startedAt) }
    }
  }

  fun cancelMove() {
    if (!_state.value.isMoving) return
    moveCancelRequested = true
    _state.update { it.copy(isCancelingMove = true) }
  }

  private suspend fun createStorage(): ObjectStorage {
    val profile = profiles.get(profileId)
    return storageFactory.create(profile)
  }

  private suspend fun executeMovePlan(storage: ObjectStorage, plan: MovePlan) {
    var completed = 0
    val favoriteFiles = profiles.favorites.first()
      .filter { it.profileId == profileId && it.bucket == bucket && it.kind == FavoriteKind.FILE }
      .mapTo(mutableSetOf()) { it.key }
    suspend fun moved(move: ObjectMove) {
      completed++
      _state.update { it.copy(moveProgress = ItemProgress(completed, maxOf(plan.total, completed))) }
      if (move.sourceKey in favoriteFiles) {
        profiles.moveFavorites(profileId, bucket, move.sourceKey, move.targetKey, isPrefix = false)
      }
    }
    _state.update { it.copy(moveProgress = ItemProgress(0, plan.total)) }
    storage.moveObjects(bucket, plan.files, { moveCancelRequested }, ::moved)
    plan.folders.forEach { folder ->
      storage.movePrefix(
        bucket,
        folder.source,
        folder.target,
        { moveCancelRequested },
        ::moved,
      )
      profiles.moveFavorites(profileId, bucket, folder.source, folder.target, isPrefix = true)
    }
  }

  private suspend fun deletePlan(
    storage: ObjectStorage,
    items: List<ObjectItem>,
    plan: DeletePlan,
  ) {
    var completed = 0
    val currentFavorites = profiles.favorites.first().filter { it.profileId == profileId && it.bucket == bucket }
    suspend fun deleted(keys: List<String>) {
      completed += keys.size
      _state.update { it.copy(deleteProgress = ItemProgress(completed, maxOf(plan.total, completed))) }
      val removed = currentFavorites.filter {
        it.key in keys && (it.kind == FavoriteKind.FILE || it.key in plan.fileKeys)
      }
      if (removed.isNotEmpty()) profiles.setFavorites(removed, false)
    }
    _state.update { it.copy(deleteProgress = ItemProgress(0, plan.total)) }
    storage.deleteObjects(bucket, plan.fileKeys, { deleteCancelRequested }, ::deleted)
    plan.recursivePrefixes.forEach { folderPrefix ->
      storage.deletePrefix(bucket, folderPrefix, { deleteCancelRequested }, ::deleted)
      removeFolderFavorites(folderPrefix)
    }
    val deletedCount = maxOf(items.size, completed)
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

  private suspend fun removeFolderFavorites(prefix: String) {
    val deletedFavorites = profiles.favorites.first().filter { favorite ->
      favorite.profileId == profileId && favorite.bucket == bucket &&
        favorite.key.startsWith(prefix)
    }
    profiles.setFavorites(deletedFavorites, false)
  }

  private fun handleDeleteFailure(error: Throwable) {
    if (error is StorageOperationCanceledException) {
      AppLog.debug(TAG) { "Delete canceled" }
      _state.update {
        it.copy(
          isWorking = false,
          isDeleting = false,
          isCancelingDelete = false,
          pendingRecursiveDelete = null,
          deleteProgress = null,
          deleteCanceled = true,
          partialProgress = it.deleteProgress,
        )
      }
      refreshListing()
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
        partialProgress = it.deleteProgress?.takeIf { progress -> progress.completed > 0 },
      )
    }
    refreshListing(preserveError = true)
  }

  private fun handleMoveFailure(error: Throwable, startedAt: Long) {
    if (error is StorageOperationCanceledException) {
      AppLog.debug(TAG) { "Move canceled" }
      _state.update {
        it.copy(
          isWorking = false,
          isMoving = false,
          isCancelingMove = false,
          moveProgress = null,
          moveCanceled = true,
          partialProgress = it.moveProgress,
        )
      }
      refreshListing()
      return
    }
    AppLog.error(TAG, error) { "Move failed in ${SystemClock.elapsedRealtime() - startedAt} ms" }
    val errorRes = error.operationErrorRes()
    _state.update {
      it.copy(
        isWorking = false,
        isMoving = false,
        isCancelingMove = false,
        moveProgress = null,
        error = if (errorRes == null) error.userMessage() else null,
        errorRes = errorRes,
        partialProgress = it.moveProgress?.takeIf { progress -> progress.completed > 0 },
      )
    }
    refreshListing(preserveError = true)
  }

  fun consumeDeletedCount() = _state.update { it.copy(deletedCount = null) }

  fun consumeDeleteCanceled() = _state.update { it.copy(deleteCanceled = false, partialProgress = null) }

  fun consumeMoveCanceled() = _state.update { it.copy(moveCanceled = false, partialProgress = null) }

  fun consumeDownloadedSource() = _state.update { it.copy(downloadedSource = null) }

  fun setSort(mode: ObjectSortMode, ascending: Boolean) {
    viewModelScope.launch { settings.setObjectSort(mode, ascending) }
  }

  fun setViewMode(mode: ObjectViewMode) {
    viewModelScope.launch { settings.setObjectViewMode(mode) }
  }

  fun setGalleryThumbnailSize(size: GalleryThumbnailSize) {
    viewModelScope.launch { settings.setGalleryThumbnailSize(size) }
  }

  fun setGalleryImageScale(scale: GalleryImageScale) {
    viewModelScope.launch { settings.setGalleryImageScale(scale) }
  }

  fun setFavorites(items: List<FavoriteItem>, enabled: Boolean, onSaved: () -> Unit) {
    viewModelScope.launch {
      profiles.setFavorites(items, enabled)
      onSaved()
    }
  }

  override fun onCleared() {
    stagingSession.close()
    transferManager.release(uploadIds.value)
  }

  private companion object {
    const val TAG = "S3Pocket.Objects"
  }
}

internal fun MutableStateFlow<ObjectsUiState>.consumeUploadResult(result: UploadResult): String? {
  val previous = getAndUpdate { current ->
    if (current.uploadResult === result) current.copy(uploadResult = null, uploadKeyToOpen = null)
    else current
  }
  return previous.uploadedObjectKey.takeIf { previous.uploadResult === result }
}

internal fun uploadProgress(items: List<TransferItem>): UploadProgress =
  UploadProgress(
    completed = items.count { it.status == TransferStatus.COMPLETED },
    total = items.size,
    completedBytes = items.sumOf { it.completedBytes },
    totalBytes = items.sumOf { it.totalBytes },
    error = items.firstOrNull { it.status == TransferStatus.FAILED }?.let { it.error ?: "Upload failed" },
    active = items.any { it.status == TransferStatus.QUEUED || it.status == TransferStatus.RUNNING },
  )

internal suspend fun loadObjectPages(
  initialToken: String?,
  loadAll: () -> Boolean,
  load: suspend (String?) -> ObjectPage,
  onPage: (ObjectPage) -> Unit,
) {
  var token = initialToken
  do {
    val page = load(token)
    onPage(page)
    token = page.nextToken
  } while (loadAll() && token != null)
}

private fun Throwable.operationErrorRes(): Int? =
  when {
    message == "Invalid folder name" -> com.jasongzy.s3pocket.R.string.error_invalid_folder_name
    message == "Recursive folder deletion is disabled" -> com.jasongzy.s3pocket.R.string.error_recursive_delete_disabled
    message == "Source and destination are the same" -> com.jasongzy.s3pocket.R.string.error_same_destination
    message == "Source and destination folders overlap" -> com.jasongzy.s3pocket.R.string.error_overlapping_folders
    message?.startsWith("Object already exists:") == true -> com.jasongzy.s3pocket.R.string.error_destination_exists
    else -> null
  }

internal suspend fun uniqueUploadKeys(
  keys: List<String>,
  exists: suspend (String) -> Boolean,
): List<String> {
  val occupied = mutableSetOf<String>()
  return keys.map { key ->
    var candidate = key
    var number = 1
    while (candidate in occupied || exists(candidate)) {
      occupied += candidate
      candidate = numberedKey(key, number++)
    }
    occupied += candidate
    candidate
  }
}

private fun numberedKey(key: String, number: Int): String {
  val slash = key.lastIndexOf('/')
  val prefix = if (slash >= 0) key.substring(0, slash + 1) else ""
  val fileName = key.substring(slash + 1)
  val dot = fileName.lastIndexOf('.').takeIf { it > 0 } ?: fileName.length
  return "$prefix${fileName.substring(0, dot)} ($number)${fileName.substring(dot)}"
}
