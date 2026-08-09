package com.jasongzy.s3pocket.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.annotation.StringRes
import com.jasongzy.s3pocket.R
import com.jasongzy.s3pocket.data.ProfileRepository
import com.jasongzy.s3pocket.data.model.StorageProfile
import com.jasongzy.s3pocket.data.model.StorageProvider
import com.jasongzy.s3pocket.data.storage.StorageFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileFormState(
  val id: String = "",
  val name: String = "",
  val accountId: String = "",
  val provider: StorageProvider = StorageProvider.AWS_S3,
  val accessKeyId: String = "",
  val secretAccessKey: String = "",
  val managementApiToken: String = "",
  val region: String = "",
  val endpoint: String = "",
  val forcePathStyle: Boolean = false,
  val publicBaseUrl: String = "",
  val isLoading: Boolean = false,
  val isTesting: Boolean = false,
  val testSucceeded: Boolean = false,
  @param:StringRes val errorRes: Int? = null,
  val errorDetail: String? = null,
)

class ProfileEditorViewModel(
  private val profileId: String?,
  private val repository: ProfileRepository,
  private val storageFactory: StorageFactory,
) : ViewModel() {
  private val _state = MutableStateFlow(ProfileFormState())
  val state: StateFlow<ProfileFormState> = _state

  init {
    if (profileId != null) {
      viewModelScope.launch {
        val profile = repository.find(profileId) ?: return@launch
        _state.value =
          ProfileFormState(
            id = profile.id,
            name = profile.name,
            accountId = profile.accountId,
            provider = profile.provider,
            accessKeyId = profile.accessKeyId,
            secretAccessKey = profile.secretAccessKey,
            managementApiToken = profile.managementApiToken,
            region = profile.regionName,
            endpoint = profile.endpointUrl,
            forcePathStyle = profile.forcePathStyle,
            publicBaseUrl = profile.publicBaseUrl,
          )
      }
    }
  }

  fun updateName(value: String) = update { it.copy(name = value) }

  fun updateProvider(value: StorageProvider) = update { it.copy(provider = value) }

  fun updateAccountId(value: String) = update { it.copy(accountId = value.trim()) }

  fun updateAccessKeyId(value: String) = update { it.copy(accessKeyId = value.trim()) }

  fun updateSecretAccessKey(value: String) = update { it.copy(secretAccessKey = value.trim()) }

  fun updateManagementApiToken(value: String) = update { it.copy(managementApiToken = value.trim()) }

  fun updateRegion(value: String) = update { it.copy(region = value.trim()) }

  fun updateEndpoint(value: String) = update { it.copy(endpoint = value.trim()) }

  fun updateForcePathStyle(value: Boolean) = update { it.copy(forcePathStyle = value) }

  fun updatePublicBaseUrl(value: String) = update { it.copy(publicBaseUrl = value.trim()) }

  fun testConnection() {
    val form = state.value
    val errorRes = validateConnection(form)
    if (errorRes != null) {
      _state.update { it.copy(errorRes = errorRes) }
      return
    }
    viewModelScope.launch {
      _state.update { it.copy(isTesting = true, testSucceeded = false, errorRes = null, errorDetail = null) }
      runSuspendCatching { storageFactory.create(form.toProfile()).listBuckets() }
        .onSuccess { _state.update { it.copy(isTesting = false, testSucceeded = true) } }
        .onFailure { error -> _state.update { it.copy(isTesting = false, errorDetail = error.userMessage()) } }
    }
  }

  fun save(onSaved: (String) -> Unit) {
    val form = state.value
    val errorRes = validate(form)
    if (errorRes != null) {
      _state.update { it.copy(errorRes = errorRes) }
      return
    }
    viewModelScope.launch {
      _state.update { it.copy(isLoading = true, errorRes = null, errorDetail = null) }
      runSuspendCatching {
          repository.save(form.toProfile())
        }
        .onSuccess { onSaved(it.id) }
        .onFailure { error -> _state.update { it.copy(isLoading = false, errorDetail = error.userMessage()) } }
    }
  }

  @StringRes
  private fun validate(form: ProfileFormState): Int? = when {
    form.name.isBlank() -> R.string.error_profile_name
    else -> validateConnection(form)
  }

  @StringRes
  private fun validateConnection(form: ProfileFormState): Int? = when {
    form.provider == StorageProvider.CLOUDFLARE_R2 && !form.accountId.matches(Regex("[a-fA-F0-9]{32}")) -> R.string.error_account_id
    form.provider == StorageProvider.AWS_S3 && form.region.isBlank() -> R.string.error_region
    form.accessKeyId.isBlank() -> R.string.error_access_key
    form.secretAccessKey.isBlank() -> R.string.error_secret_key
    else -> null
  }

  private fun update(transform: (ProfileFormState) -> ProfileFormState) {
    _state.update { transform(it).copy(testSucceeded = false, errorRes = null, errorDetail = null) }
  }

  private fun ProfileFormState.toProfile() =
    StorageProfile(
      id = id,
      name = name.trim(),
      provider = provider,
      accountId = accountId,
      accessKeyId = accessKeyId,
      secretAccessKey = secretAccessKey,
      managementApiToken = managementApiToken,
      regionName = region,
      endpointUrl = endpoint,
      forcePathStyle = forcePathStyle,
      publicBaseUrl = publicBaseUrl,
    )
}
