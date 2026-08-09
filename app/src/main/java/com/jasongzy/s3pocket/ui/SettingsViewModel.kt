package com.jasongzy.s3pocket.ui

import android.content.ContentResolver
import android.app.Application
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import coil3.SingletonImageLoader
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jasongzy.s3pocket.data.ProfileRepository
import com.jasongzy.s3pocket.data.SettingsRepository
import com.jasongzy.s3pocket.data.ThemeMode
import com.jasongzy.s3pocket.data.LogLevel
import com.jasongzy.s3pocket.data.PaletteMode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BackupUiState(
  val isWorking: Boolean = false,
  val error: String? = null,
)

sealed interface BackupEvent {
  data object Exported : BackupEvent
  data class Imported(val profileCount: Int) : BackupEvent
}

class SettingsViewModel(
  private val repository: SettingsRepository,
  private val profiles: ProfileRepository,
  application: Application,
) : AndroidViewModel(application) {
  private val context = getApplication<Application>()
  private val contentResolver: ContentResolver = context.contentResolver
  val themeMode: StateFlow<ThemeMode> =
    repository.themeMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)
  val logLevel: StateFlow<LogLevel> =
    repository.logLevel.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogLevel.ERRORS)
  val paletteMode: StateFlow<PaletteMode> =
    repository.paletteMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PaletteMode.DYNAMIC)
  val predictiveBack: StateFlow<Boolean> =
    repository.predictiveBack.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
  val metadataTemplate: StateFlow<Map<String, String>> =
    repository.metadataTemplate.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
  val filePreviewEnabled: StateFlow<Boolean> =
    repository.filePreviewEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
  val presignedUrlExpiryMinutes: StateFlow<Int> =
    repository.presignedUrlExpiryMinutes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 15)
  val allowRecursiveFolderDelete: StateFlow<Boolean> =
    repository.allowRecursiveFolderDelete.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
  val advancedSettingsEnabled: StateFlow<Boolean> =
    repository.advancedSettingsEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
  private val _cacheBytes = MutableStateFlow(0L)
  val cacheBytes: StateFlow<Long> = _cacheBytes
  private val _backup = MutableStateFlow(BackupUiState())
  val backup: StateFlow<BackupUiState> = _backup
  private val _backupEvents = Channel<BackupEvent>(Channel.BUFFERED)
  val backupEvents = _backupEvents.receiveAsFlow()

  init {
    refreshCacheSize()
  }

  fun setThemeMode(mode: ThemeMode) {
    viewModelScope.launch { repository.setThemeMode(mode) }
  }

  fun setLogLevel(level: LogLevel) {
    viewModelScope.launch { repository.setLogLevel(level) }
  }

  fun setPaletteMode(mode: PaletteMode) {
    viewModelScope.launch { repository.setPaletteMode(mode) }
  }

  fun setPredictiveBack(enabled: Boolean) {
    viewModelScope.launch { repository.setPredictiveBack(enabled) }
  }

  fun setMetadataTemplate(metadata: Map<String, String>) {
    viewModelScope.launch { repository.setMetadataTemplate(metadata) }
  }

  fun setFilePreviewEnabled(enabled: Boolean) {
    viewModelScope.launch { repository.setFilePreviewEnabled(enabled) }
  }

  fun setPresignedUrlExpiryMinutes(minutes: Int) {
    viewModelScope.launch { repository.setPresignedUrlExpiryMinutes(minutes) }
  }

  fun setAllowRecursiveFolderDelete(enabled: Boolean) {
    viewModelScope.launch { repository.setAllowRecursiveFolderDelete(enabled) }
  }

  fun setAdvancedSettingsEnabled(enabled: Boolean) {
    viewModelScope.launch { repository.setAdvancedSettingsEnabled(enabled) }
  }

  fun clearCache() {
    viewModelScope.launch(Dispatchers.IO) {
      SingletonImageLoader.get(context).memoryCache?.clear()
      SingletonImageLoader.get(context).diskCache?.clear()
      context.cacheDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
      _cacheBytes.value = cacheSize()
    }
  }

  fun refreshCacheSize() {
    viewModelScope.launch(Dispatchers.IO) { _cacheBytes.value = cacheSize() }
  }

  private fun cacheSize(): Long = context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

  fun exportConfiguration(uri: Uri, includeSettings: Boolean) {
    viewModelScope.launch {
      _backup.value = BackupUiState(isWorking = true)
      runSuspendCatching {
          val languageTag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
          val settings = if (includeSettings) repository.snapshot(languageTag) else null
          val value = profiles.exportBackup(settings)
          withContext(Dispatchers.IO) {
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(value) }
              ?: error("Unable to open backup destination")
          }
        }
        .onSuccess {
          _backup.value = BackupUiState()
          _backupEvents.send(BackupEvent.Exported)
        }
        .onFailure { _backup.value = BackupUiState(error = it.userMessage()) }
    }
  }

  fun importConfiguration(uri: Uri) {
    viewModelScope.launch {
      _backup.value = BackupUiState(isWorking = true)
      runSuspendCatching {
          val value = withContext(Dispatchers.IO) {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
              ?: error("Unable to open backup file")
          }
          val result = profiles.importBackup(value)
          result.settings?.let { settings ->
            repository.restore(settings)
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(settings.languageTag))
          }
          result
        }
        .onSuccess { result ->
          _backup.value = BackupUiState()
          _backupEvents.send(BackupEvent.Imported(result.profileCount))
        }
        .onFailure { _backup.value = BackupUiState(error = it.userMessage()) }
    }
  }
}
