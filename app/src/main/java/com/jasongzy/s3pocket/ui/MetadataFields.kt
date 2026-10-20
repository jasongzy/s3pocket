package com.jasongzy.s3pocket.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jasongzy.s3pocket.R

internal data class MetadataEntry(val key: String = "", val value: String = "")

internal fun uploadMetadata(template: Map<String, String>, sourceUrl: String?): Map<String, String> =
  if (sourceUrl == null) template else template.filterKeys { !it.equals("source-url", true) } + ("source-url" to sourceUrl)

internal fun LazyListScope.metadataFields(entries: SnapshotStateList<MetadataEntry>, onChange: () -> Unit) {
  items(entries.size) { index ->
    val entry = entries[index]
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(
        value = entry.key,
        onValueChange = { entries[index] = entry.copy(key = it); onChange() },
        label = { Text(stringResource(R.string.metadata_key)) },
        modifier = Modifier.weight(1f),
      )
      OutlinedTextField(
        value = entry.value,
        onValueChange = { entries[index] = entry.copy(value = it); onChange() },
        label = { Text(stringResource(R.string.metadata_value)) },
        modifier = Modifier.weight(1f),
      )
      TooltipIconButton(stringResource(R.string.remove_metadata), { entries.removeAt(index); onChange() }) {
        AppIcon(R.drawable.ms_delete_24, null)
      }
    }
  }
  item {
    TextButton(onClick = { entries += MetadataEntry(); onChange() }) {
      Text(stringResource(R.string.add_metadata))
    }
  }
}

internal fun List<MetadataEntry>.toMetadata(): Map<String, String>? {
  val entries = filter { it.key.isNotBlank() }.map { it.copy(key = it.key.trim()) }
  if (entries.map { it.key.lowercase() }.distinct().size != entries.size) return null
  return entries.associate { it.key to it.value }
}
