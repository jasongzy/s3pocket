package com.jasongzy.s3pocket.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jasongzy.s3pocket.R

@Composable
internal fun DeleteObjectsDialog(
  fileCount: Int,
  folderCount: Int,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
  recursive: Boolean = false,
  objectKey: String? = null,
  isDeleting: Boolean = false,
  error: String? = null,
) {
  val count = fileCount + folderCount
  val (title, description) = when {
    recursive ->
      stringResource(if (fileCount > 0) R.string.recursive_delete_mixed_title else R.string.recursive_delete_title) to
        stringResource(if (fileCount > 0) R.string.recursive_delete_mixed_description else R.string.recursive_delete_description)
    folderCount == 0 ->
      pluralStringResource(R.plurals.delete_files_title, count, count) to
        pluralStringResource(R.plurals.delete_files_description, count)
    fileCount == 0 ->
      pluralStringResource(R.plurals.delete_folders_title, count, count) to
        pluralStringResource(R.plurals.delete_folders_description, count)
    else ->
      pluralStringResource(R.plurals.delete_items_title, count, count) to
        stringResource(R.string.delete_items_description)
  }
  AlertDialog(
    onDismissRequest = { if (!isDeleting) onDismiss() },
    icon = { AppIcon(R.drawable.ms_warning_24, null, tint = MaterialTheme.colorScheme.error) },
    title = { Text(title, color = if (recursive) MaterialTheme.colorScheme.error else Color.Unspecified) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        objectKey?.let { Text(it) }
        Text(description)
        if (recursive) {
          Text(
            pluralStringResource(R.plurals.recursive_delete_selection, count, count),
            fontWeight = FontWeight.Bold,
          )
        }
        if (isDeleting) LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        error?.let { SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error) } }
      }
    },
    confirmButton = {
      TextButton(
        onClick = onConfirm,
        enabled = !isDeleting,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
      ) { Text(stringResource(if (recursive) R.string.delete_everything else R.string.delete)) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, enabled = !isDeleting) { Text(stringResource(R.string.cancel)) }
    },
  )
}
