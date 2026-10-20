package com.jasongzy.s3pocket.data.transfer

import android.net.Uri
import java.io.File

class TransferStaging(cacheDir: File) {
  val directory = File(cacheDir, DIRECTORY_NAME).apply {
    deleteRecursively()
    mkdirs()
  }
  private val owners = mutableMapOf<File, Any>()
  private val transfers = Any()

  fun openSession() = Session()

  @Synchronized
  fun claim(uri: Uri) {
    stagedFile(uri)?.let { owners[it] = transfers }
  }

  @Synchronized
  fun discard(uri: Uri) {
    stagedFile(uri)?.let { file ->
      if (file.delete() || !file.exists()) owners.remove(file)
    }
  }

  fun discard(uris: Iterable<Uri>) = uris.forEach(::discard)

  @Synchronized
  fun clearUnused() {
    directory.listFiles().orEmpty().filter { it.canonicalFile !in owners && it.isDescendantOf(directory) }
      .forEach { it.delete() }
  }

  private fun stagedFile(uri: Uri): File? =
    uri.takeIf { it.scheme == "file" }?.path?.let(::File)?.canonicalFile?.takeIf { it.isDescendantOf(directory) }

  inner class Session : AutoCloseable {
    private var closed = false

    fun create(prefix: String, suffix: String): File = synchronized(this@TransferStaging) {
      check(!closed) { "Transfer preparation was canceled" }
      File.createTempFile(prefix, suffix, directory).canonicalFile.also { owners[it] = this }
    }

    override fun close() = synchronized(this@TransferStaging) {
      closed = true
      val files = owners.filterValues { it === this }.keys.toList()
      files.forEach { file ->
        owners.remove(file)
        file.delete()
      }
    }
  }

  companion object {
    const val DIRECTORY_NAME = "transfer-staging"
  }
}

internal fun File.isDescendantOf(root: File): Boolean =
  canonicalPath.startsWith(root.canonicalPath + File.separator)
