package com.jasongzy.s3pocket.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class SafFile(
  val uri: Uri,
  val relativePath: String,
  val contentType: String,
  val size: Long,
)

class SafTreeScanner(
  private val resolver: ContentResolver,
  private val createStagedFile: (String, String) -> File,
) {
  suspend fun scan(treeUri: Uri): List<SafFile> = withContext(Dispatchers.IO) {
    val rootId = DocumentsContract.getTreeDocumentId(treeUri)
    val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
    val rootName = query(rootUri).name
    val files = mutableListOf<SafFile>()
    try {
      scanChildren(treeUri, rootId, rootName, files)
      files
    } catch (error: Throwable) {
      files.filter { it.uri.scheme == "file" }.forEach { file -> file.uri.path?.let(::File)?.delete() }
      throw error
    }
  }

  private suspend fun scanChildren(treeUri: Uri, parentId: String, path: String, files: MutableList<SafFile>) {
    coroutineContext.ensureActive()
    queryChildren(treeUri, parentId).forEach { child ->
      coroutineContext.ensureActive()
      val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id)
      val childPath = "$path/${child.name}"
      if (child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
        scanChildren(treeUri, child.id, childPath, files)
      } else {
        files += if (child.size >= 0) {
          SafFile(documentUri, childPath, child.mimeType, child.size)
        } else {
          stage(documentUri, childPath, child.mimeType)
        }
      }
    }
  }

  private fun queryChildren(treeUri: Uri, parentId: String): List<DocumentNode> {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
    return resolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
      buildList {
        while (cursor.moveToNext()) {
          add(
            DocumentNode(
              id = cursor.getString(0),
              name = cursor.getString(1),
              mimeType = cursor.getString(2),
              size = if (cursor.isNull(3)) -1 else cursor.getLong(3),
            ),
          )
        }
      }
    } ?: error("Unable to read selected folder")
  }

  private fun query(uri: Uri): DocumentInfo = resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
    check(cursor.moveToFirst()) { "Unable to read selected folder" }
    DocumentInfo(cursor.getString(1))
  } ?: error("Unable to read selected folder")

  private suspend fun stage(uri: Uri, path: String, mimeType: String): SafFile {
    val file = createStagedFile("tree-", ".tmp")
    try {
      resolver.openInputStream(uri).use { input ->
        checkNotNull(input) { "Unable to read $path" }
        file.outputStream().use { output ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
          }
        }
        coroutineContext.ensureActive()
      }
    } catch (error: Throwable) {
      file.delete()
      throw error
    }
    return SafFile(Uri.fromFile(file), path, mimeType, file.length())
  }

  private data class DocumentInfo(val name: String)

  private data class DocumentNode(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
  )

  private companion object {
    val PROJECTION = arrayOf(
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      OpenableColumns.DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE,
      OpenableColumns.SIZE,
    )
  }
}
