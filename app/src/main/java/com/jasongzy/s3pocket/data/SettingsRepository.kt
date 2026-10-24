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

@Serializable
enum class GalleryThumbnailSize(val columns: Int) {
  LARGE(2),
  MEDIUM(3),
  SMALL(4),
}

@Serializable
enum class GalleryImageScale {
  CROP,
  FIT,
}

class SettingsRepository(private val context: Context) {
  private val json = Json
  val settings: Flow<AppSettingsSnapshot> = context.settingsDataStore.data.map { preferences ->
    AppSettingsSnapshot(
      themeMode = preferences[THEME_MODE].enumOrDefault(ThemeMode.SYSTEM),
      logLevel = preferences[LOG_LEVEL].enumOrDefault(LogLevel.ERRORS),
      paletteMode = preferences[PALETTE_MODE].enumOrDefault(PaletteMode.DYNAMIC),
      predictiveBack = preferences[PREDICTIVE_BACK] ?: false,
      metadataTemplate = preferences[METADATA_TEMPLATE]?.let {
        runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrDefault(emptyMap())
      }.orEmpty(),
      objectSort = preferences[OBJECT_SORT].enumOrDefault(ObjectSortMode.NAME),
      objectSortAscending = preferences[OBJECT_SORT_ASCENDING] ?: true,
      objectViewMode = preferences[OBJECT_VIEW_MODE].enumOrDefault(ObjectViewMode.LIST),
      galleryThumbnailSize = preferences[GALLERY_THUMBNAIL_SIZE].enumOrDefault(GalleryThumbnailSize.MEDIUM),
      galleryImageScale = preferences[GALLERY_IMAGE_SCALE].enumOrDefault(GalleryImageScale.FIT),
      filePreviewEnabled = preferences[FILE_PREVIEW_ENABLED] ?: true,
      openWithEnabled = preferences[OPEN_WITH_ENABLED] ?: false,
      presignedUrlExpiryMinutes = preferences[PRESIGNED_URL_EXPIRY_MINUTES] ?: DEFAULT_PRESIGNED_URL_EXPIRY_MINUTES,
      allowRecursiveFolderDelete = preferences[ALLOW_RECURSIVE_FOLDER_DELETE] ?: false,
      advancedSettingsEnabled = preferences[ADVANCED_SETTINGS_ENABLED] ?: false,
    )
  }
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

  suspend fun setGalleryThumbnailSize(size: GalleryThumbnailSize) {
    context.settingsDataStore.edit { it[GALLERY_THUMBNAIL_SIZE] = size.name }
  }

  suspend fun setGalleryImageScale(scale: GalleryImageScale) {
    context.settingsDataStore.edit { it[GALLERY_IMAGE_SCALE] = scale.name }
  }

  suspend fun setFilePreviewEnabled(enabled: Boolean) {
    context.settingsDataStore.edit { it[FILE_PREVIEW_ENABLED] = enabled }
  }

  suspend fun setOpenWithEnabled(enabled: Boolean) {
    context.settingsDataStore.edit { it[OPEN_WITH_ENABLED] = enabled }
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

  suspend fun snapshot(languageTag: String): AppSettingsSnapshot =
    settings.first().copy(languageTag = languageTag)

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
      it[GALLERY_THUMBNAIL_SIZE] = snapshot.galleryThumbnailSize.name
      it[GALLERY_IMAGE_SCALE] = snapshot.galleryImageScale.name
      it[FILE_PREVIEW_ENABLED] = snapshot.filePreviewEnabled
      it[OPEN_WITH_ENABLED] = snapshot.openWithEnabled
      it[PRESIGNED_URL_EXPIRY_MINUTES] = snapshot.presignedUrlExpiryMinutes
      it[ALLOW_RECURSIVE_FOLDER_DELETE] = snapshot.allowRecursiveFolderDelete
      it[ADVANCED_SETTINGS_ENABLED] = snapshot.advancedSettingsEnabled
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
    val GALLERY_THUMBNAIL_SIZE = stringPreferencesKey("gallery_thumbnail_size")
    val GALLERY_IMAGE_SCALE = stringPreferencesKey("gallery_image_scale")
    val FILE_PREVIEW_ENABLED = booleanPreferencesKey("file_preview_enabled")
    val OPEN_WITH_ENABLED = booleanPreferencesKey("open_with_enabled")
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
  val galleryThumbnailSize: GalleryThumbnailSize = GalleryThumbnailSize.MEDIUM,
  val galleryImageScale: GalleryImageScale = GalleryImageScale.FIT,
  val filePreviewEnabled: Boolean = true,
  val openWithEnabled: Boolean = false,
  val presignedUrlExpiryMinutes: Int = DEFAULT_PRESIGNED_URL_EXPIRY_MINUTES,
  val allowRecursiveFolderDelete: Boolean = false,
  val advancedSettingsEnabled: Boolean = false,
  val languageTag: String = "",
)

internal inline fun <reified T : Enum<T>> String?.enumOrDefault(default: T): T =
  this?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default
