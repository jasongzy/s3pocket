package com.jasongzy.s3pocket.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jasongzy.s3pocket.R
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun parseDownloadUrl(value: String): HttpUrl? = value.trim().toHttpUrlOrNull()?.takeIf {
  it.isHttps && it.username.isEmpty() && it.password.isEmpty()
}

@Composable
internal fun UploadDialog(
  document: SelectedDocument,
  initialKey: String,
  isUploading: Boolean,
  initialMetadata: Map<String, String>,
  onDismiss: () -> Unit,
  onUpload: (String, String, Map<String, String>) -> Unit,
) {
  var key by remember(document.uri) { mutableStateOf(initialKey) }
  var contentType by remember(document.uri) { mutableStateOf(document.contentType) }
  var errorRes by remember { mutableStateOf<Int?>(null) }
  val metadata = remember(document.uri) {
    mutableStateListOf<MetadataEntry>().apply {
      addAll(initialMetadata.map { MetadataEntry(it.key, it.value) })
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.upload_file)) },
    text = {
      LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(stringResource(R.string.file_summary, document.name, formatBytes(document.size)), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
          OutlinedTextField(value = key, onValueChange = { key = it; errorRes = null }, label = { Text(stringResource(R.string.object_key)) }, modifier = Modifier.fillMaxWidth())
        }
        item {
          OutlinedTextField(value = contentType, onValueChange = { contentType = it }, label = { Text(stringResource(R.string.content_type)) }, modifier = Modifier.fillMaxWidth())
        }
        item { Text(stringResource(R.string.custom_metadata), style = MaterialTheme.typography.titleSmall) }
        metadataFields(metadata) { errorRes = null }
        errorRes?.let { item { Text(stringResource(it), color = MaterialTheme.colorScheme.error) } }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val values = metadata.toMetadata()
          when {
            key.isBlank() || key.endsWith('/') -> errorRes = R.string.error_object_key
            values == null -> errorRes = R.string.error_metadata_duplicate
            else -> onUpload(key, contentType, values)
          }
        },
        enabled = !isUploading,
      ) { Text(stringResource(R.string.upload)) }
    },
    dismissButton = { TextButton(onClick = onDismiss, enabled = !isUploading) { Text(stringResource(R.string.cancel)) } },
  )
}

@Composable
internal fun BatchUploadDialog(
  documents: List<SelectedDocument>,
  isUploading: Boolean,
  initialMetadata: Map<String, String>,
  onDismiss: () -> Unit,
  onUpload: (Map<String, String>) -> Unit,
) {
  var errorRes by remember { mutableStateOf<Int?>(null) }
  val metadata = remember(documents) {
    mutableStateListOf<MetadataEntry>().apply {
      addAll(initialMetadata.map { MetadataEntry(it.key, it.value) })
    }
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(pluralStringResource(R.plurals.upload_files, documents.size, documents.size)) },
    text = {
      LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(documents) { document -> Text(document.name) }
        item { Text(stringResource(R.string.custom_metadata), style = MaterialTheme.typography.titleSmall) }
        metadataFields(metadata) { errorRes = null }
        errorRes?.let { item { Text(stringResource(it), color = MaterialTheme.colorScheme.error) } }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val values = metadata.toMetadata()
          if (values == null) {
            errorRes = R.string.error_metadata_duplicate
          } else {
            onUpload(values)
          }
        },
        enabled = !isUploading,
      ) { Text(stringResource(R.string.upload)) }
    },
    dismissButton = { TextButton(onClick = onDismiss, enabled = !isUploading) { Text(stringResource(R.string.cancel)) } },
  )
}
