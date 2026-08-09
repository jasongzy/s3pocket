package com.jasongzy.s3pocket.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

@Serializable
enum class ThemeMode {
  SYSTEM,
  LIGHT,
  DARK,
}

@Serializable
enum class PaletteMode {
  DYNAMIC,
  PURPLE,
  BLUE,
  GREEN,
  ORANGE,
}

@Serializable
enum class ObjectSortMode {
  NAME,
  DATE,
  SIZE,
}

@Serializable
enum class ObjectViewMode {
  LIST,
  GALLERY,
}

class SettingsRepository(private val context: Context) {
  private val json = Json
  val themeMode: Flow<ThemeMode> =
    context.settingsDataStore.data.map { preferences ->
      preferences[THEME_MODE]?.let(ThemeMode::valueOf) ?: ThemeMode.SYSTEM
    }
  val logLevel: Flow<LogLevel> =
    context.settingsDataStore.data.map { preferences ->
      preferences[LOG_LEVEL]?.let(LogLevel::valueOf) ?: LogLevel.ERRORS
    }
  val paletteMode: Flow<PaletteMode> =
    context.settingsDataStore.data.map { preferences ->
      preferences[PALETTE_MODE]?.let(PaletteMode::valueOf) ?: PaletteMode.DYNAMIC
    }
  val predictiveBack: Flow<Boolean> =
    context.settingsDataStore.data.map { it[PREDICTIVE_BACK] ?: false }
  val metadataTemplate: Flow<Map<String, String>> =
    context.settingsDataStore.data.map { preferences ->
      preferences[METADATA_TEMPLATE]?.let { json.decodeFromString<Map<String, String>>(it) }.orEmpty()
    }
  val objectSort: Flow<ObjectSortMode> =
    context.settingsDataStore.data.map { preferences ->
      preferences[OBJECT_SORT]?.let(ObjectSortMode::valueOf) ?: ObjectSortMode.NAME
    }
  val objectSortAscending: Flow<Boolean> =
    context.settingsDataStore.data.map { it[OBJECT_SORT_ASCENDING] ?: true }
  val objectViewMode: Flow<ObjectViewMode> =
    context.settingsDataStore.data.map { preferences ->
      preferences[OBJECT_VIEW_MODE]?.let(ObjectViewMode::valueOf) ?: ObjectViewMode.LIST
    }
  val filePreviewEnabled: Flow<Boolean> =
    context.settingsDataStore.data.map { it[FILE_PREVIEW_ENABLED] ?: true }
  val presignedUrlExpiryMinutes: Flow<Int> =
    context.settingsDataStore.data.map { it[PRESIGNED_URL_EXPIRY_MINUTES] ?: DEFAULT_PRESIGNED_URL_EXPIRY_MINUTES }
  val allowRecursiveFolderDelete: Flow<Boolean> =
    context.settingsDataStore.data.map { it[ALLOW_RECURSIVE_FOLDER_DELETE] ?: false }
  val advancedSettingsEnabled: Flow<Boolean> =
    context.settingsDataStore.data.map { it[ADVANCED_SETTINGS_ENABLED] ?: false }

  suspend fun setThemeMode(mode: ThemeMode) {
    context.settingsDataStore.edit { it[THEME_MODE] = mode.name }
  }

  suspend fun setLogLevel(level: LogLevel) {
    context.settingsDataStore.edit { it[LOG_LEVEL] = level.name }
  }

  suspend fun setPaletteMode(mode: PaletteMode) {
    context.settingsDataStore.edit { it[PALETTE_MODE] = mode.name }
  }

  suspend fun setPredictiveBack(enabled: Boolean) {
    context.settingsDataStore.edit { it[PREDICTIVE_BACK] = enabled }
  }

  suspend fun setMetadataTemplate(metadata: Map<String, String>) {
    context.settingsDataStore.edit { it[METADATA_TEMPLATE] = json.encodeToString(metadata) }
  }

  suspend fun setObjectSort(mode: ObjectSortMode, ascending: Boolean) {
    context.settingsDataStore.edit {
      it[OBJECT_SORT] = mode.name
      it[OBJECT_SORT_ASCENDING] = ascending
    }
  }

  suspend fun setObjectViewMode(mode: ObjectViewMode) {
    context.settingsDataStore.edit { it[OBJECT_VIEW_MODE] = mode.name }
  }

  suspend fun setFilePreviewEnabled(enabled: Boolean) {
    context.settingsDataStore.edit { it[FILE_PREVIEW_ENABLED] = enabled }
  }

