package com.jasongzy.s3pocket.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jasongzy.s3pocket.R
import com.jasongzy.s3pocket.data.model.ObjectDetails

@Composable
fun ObjectEditDialog(
  details: ObjectDetails,
  onDismiss: () -> Unit,
  onSave: (String, String?, String?, String?, Map<String, String>) -> Unit,
) {
  var key by remember(details.key) { mutableStateOf(details.key) }
  var contentType by remember(details.key) { mutableStateOf(details.contentType.orEmpty()) }
  var cacheControl by remember(details.key) { mutableStateOf(details.cacheControl.orEmpty()) }
  var contentDisposition by remember(details.key) { mutableStateOf(details.contentDisposition.orEmpty()) }
  var errorRes by remember { mutableStateOf<Int?>(null) }
  val metadata = remember(details.key) {
    mutableStateListOf<MetadataEntry>().apply {
      addAll(details.metadata.map { MetadataEntry(it.key, it.value) })
    }
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.edit_object)) },
    text = {
      LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
          OutlinedTextField(
            value = key,
            onValueChange = { key = it; errorRes = null },
            label = { Text(stringResource(R.string.object_key)) },
            modifier = Modifier.fillMaxWidth(),
          )
        }
        item {
          OutlinedTextField(
            value = cacheControl,
            onValueChange = { cacheControl = it },
            label = { Text("Cache-Control") },
            modifier = Modifier.fillMaxWidth(),
          )
        }
        item {
          OutlinedTextField(
            value = contentDisposition,
            onValueChange = { contentDisposition = it },
            label = { Text("Content-Disposition") },
            modifier = Modifier.fillMaxWidth(),
          )
        }
        item {
          OutlinedTextField(
            value = contentType,
            onValueChange = { contentType = it },
            label = { Text(stringResource(R.string.content_type)) },
            modifier = Modifier.fillMaxWidth(),
          )
        }
        item { Text(stringResource(R.string.custom_metadata), style = MaterialTheme.typography.titleSmall) }
        metadataFields(metadata) { errorRes = null }
        errorRes?.let { item { Text(stringResource(it), color = MaterialTheme.colorScheme.error) } }
      }
    },
    confirmButton = {
      Button(onClick = {
        val values = metadata.toMetadata()
        when {
          key.isBlank() || key.endsWith('/') -> errorRes = R.string.error_object_key
          values == null -> errorRes = R.string.error_metadata_duplicate
          else -> onSave(
            key,
            contentType.ifBlank { null },
            cacheControl.ifBlank { null },
            contentDisposition.ifBlank { null },
            values,
          )
        }
      }) {
        AppIcon(R.drawable.ms_save_24, null)
        Text(stringResource(R.string.save))
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}
