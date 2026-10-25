package com.jasongzy.s3pocket.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.jasongzy.s3pocket.R
import com.jasongzy.s3pocket.data.model.MetadataCondition
import com.jasongzy.s3pocket.data.model.MetadataFilter
import com.jasongzy.s3pocket.data.model.MetadataOperator
import com.jasongzy.s3pocket.data.storage.metadataKeySuggestions
import com.jasongzy.s3pocket.data.storage.mergeMetadataKeys
import kotlinx.serialization.json.Json

private val FilterSaver = Saver<MetadataFilter, String>(
  save = { Json.encodeToString(it) },
  restore = { Json.decodeFromString<MetadataFilter>(it) },
)

@Composable
internal fun MetadataFilterDialog(
  initial: MetadataFilter?,
  metadataKeys: List<String>,
  onDismiss: () -> Unit,
  onApply: (MetadataFilter) -> Unit,
) {
  var filter by rememberSaveable(stateSaver = FilterSaver) { mutableStateOf(initial ?: MetadataFilter()) }
  var candidateKeys by rememberSaveable { mutableStateOf(mergeMetadataKeys(emptyList(), metadataKeys)) }
  LaunchedEffect(metadataKeys) {
    candidateKeys = mergeMetadataKeys(candidateKeys, metadataKeys)
  }
  val focusManager = LocalFocusManager.current
  fun update(index: Int, condition: MetadataCondition) {
    filter = filter.copy(conditions = filter.conditions.mapIndexed { i, current -> if (i == index) condition else current })
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = Modifier.imePadding().padding(horizontal = 20.dp).widthIn(max = 560.dp).fillMaxWidth(),
    properties = DialogProperties(usePlatformDefaultWidth = false),
    title = { Text(stringResource(R.string.metadata_filter)) },
    text = {
      LazyColumn(modifier = Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
          Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
              stringResource(R.string.metadata_filter_scope),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
              SegmentedButton(
                selected = filter.matchAll,
                onClick = { filter = filter.copy(matchAll = true) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
              ) { Text(stringResource(R.string.match_all)) }
              SegmentedButton(
                selected = !filter.matchAll,
                onClick = { filter = filter.copy(matchAll = false) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
              ) { Text(stringResource(R.string.match_any)) }
            }
          }
        }
        items(filter.conditions.size) { index ->
          val condition = filter.conditions[index]
          var expanded by remember { mutableStateOf(false) }
          OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
          ) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                  TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(
                      stringResource(condition.operator.label),
                      modifier = Modifier.weight(1f, fill = false),
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                    AppIcon(R.drawable.ms_arrow_drop_down_24, null, Modifier.size(20.dp))
                  }
                  DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    MetadataOperator.entries.forEach { operator ->
                      DropdownMenuItem(
                        text = { Text(stringResource(operator.label)) },
                        leadingIcon = { RadioButton(selected = condition.operator == operator, onClick = null) },
                        onClick = { update(index, condition.copy(operator = operator)); expanded = false },
                      )
                    }
                  }
                }
                TooltipIconButton(stringResource(R.string.remove_condition), {
                  focusManager.clearFocus()
                  filter = filter.copy(conditions = filter.conditions.filterIndexed { i, _ -> i != index })
                }) { AppIcon(R.drawable.ms_close_24, null, Modifier.size(20.dp)) }
              }
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                MetadataKeyField(
                  value = condition.key,
                  onValueChange = { update(index, condition.copy(key = it)) },
                  keys = candidateKeys,
                  imeAction = if (condition.operator.needsValue) ImeAction.Next else ImeAction.Done,
                  modifier = Modifier.weight(1f),
                )
                if (condition.operator.needsValue) {
                  OutlinedTextField(
                    value = condition.value,
                    onValueChange = { update(index, condition.copy(value = it)) },
                    label = { Text(stringResource(R.string.metadata_value)) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    maxLines = 3,
                    modifier = Modifier.weight(1f),
                  )
                }
              }
            }
          }
        }
        item {
          OutlinedButton(
            onClick = { filter = filter.copy(conditions = filter.conditions + MetadataCondition()) },
            modifier = Modifier.fillMaxWidth(),
          ) {
            AppIcon(R.drawable.ms_add_24, null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.add_condition))
          }
        }
        if (filter.conditions.any { it.operator.needsValue }) {
          item {
            Row(
              modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small).toggleable(
                value = filter.ignoreCase, role = Role.Checkbox, onValueChange = { filter = filter.copy(ignoreCase = it) },
              ).padding(vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Checkbox(checked = filter.ignoreCase, onCheckedChange = null)
              Text(stringResource(R.string.ignore_value_case), style = MaterialTheme.typography.bodyMedium)
            }
          }
        }
      }
    },
    confirmButton = {
      Button(onClick = { onApply(filter) }, enabled = filter.isValid) { Text(stringResource(R.string.apply_filter)) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetadataKeyField(
  value: String,
  onValueChange: (String) -> Unit,
  keys: List<String>,
  imeAction: ImeAction,
  modifier: Modifier = Modifier,
) {
  var focused by remember { mutableStateOf(false) }
  var showSuggestions by remember { mutableStateOf(false) }
  val suggestions = remember(keys, value) { metadataKeySuggestions(keys, value) }
  val expanded = focused && showSuggestions && suggestions.isNotEmpty()
  ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { showSuggestions = it }, modifier = modifier) {
    OutlinedTextField(
      value = value,
      onValueChange = { onValueChange(it); showSuggestions = true },
      label = { Text(stringResource(R.string.metadata_key)) },
      singleLine = true,
      textStyle = MaterialTheme.typography.bodyMedium,
      keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = imeAction),
      modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable).onFocusChanged {
        focused = it.isFocused
        showSuggestions = it.isFocused
      },
    )
    ExposedDropdownMenu(
      expanded = expanded,
      onDismissRequest = { showSuggestions = false },
      modifier = Modifier.heightIn(max = 200.dp),
      shape = MenuDefaults.standaloneGroupShape,
      containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
      suggestions.forEach { key ->
        DropdownMenuItem(
          text = { Text(key, style = MaterialTheme.typography.bodyMedium) },
          onClick = { onValueChange(key); showSuggestions = false },
        )
      }
    }
  }
}

private val MetadataOperator.label: Int
  get() = when (this) {
    MetadataOperator.EXISTS -> R.string.field_exists
    MetadataOperator.MISSING -> R.string.field_missing
    MetadataOperator.EMPTY -> R.string.value_empty
    MetadataOperator.NOT_EMPTY -> R.string.value_not_empty
    MetadataOperator.EQUALS -> R.string.value_equals
    MetadataOperator.NOT_EQUALS -> R.string.value_not_equals
    MetadataOperator.CONTAINS -> R.string.value_contains
    MetadataOperator.NOT_CONTAINS -> R.string.value_not_contains
  }