  suspend fun setPresignedUrlExpiryMinutes(minutes: Int) {
    require(minutes in 1..MAX_PRESIGNED_URL_EXPIRY_MINUTES)
    context.settingsDataStore.edit { it[PRESIGNED_URL_EXPIRY_MINUTES] = minutes }
  }

  suspend fun setAllowRecursiveFolderDelete(enabled: Boolean) {
    context.settingsDataStore.edit { it[ALLOW_RECURSIVE_FOLDER_DELETE] = enabled }
  }

  suspend fun setAdvancedSettingsEnabled(enabled: Boolean) {
    context.settingsDataStore.edit { it[ADVANCED_SETTINGS_ENABLED] = enabled }
  }

  suspend fun snapshot(languageTag: String): AppSettingsSnapshot = AppSettingsSnapshot(
    themeMode = themeMode.first(),
    logLevel = logLevel.first(),
    paletteMode = paletteMode.first(),
    predictiveBack = predictiveBack.first(),
    metadataTemplate = metadataTemplate.first(),
    objectSort = objectSort.first(),
    objectSortAscending = objectSortAscending.first(),
    objectViewMode = objectViewMode.first(),
    filePreviewEnabled = filePreviewEnabled.first(),
    presignedUrlExpiryMinutes = presignedUrlExpiryMinutes.first(),
    allowRecursiveFolderDelete = allowRecursiveFolderDelete.first(),
    languageTag = languageTag,
  )

  suspend fun restore(snapshot: AppSettingsSnapshot) {
    context.settingsDataStore.edit {
      it[THEME_MODE] = snapshot.themeMode.name
      it[LOG_LEVEL] = snapshot.logLevel.name
      it[PALETTE_MODE] = snapshot.paletteMode.name
      it[PREDICTIVE_BACK] = snapshot.predictiveBack
      it[METADATA_TEMPLATE] = json.encodeToString(snapshot.metadataTemplate)
      it[OBJECT_SORT] = snapshot.objectSort.name
      it[OBJECT_SORT_ASCENDING] = snapshot.objectSortAscending
      it[OBJECT_VIEW_MODE] = snapshot.objectViewMode.name
      it[FILE_PREVIEW_ENABLED] = snapshot.filePreviewEnabled
      it[PRESIGNED_URL_EXPIRY_MINUTES] = snapshot.presignedUrlExpiryMinutes.coerceIn(1, MAX_PRESIGNED_URL_EXPIRY_MINUTES)
      it[ALLOW_RECURSIVE_FOLDER_DELETE] = snapshot.allowRecursiveFolderDelete
    }
  }

  private companion object {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val LOG_LEVEL = stringPreferencesKey("log_level")
    val PALETTE_MODE = stringPreferencesKey("palette_mode")
    val PREDICTIVE_BACK = booleanPreferencesKey("predictive_back")
    val METADATA_TEMPLATE = stringPreferencesKey("metadata_template")
    val OBJECT_SORT = stringPreferencesKey("object_sort")
    val OBJECT_SORT_ASCENDING = booleanPreferencesKey("object_sort_ascending")
    val OBJECT_VIEW_MODE = stringPreferencesKey("object_view_mode")
    val FILE_PREVIEW_ENABLED = booleanPreferencesKey("file_preview_enabled")
    val PRESIGNED_URL_EXPIRY_MINUTES = intPreferencesKey("presigned_url_expiry_minutes")
    val ALLOW_RECURSIVE_FOLDER_DELETE = booleanPreferencesKey("allow_recursive_folder_delete")
    val ADVANCED_SETTINGS_ENABLED = booleanPreferencesKey("advanced_settings_enabled")
  }
}

const val DEFAULT_PRESIGNED_URL_EXPIRY_MINUTES = 15
const val MAX_PRESIGNED_URL_EXPIRY_MINUTES = 7 * 24 * 60

@Serializable
data class AppSettingsSnapshot(
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  val logLevel: LogLevel = LogLevel.ERRORS,
  val paletteMode: PaletteMode = PaletteMode.DYNAMIC,
  val predictiveBack: Boolean = false,
  val metadataTemplate: Map<String, String> = emptyMap(),
  val objectSort: ObjectSortMode = ObjectSortMode.NAME,
  val objectSortAscending: Boolean = true,
  val objectViewMode: ObjectViewMode = ObjectViewMode.LIST,
  val filePreviewEnabled: Boolean = true,
  val presignedUrlExpiryMinutes: Int = DEFAULT_PRESIGNED_URL_EXPIRY_MINUTES,
  val allowRecursiveFolderDelete: Boolean = false,
  val languageTag: String = "",
)
