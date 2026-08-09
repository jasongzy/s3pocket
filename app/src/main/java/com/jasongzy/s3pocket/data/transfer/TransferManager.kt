package com.jasongzy.s3pocket.data.transfer

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.jasongzy.s3pocket.data.ProfileRepository
import com.jasongzy.s3pocket.data.model.UploadObject
import com.jasongzy.s3pocket.data.storage.StorageFactory
import com.jasongzy.s3pocket.data.storage.TransferCanceledException
import com.jasongzy.s3pocket.data.storage.UploadSource
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class TransferStatus {
  QUEUED,
  RUNNING,
  COMPLETED,
  FAILED,
  CANCELED,
}

data class TransferItem(
  val id: String,
  val completedBytes: Long = 0,
  val totalBytes: Long,
  val status: TransferStatus = TransferStatus.QUEUED,
  val error: String? = null,
)

sealed interface TransferRequest {
  val profileId: String
  val bucket: String
  val key: String
  val contentLength: Long
}

data class UploadTransfer(
  override val profileId: String,
  override val bucket: String,
  val uri: Uri,
  override val key: String,
  val contentType: String,
  override val contentLength: Long,
  val metadata: Map<String, String>,
) : TransferRequest

data class DownloadTransfer(
  override val profileId: String,
  override val bucket: String,
  val uri: Uri,
  override val key: String,
  override val contentLength: Long,
) : TransferRequest

class TransferManager(
  private val application: Application,
  private val profiles: ProfileRepository,
  private val storageFactory: StorageFactory,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val slots = Semaphore(2)
  private val requests = ConcurrentHashMap<String, TransferRequest>()
  private val cancellations = ConcurrentHashMap<String, AtomicBoolean>()
  private val _items = MutableStateFlow<List<TransferItem>>(emptyList())
  val items: StateFlow<List<TransferItem>> = _items

  fun enqueue(requests: List<UploadTransfer>): List<String> = enqueueRequests(requests)

  fun enqueueDownload(request: DownloadTransfer): String = enqueueRequests(listOf(request)).single()

  private fun enqueueRequests(requests: List<TransferRequest>): List<String> {
    if (requests.isEmpty()) return emptyList()
    val ids = requests.map { request ->
      val id = UUID.randomUUID().toString()
      this.requests[id] = request
      _items.update { it + request.toItem(id) }
      launch(id)
      id
    }
    ContextCompat.startForegroundService(application, Intent(application, TransferService::class.java))
    return ids
  }

  fun cancel(id: String) {
    cancellations[id]?.set(true)
    var canceledQueuedTransfer = false
    update(id) { item ->
      if (item.status == TransferStatus.QUEUED) {
        canceledQueuedTransfer = true
        item.copy(status = TransferStatus.CANCELED)
      } else {
        item
      }
    }
    if (canceledQueuedTransfer) scheduleCleanup(id)
  }

  fun cancel(ids: Collection<String>) = ids.forEach(::cancel)

  fun cancelActive() = _items.value.filter { it.status == TransferStatus.QUEUED || it.status == TransferStatus.RUNNING }
    .forEach { cancel(it.id) }

  fun retry(id: String) {
    val item = _items.value.firstOrNull { it.id == id } ?: return
    if (item.status != TransferStatus.FAILED && item.status != TransferStatus.CANCELED) return
    update(id) { it.copy(completedBytes = 0, status = TransferStatus.QUEUED, error = null) }
    launch(id)
    ContextCompat.startForegroundService(application, Intent(application, TransferService::class.java))
  }

  fun dismissFinished(ids: Collection<String>) {
    val idSet = ids.toSet()
    val finishedIds = _items.value.filter { it.id in idSet && it.status.isFinished }.mapTo(mutableSetOf()) { it.id }
    _items.update { items -> items.filterNot { it.id in finishedIds } }
    finishedIds.forEach { id ->
      requests.remove(id)?.let(::deleteCachedUploadSource)
      cancellations.remove(id)
    }
  }

  private fun launch(id: String) {
    val request = requests[id] ?: return
    val canceled = AtomicBoolean(false)
    cancellations[id] = canceled
    scope.launch {
      slots.withPermit {
        if (canceled.get()) return@withPermit
        update(id) { it.copy(status = TransferStatus.RUNNING) }
        try {
          val profile = profiles.get(request.profileId)
          val storage = storageFactory.create(profile)
          when (request) {
            is UploadTransfer -> storage.upload(
              bucket = request.bucket,
              request = UploadObject(
                key = request.key,
                contentType = request.contentType.ifBlank { "application/octet-stream" },
                contentLength = request.contentLength,
                metadata = request.metadata,
              ),
              source = UploadSource {
                application.contentResolver.openInputStream(request.uri) ?: error("Unable to open selected file")
              },
              shouldCancel = canceled::get,
              onProgress = { completed, _ -> update(id) { it.copy(completedBytes = completed) } },
            )
            is DownloadTransfer -> application.contentResolver.openOutputStream(request.uri)?.use { output ->
              storage.download(
                request.bucket,
                request.key,
                output,
                canceled::get,
              ) { completed, _ -> update(id) { it.copy(completedBytes = completed) } }
            } ?: error("Unable to open destination")
          }
          update(id) { it.copy(completedBytes = it.totalBytes, status = TransferStatus.COMPLETED) }
          scheduleCleanup(id)
        } catch (error: Throwable) {
          val isCanceled = canceled.get() || error is TransferCanceledException || error is CancellationException
          update(id) {
            it.copy(status = if (isCanceled) TransferStatus.CANCELED else TransferStatus.FAILED, error = error.message)
          }
          if (isCanceled) scheduleCleanup(id)
        }
      }
    }
  }

  private fun scheduleCleanup(id: String) {
    scope.launch {
      delay(FINISHED_RETENTION_MILLIS)
      dismissFinished(listOf(id))
    }
  }

  private fun deleteCachedUploadSource(request: TransferRequest) {
    if (request !is UploadTransfer || request.uri.scheme != "file") return
    val source = request.uri.path?.let(::File)?.canonicalFile ?: return
    val cache = application.cacheDir.canonicalFile
    if (source.path.startsWith(cache.path + File.separator)) source.delete()
  }

  private fun update(id: String, block: (TransferItem) -> TransferItem) {
    _items.update { items -> items.map { if (it.id == id) block(it) else it } }
  }

  private companion object {
    const val FINISHED_RETENTION_MILLIS = 30_000L
  }
}

val TransferStatus.isFinished: Boolean
  get() = this == TransferStatus.COMPLETED || this == TransferStatus.FAILED || this == TransferStatus.CANCELED

private fun TransferRequest.toItem(id: String) =
  TransferItem(
    id = id,
    totalBytes = contentLength,
  )
