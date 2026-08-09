package com.jasongzy.s3pocket.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jasongzy.s3pocket.BuildConfig
import com.jasongzy.s3pocket.R
import com.jasongzy.s3pocket.data.SettingsRepository
import com.jasongzy.s3pocket.data.ProfileRepository
import com.jasongzy.s3pocket.data.ThemeMode
import com.jasongzy.s3pocket.data.LogLevel
import com.jasongzy.s3pocket.data.PaletteMode

private const val GITHUB_URL = "https://github.com/jasongzy/s3pocket"
private val PRESIGNED_EXPIRY_OPTIONS = listOf(5, 15, 60, 360, 1_440, 10_080)
private const val ADVANCED_SETTINGS_TAPS = 7

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  repository: SettingsRepository,
  profiles: ProfileRepository,
  onBack: () -> Unit,
) {
  val context = LocalContext.current
  val resources = LocalResources.current
  val configuration = LocalConfiguration.current
  val viewModel: SettingsViewModel = viewModel {
    SettingsViewModel(repository, profiles, context.applicationContext as android.app.Application)
  }
  val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
  val logLevel by viewModel.logLevel.collectAsStateWithLifecycle()
  val paletteMode by viewModel.paletteMode.collectAsStateWithLifecycle()
  val predictiveBack by viewModel.predictiveBack.collectAsStateWithLifecycle()
  val metadataTemplate by viewModel.metadataTemplate.collectAsStateWithLifecycle()
  val filePreviewEnabled by viewModel.filePreviewEnabled.collectAsStateWithLifecycle()
  val presignedUrlExpiryMinutes by viewModel.presignedUrlExpiryMinutes.collectAsStateWithLifecycle()
  val allowRecursiveFolderDelete by viewModel.allowRecursiveFolderDelete.collectAsStateWithLifecycle()
  val advancedSettingsEnabled by viewModel.advancedSettingsEnabled.collectAsStateWithLifecycle()
  val cacheBytes by viewModel.cacheBytes.collectAsStateWithLifecycle()
  var editMetadataTemplate by remember { mutableStateOf(false) }
  var includeSettings by remember { mutableStateOf(true) }
  var choosePresignedExpiry by remember { mutableStateOf(false) }
  var chooseTheme by remember { mutableStateOf(false) }
  var choosePalette by remember { mutableStateOf(false) }
  var chooseLanguage by remember { mutableStateOf(false) }
  var chooseLogLevel by remember { mutableStateOf(false) }
  var versionTapCount by remember { mutableIntStateOf(0) }
  val backup by viewModel.backup.collectAsStateWithLifecycle()
  val languageTag = remember(configuration) { AppCompatDelegate.getApplicationLocales().toLanguageTags() }
  val uriHandler = LocalUriHandler.current
  val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
    it?.let { uri -> viewModel.exportConfiguration(uri, includeSettings) }
  }
  val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
    it?.let(viewModel::importConfiguration)
  }
  LaunchedEffect(viewModel) {
    viewModel.backupEvents.collect { event ->
      val message = when (event) {
        BackupEvent.Exported -> resources.getString(R.string.export_complete)
        is BackupEvent.Imported -> resources.getQuantityString(
          R.plurals.import_complete,
          event.profileCount,
          event.profileCount,
        )
      }
      Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.settings)) },
        navigationIcon = { BackButton(onBack) },
      )
    }
  ) { padding ->
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
      item {
        SettingsSection(title = stringResource(R.string.appearance)) {
          SettingsValueRow(
            title = stringResource(R.string.theme),
            value = themeMode.label(),
            onClick = { chooseTheme = true },
          )
          SettingsDivider()
          SettingsValueRow(
            title = stringResource(R.string.color_palette),
            value = paletteMode.label(),
            onClick = { choosePalette = true },
          )
          SettingsDivider()
          SettingsValueRow(
            title = stringResource(R.string.language),
            value = when {
              languageTag.startsWith("en") -> stringResource(R.string.language_english)
              languageTag.startsWith("zh") -> stringResource(R.string.language_simplified_chinese)
              else -> stringResource(R.string.language_system)
            },
            onClick = { chooseLanguage = true },
          )
          SettingsDivider()
          SettingsSwitchRow(
            title = stringResource(R.string.predictive_back),
            description = stringResource(R.string.predictive_back_description),
            checked = predictiveBack,
            onCheckedChange = viewModel::setPredictiveBack,
          )
        }
      }
      item {
        SettingsSection(title = stringResource(R.string.files_and_links)) {
          SettingsSwitchRow(
            title = stringResource(R.string.load_file_previews),
            description = stringResource(R.string.load_file_previews_description),
            checked = filePreviewEnabled,
            onCheckedChange = viewModel::setFilePreviewEnabled,
          )
          SettingsDivider()
          SettingsValueRow(
            title = stringResource(R.string.signed_url_validity),
            value = formatExpiry(presignedUrlExpiryMinutes),
            onClick = { choosePresignedExpiry = true },
          )
          SettingsDivider()
          SettingsValueRow(
            title = stringResource(R.string.metadata_template),
            value = pluralStringResource(
              R.plurals.metadata_template_summary,
              metadataTemplate.size,
              metadataTemplate.size,
            ),
            onClick = { editMetadataTemplate = true },
          )
          SettingsDivider()
          SettingsSwitchRow(
            title = stringResource(R.string.allow_recursive_folder_delete),
            description = stringResource(R.string.allow_recursive_folder_delete_description),
            checked = allowRecursiveFolderDelete,
            descriptionColor = MaterialTheme.colorScheme.error,
            onCheckedChange = viewModel::setAllowRecursiveFolderDelete,
          )
        }
      }
      item {
        SettingsSection(title = stringResource(R.string.data_and_security)) {
          Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.cache_used, formatBytes(cacheBytes)), modifier = Modifier.weight(1f))
            Button(onClick = viewModel::clearCache) { Text(stringResource(R.string.clear_cache)) }
          }
          SettingsDivider()
          Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.configuration_backup), style = MaterialTheme.typography.titleMedium)
            Text(
              stringResource(R.string.profile_backup_warning),
              modifier = Modifier.padding(top = 4.dp),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.error,
            )
            Row(
              modifier = Modifier.fillMaxWidth().clickable { includeSettings = !includeSettings }.padding(top = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Checkbox(checked = includeSettings, onCheckedChange = { includeSettings = it })
              Text(stringResource(R.string.include_settings))
            }
            Row(
              modifier = Modifier.padding(top = 12.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Button(onClick = { exportLauncher.launch("s3pocket-backup.json") }, enabled = !backup.isWorking) {
                AppIcon(R.drawable.ms_download_24, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.export_profiles))
              }
              Button(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }, enabled = !backup.isWorking) {
                AppIcon(R.drawable.ms_upload_file_24, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.import_profiles))
              }
            }
            if (backup.error != null) {
              Text(backup.error.orEmpty(), modifier = Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.error)
            }
          }
        }
      }
      if (advancedSettingsEnabled) {
        item {
          SettingsSection(title = stringResource(R.string.advanced)) {
            SettingsValueRow(
              title = stringResource(R.string.logging),
              value = logLevel.label(),
              description = stringResource(R.string.logging_description),
              onClick = { chooseLogLevel = true },
            )
          }
        }
      }
      item {
        SettingsSection(title = stringResource(R.string.about)) {
          Row(
            modifier = Modifier.fillMaxWidth().clickable { uriHandler.openUri(GITHUB_URL) }.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(stringResource(R.string.github), style = MaterialTheme.typography.bodyLarge)
              Text(
                stringResource(R.string.github_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            AppIcon(
              R.drawable.ms_open_in_new_24,
              stringResource(R.string.open),
              tint = MaterialTheme.colorScheme.primary,
            )
          }
          SettingsDivider()
          Row(
            modifier = Modifier.fillMaxWidth().clickable {
              versionTapCount++
              if (versionTapCount == ADVANCED_SETTINGS_TAPS) {
                versionTapCount = 0
                val enabled = !advancedSettingsEnabled
                viewModel.setAdvancedSettingsEnabled(enabled)
                Toast.makeText(
                  context,
                  if (enabled) R.string.advanced_settings_enabled else R.string.advanced_settings_hidden,
                  Toast.LENGTH_SHORT,
                ).show()
              }
            }.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(stringResource(R.string.version), modifier = Modifier.weight(1f))
            Text(
              BuildConfig.VERSION_NAME,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
  if (chooseTheme) {
    ChoiceDialog(
      title = stringResource(R.string.theme),
      values = ThemeMode.entries,
      selected = themeMode,
      label = { it.label() },
      onDismiss = { chooseTheme = false },
      onSelected = { viewModel.setThemeMode(it); chooseTheme = false },
    )
  }
  if (choosePalette) {
    ChoiceDialog(
      title = stringResource(R.string.color_palette),
      values = PaletteMode.entries,
      selected = paletteMode,
      label = { it.label() },
      onDismiss = { choosePalette = false },
      onSelected = { viewModel.setPaletteMode(it); choosePalette = false },
    )
  }
  if (chooseLanguage) {
    val selectedLanguage = when {
      languageTag.startsWith("en") -> "en"
      languageTag.startsWith("zh") -> "zh-CN"
      else -> ""
    }
    ChoiceDialog(
      title = stringResource(R.string.language),
      values = listOf("", "en", "zh-CN"),
      selected = selectedLanguage,
      label = { it.languageLabel() },
      onDismiss = { chooseLanguage = false },
      onSelected = { chooseLanguage = false; setLanguage(it) },
    )
  }
  if (chooseLogLevel) {
    ChoiceDialog(
      title = stringResource(R.string.logging),
      values = LogLevel.entries,
      selected = logLevel,
      label = { it.label() },
      onDismiss = { chooseLogLevel = false },
      onSelected = { viewModel.setLogLevel(it); chooseLogLevel = false },
    )
  }
  if (editMetadataTemplate) {
    MetadataTemplateDialog(
      initial = metadataTemplate,
      onDismiss = { editMetadataTemplate = false },
      onSave = { viewModel.setMetadataTemplate(it); editMetadataTemplate = false },
    )
  }
  if (choosePresignedExpiry) {
    AlertDialog(
      onDismissRequest = { choosePresignedExpiry = false },
      title = { Text(stringResource(R.string.signed_url_validity)) },
      text = {
        Column {
          PRESIGNED_EXPIRY_OPTIONS.forEach { minutes ->
            ChoiceRow(
              label = formatExpiry(minutes),
              selected = presignedUrlExpiryMinutes == minutes,
              onClick = {
                viewModel.setPresignedUrlExpiryMinutes(minutes)
                choosePresignedExpiry = false
              },
            )
          }
        }
      },
      confirmButton = {},
    )
  }
}

@Composable
private fun SettingsValueRow(
  title: String,
  value: String,
  onClick: () -> Unit,
  description: String? = null,
) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(20.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(title)
      Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      description?.let {
        Text(
          it,
          modifier = Modifier.padding(top = 4.dp),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    AppIcon(R.drawable.ms_chevron_right_24, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun SettingsSwitchRow(
  title: String,
  description: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  descriptionColor: Color? = null,
) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(20.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(title)
      Text(
        description,
        style = MaterialTheme.typography.bodySmall,
        color = descriptionColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}

@Composable
private fun SettingsDivider() {
  HorizontalDivider(modifier = Modifier.padding(start = 20.dp))
}

@Composable
private fun <T> ChoiceDialog(
  title: String,
  values: List<T>,
  selected: T,
  label: @Composable (T) -> String,
  onDismiss: () -> Unit,
  onSelected: (T) -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column {
        values.forEach { value ->
          ChoiceRow(label(value), selected == value) { onSelected(value) }
        }
      }
    },
    confirmButton = {},
  )
}

@Composable
private fun ThemeMode.label(): String = stringResource(
  when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
  }
)

@Composable
private fun PaletteMode.label(): String = stringResource(
  when (this) {
    PaletteMode.DYNAMIC -> R.string.palette_dynamic
    PaletteMode.PURPLE -> R.string.palette_purple
    PaletteMode.BLUE -> R.string.palette_blue
    PaletteMode.GREEN -> R.string.palette_green
    PaletteMode.ORANGE -> R.string.palette_orange
  }
)

@Composable
private fun LogLevel.label(): String = stringResource(
  when (this) {
    LogLevel.OFF -> R.string.logging_off
    LogLevel.ERRORS -> R.string.logging_errors
    LogLevel.DEBUG -> R.string.logging_debug
  }
)

@Composable
private fun String.languageLabel(): String = stringResource(
  when (this) {
    "en" -> R.string.language_english
    "zh-CN" -> R.string.language_simplified_chinese
    else -> R.string.language_system
  }
)

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Text(
      title,
      modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.primary,
    )
    content()
    HorizontalDivider()
  }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = onClick)
    Text(label, modifier = Modifier.padding(start = 8.dp, top = 14.dp, end = 16.dp, bottom = 14.dp))
  }
}

private fun setLanguage(languageTag: String) {
  val locales =
    if (languageTag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
    else LocaleListCompat.forLanguageTags(languageTag)
  AppCompatDelegate.setApplicationLocales(locales)
}

private data class TemplateEntry(val key: String, val value: String)

@Composable
private fun MetadataTemplateDialog(initial: Map<String, String>, onDismiss: () -> Unit, onSave: (Map<String, String>) -> Unit) {
  val entries = remember(initial) { mutableStateListOf<TemplateEntry>().apply { addAll(initial.map { TemplateEntry(it.key, it.value) }) } }
  var duplicate by remember { mutableStateOf(false) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.metadata_template)) },
    text = {
      LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(entries.size) { index ->
          val entry = entries[index]
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(entry.key, { entries[index] = entry.copy(key = it); duplicate = false }, label = { Text(stringResource(R.string.metadata_key)) }, modifier = Modifier.weight(1f))
            OutlinedTextField(entry.value, { entries[index] = entry.copy(value = it) }, label = { Text(stringResource(R.string.metadata_value)) }, modifier = Modifier.weight(1f))
            TooltipIconButton(stringResource(R.string.delete), { entries.removeAt(index) }) { AppIcon(R.drawable.ms_delete_24, null) }
          }
        }
        item { TextButton(onClick = { entries += TemplateEntry("", "") }) { Text(stringResource(R.string.add_metadata)) } }
        if (duplicate) item { Text(stringResource(R.string.error_metadata_duplicate), color = MaterialTheme.colorScheme.error) }
      }
    },
    confirmButton = {
      Button(onClick = {
        val valid = entries.filter { it.key.isNotBlank() }
        duplicate = valid.map { it.key.lowercase() }.distinct().size != valid.size
        if (!duplicate) onSave(valid.associate { it.key.trim() to it.value })
      }) { Text(stringResource(R.string.save)) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}
