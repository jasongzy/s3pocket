package com.jasongzy.s3pocket.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jasongzy.s3pocket.data.CloudflareMetrics
import com.jasongzy.s3pocket.data.ProfileStoreState
import com.jasongzy.s3pocket.data.model.StorageProfile
import com.jasongzy.s3pocket.data.model.StorageProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileInfoUiState(
  val profile: StorageProfile? = null,
  val metrics: CloudflareMetrics? = null,
  val isProfileLoading: Boolean = true,
  val isMetricsLoading: Boolean = false,
  val metricsError: String? = null,
) {
  val canLoadMetrics: Boolean
    get() = profile?.canLoadCloudflareMetrics() == true
}

class ProfileInfoViewModel(
  private val profileId: String,
  private val profiles: StateFlow<ProfileStoreState?>,
  private val fetchMetrics: suspend (StorageProfile) -> CloudflareMetrics,
) : ViewModel() {
  private val _state = MutableStateFlow(
    profiles.value?.profiles?.firstOrNull { it.id == profileId }.let {
      ProfileInfoUiState(profile = it, isProfileLoading = it == null)
    },
  )
  val state: StateFlow<ProfileInfoUiState> = _state
  private var profileJob: Job? = null
  private var metricsJob: Job? = null

  fun loadProfile() {
    stopLoading()
    profileJob = viewModelScope.launch {
      profiles.filterNotNull().map { snapshot -> snapshot.profiles.firstOrNull { it.id == profileId } }
        .distinctUntilChanged().collect { profile ->
          metricsJob?.cancel()
          _state.update {
            it.copy(
              profile = profile,
              isProfileLoading = false,
              metrics = it.metrics.takeIf { _ -> it.profile == profile },
              isMetricsLoading = false,
              metricsError = null,
            )
          }
          if (profile?.canLoadCloudflareMetrics() == true) refreshMetrics()
        }
    }
  }

  fun stopLoading() {
    profileJob?.cancel()
    metricsJob?.cancel()
    _state.update { it.copy(isMetricsLoading = false) }
  }

  fun refreshMetrics() {
    val profile = _state.value.profile?.takeIf(StorageProfile::canLoadCloudflareMetrics) ?: return
    if (_state.value.isMetricsLoading || _state.value.isProfileLoading) return
    metricsJob = viewModelScope.launch {
      _state.update { it.copy(isMetricsLoading = true, metricsError = null) }
      runSuspendCatching { fetchMetrics(profile) }
        .onSuccess { metrics ->
          _state.update { it.copy(metrics = metrics, isMetricsLoading = false) }
        }
        .onFailure { error ->
          _state.update { it.copy(isMetricsLoading = false, metricsError = error.userMessage()) }
        }
    }
  }
}

private fun StorageProfile.canLoadCloudflareMetrics(): Boolean =
  provider == StorageProvider.CLOUDFLARE_R2 && managementApiToken.isNotBlank()
