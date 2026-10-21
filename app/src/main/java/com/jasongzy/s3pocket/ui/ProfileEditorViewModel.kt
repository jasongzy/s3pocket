package com.jasongzy.s3pocket.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.annotation.StringRes
import com.jasongzy.s3pocket.R
import com.jasongzy.s3pocket.data.ProfileRepository
import com.jasongzy.s3pocket.data.model.StorageProfile
import com.jasongzy.s3pocket.data.model.StorageProvider
import com.jasongzy.s3pocket.data.model.ProfileError
import com.jasongzy.s3pocket.data.storage.StorageFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.util.UUID

data class PublicUrlEntry(
  val bucket: String = "",
  val baseUrl: String = "",
  val id: String = UUID.randomUUID().toString(),
)

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
  val publicUrls: List<PublicUrlEntry> = emptyList(),
  val hasChanges: Boolean = false,
  val isLoading: Boolean = false,
  val isTesting: Boolean = false,
  val testSucceeded: Boolean = false,
  @param:StringRes val errorRes: Int? = null,
  val errorDetail: String? = null,
) {
  internal fun hasChangesFrom(original: ProfileFormState): Boolean =
    toProfile() != original.toProfile() ||
      publicUrls.map { it.bucket to it.baseUrl } != original.publicUrls.map { it.bucket to it.baseUrl }

  fun validationError(checkName: Boolean = true): ProfileError? =
    if (publicUrls.map { it.bucket }.distinct().size != publicUrls.size) ProfileError.DUPLICATE_PUBLIC_BUCKET
    else toProfile().validationError(checkName)

  fun toProfile() = StorageProfile(
    id = id,
    name = name.trim(),
    provider = provider,
    accountId = accountId.takeIf { provider == StorageProvider.CLOUDFLARE_R2 }.orEmpty(),
    accessKeyId = accessKeyId,
    secretAccessKey = secretAccessKey,
    managementApiToken = managementApiToken.takeIf { provider == StorageProvider.CLOUDFLARE_R2 }.orEmpty(),
    regionName = region.takeIf { provider == StorageProvider.AWS_S3 }.orEmpty(),
    endpointUrl = endpoint.takeIf { provider == StorageProvider.AWS_S3 }.orEmpty(),
    forcePathStyle = provider == StorageProvider.AWS_S3 && forcePathStyle,
    publicBaseUrls = publicUrls.associate { it.bucket to it.baseUrl },
  )
}

class ProfileEditorViewModel(
  private val profileId: String?,
  private val repository: ProfileRepository,
  private val storageFactory: StorageFactory,
) : ViewModel() {
  private val _state = MutableStateFlow(ProfileFormState(id = profileId.orEmpty(), isLoading = profileId != null))
  val state: StateFlow<ProfileFormState> = _state
  private var original = _state.value
  private var testJob: Job? = null

  init {
    if (profileId != null) {
      viewModelScope.launch {
        runSuspendCatching { requireNotNull(repository.find(profileId)) { "Profile not found" } }
          .onSuccess { profile ->
            original =
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
                publicUrls = profile.publicBaseUrls.map { (bucket, url) -> PublicUrlEntry(bucket, url) } +
                  listOfNotNull(profile.unassignedPublicUrl.takeIf(String::isNotEmpty)?.let { PublicUrlEntry(baseUrl = it) }),
              )
            _state.value = original
          }
          .onFailure { error -> _state.update { it.copy(isLoading = false, errorDetail = error.userMessage()) } }
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

  fun addPublicUrl() = update { it.copy(publicUrls = it.publicUrls + PublicUrlEntry()) }

  fun removePublicUrl(id: String) = update { it.copy(publicUrls = it.publicUrls.filterNot { entry -> entry.id == id }) }

  fun updatePublicUrl(entry: PublicUrlEntry) = update {
    it.copy(publicUrls = it.publicUrls.map { current ->
      if (current.id == entry.id) entry.copy(bucket = entry.bucket.trim(), baseUrl = entry.baseUrl.trim()) else current
    })
  }

  fun testConnection() {
    if (state.value.isLoading || state.value.isTesting) return
    val form = state.value
    val errorRes = validate(form, checkName = false)
    if (errorRes != null) {
      _state.update { it.copy(errorRes = errorRes) }
      return
    }
    testJob = viewModelScope.launch {
      _state.update { it.copy(isTesting = true, testSucceeded = false, errorRes = null, errorDetail = null) }
      runSuspendCatching { storageFactory.create(form.toProfile()).listBuckets() }
        .onSuccess { _state.update { it.copy(isTesting = false, testSucceeded = true) } }
        .onFailure { error -> _state.update { it.copy(isTesting = false, errorDetail = error.userMessage()) } }
    }
  }

  fun save(onSaved: (String) -> Unit) {
    if (state.value.isLoading || state.value.isTesting) return
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
        .onSuccess {
          original = form
          _state.update { current -> current.copy(hasChanges = false) }
          onSaved(it.id)
        }
        .onFailure { error -> _state.update { it.copy(isLoading = false, errorDetail = error.userMessage()) } }
    }
  }

  @StringRes
  private fun validate(form: ProfileFormState, checkName: Boolean = true): Int? =
    when (form.validationError(checkName)) {
      ProfileError.NAME -> R.string.error_profile_name
      ProfileError.ACCOUNT_ID -> R.string.error_account_id
      ProfileError.REGION -> R.string.error_region
      ProfileError.ACCESS_KEY -> R.string.error_access_key
      ProfileError.SECRET_KEY -> R.string.error_secret_key
      ProfileError.ENDPOINT -> R.string.error_endpoint_url
      ProfileError.PUBLIC_URL -> R.string.error_public_url
      ProfileError.PUBLIC_BUCKET -> R.string.error_public_bucket
      ProfileError.DUPLICATE_PUBLIC_BUCKET -> R.string.error_duplicate_public_bucket
      null -> null
  }

  private fun update(transform: (ProfileFormState) -> ProfileFormState) {
    if (state.value.isLoading) return
    testJob?.cancel()
    _state.update {
      val updated = transform(it)
      updated.copy(
        hasChanges = updated.hasChangesFrom(original),
        isTesting = false,
        testSucceeded = false,
        errorRes = null,
        errorDetail = null,
      )
    }
  }
}
