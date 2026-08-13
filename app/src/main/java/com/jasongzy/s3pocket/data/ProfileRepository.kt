package com.jasongzy.s3pocket.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jasongzy.s3pocket.data.model.FavoriteItem
import com.jasongzy.s3pocket.data.model.StorageProfile
import com.jasongzy.s3pocket.data.model.StorageProvider
import com.jasongzy.s3pocket.data.security.CredentialCipher
import com.jasongzy.s3pocket.data.security.CredentialCodec
import com.jasongzy.s3pocket.data.security.ProfileBackupCipher
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.profileDataStore by preferencesDataStore(name = "profiles")

data class ProfileStoreState(
  val profiles: List<StorageProfile>,
  val unavailableCredentialIds: Set<String>,
)

class ProfileRepository(
  private val context: Context,
  private val cipher: CredentialCodec = CredentialCipher(),
  private val backupCipher: ProfileBackupCipher = ProfileBackupCipher(),
) {
  private val json = Json { ignoreUnknownKeys = true }
  val state: Flow<ProfileStoreState> = context.profileDataStore.data.map { preferences ->
    decodeStoredProfiles(preferences[PROFILES].orEmpty(), cipher, json)
  }

  val profiles: Flow<List<StorageProfile>> = state.map { it.profiles }
  val favorites: Flow<List<FavoriteItem>> = context.profileDataStore.data.map { preferences ->
    preferences[FAVORITES]?.let(::decodeFavorites).orEmpty()
  }

  suspend fun find(id: String): StorageProfile? = state.first().profiles.firstOrNull { it.id == id }

  suspend fun get(id: String): StorageProfile {
    val snapshot = state.first()
    require(id !in snapshot.unavailableCredentialIds) { "Credentials are unavailable. Re-enter them to continue." }
    return requireNotNull(snapshot.profiles.firstOrNull { it.id == id }) { "Profile not found" }
  }

  suspend fun save(profile: StorageProfile): StorageProfile {
    val saved = profile.copy(id = profile.id.ifBlank { UUID.randomUUID().toString() })
    context.profileDataStore.edit { preferences ->
      val profiles = decodeStored(preferences[PROFILES].orEmpty()).toMutableList()
      val stored = saved.toStored(cipher)
      val index = profiles.indexOfFirst { it.id == saved.id }
      if (index < 0) profiles += stored else profiles[index] = stored
      preferences[PROFILES] = encodeStored(profiles)
    }
    return saved
  }

  suspend fun reorder(profileIds: List<String>) {
    context.profileDataStore.edit { preferences ->
      val current = decodeStored(preferences[PROFILES].orEmpty())
      require(profileIds.size == current.size && profileIds.toSet() == current.map { it.id }.toSet()) {
        "Invalid profile order"
      }
      val profilesById = current.associateBy { it.id }
      preferences[PROFILES] = encodeStored(profileIds.map(profilesById::getValue))
    }
  }

  suspend fun delete(id: String) = deleteAll(setOf(id))

  suspend fun deleteAll(ids: Set<String>) {
    if (ids.isEmpty()) return
    context.profileDataStore.edit { preferences ->
      preferences[PROFILES] = encodeStored(decodeStored(preferences[PROFILES].orEmpty()).filterNot { it.id in ids })
      preferences[FAVORITES] = encodeFavorites(
        preferences[FAVORITES]?.let(::decodeFavorites).orEmpty().filterNot { it.profileId in ids }
      )
    }
  }

  suspend fun setFavorite(favorite: FavoriteItem, enabled: Boolean) {
    context.profileDataStore.edit { preferences ->
      val current = preferences[FAVORITES]?.let(::decodeFavorites).orEmpty()
      val updated = current.filterNot { it.id == favorite.id }.let { if (enabled) it + favorite else it }
      preferences[FAVORITES] = encodeFavorites(updated)
    }
  }

  suspend fun setFavorites(items: List<FavoriteItem>, enabled: Boolean) {
    val ids = items.mapTo(mutableSetOf()) { it.id }
    context.profileDataStore.edit { preferences ->
      val current = preferences[FAVORITES]?.let(::decodeFavorites).orEmpty()
      val retained = current.filterNot { it.id in ids }
      preferences[FAVORITES] = encodeFavorites(if (enabled) retained + items else retained)
    }
  }

  suspend fun replaceFavorites(items: List<FavoriteItem>) {
    context.profileDataStore.edit { preferences ->
      preferences[FAVORITES] = encodeFavorites(items.distinctBy { it.id })
    }
  }

  suspend fun reorderFavorites(favoriteIds: List<String>) {
    context.profileDataStore.edit { preferences ->
      val current = preferences[FAVORITES]?.let(::decodeFavorites).orEmpty()
      require(favoriteIds.size == current.size && favoriteIds.toSet() == current.map { it.id }.toSet()) {
        "Invalid favorite order"
      }
      val favoritesById = current.associateBy { it.id }
      preferences[FAVORITES] = encodeFavorites(favoriteIds.map(favoritesById::getValue))
    }
  }

  suspend fun exportBackup(settings: AppSettingsSnapshot?): String {
    val snapshot = state.first()
    require(snapshot.unavailableCredentialIds.isEmpty()) { "Re-enter unavailable credentials before exporting a backup." }
    return backupCipher.encrypt(json.encodeToString(AppBackup(snapshot.profiles, settings, favorites.first())))
  }

  suspend fun importBackup(value: String): ImportBackupResult {
    val decrypted = backupCipher.decrypt(value)
    val backup = runCatching { json.decodeFromString<AppBackup>(decrypted) }
      .getOrElse { AppBackup(profiles = decodeProfiles(decrypted)) }
    val imported = backup.profiles
    require(imported.all { it.id.isNotBlank() && it.name.isNotBlank() }) { "Invalid profile backup" }
    context.profileDataStore.edit { preferences ->
      val current = decodeStored(preferences[PROFILES].orEmpty()).toMutableList()
      imported.forEach { profile ->
        val stored = profile.toStored(cipher)
        val index = current.indexOfFirst { it.id == profile.id }
        if (index < 0) current += stored else current[index] = stored
      }
      preferences[PROFILES] = encodeStored(current)
      val profileIds = current.mapTo(mutableSetOf()) { it.id }
      val currentFavorites = preferences[FAVORITES]?.let(::decodeFavorites).orEmpty()
      val importedFavorites = backup.favorites.filter { it.profileId in profileIds }
      val importedIds = importedFavorites.mapTo(mutableSetOf()) { it.id }
      preferences[FAVORITES] = encodeFavorites(currentFavorites.filterNot { it.id in importedIds } + importedFavorites)
    }
    return ImportBackupResult(imported.size, backup.settings)
  }

  private fun decodeStored(value: String): List<StoredProfile> =
    if (value.isBlank()) emptyList() else json.decodeFromString(ListSerializer(StoredProfile.serializer()), value)

  private fun encodeStored(profiles: List<StoredProfile>): String =
    json.encodeToString(ListSerializer(StoredProfile.serializer()), profiles)

  private fun decodeProfiles(value: String): List<StorageProfile> =
    json.decodeFromString(ListSerializer(StorageProfile.serializer()), value)

  private fun encodeFavorites(favorites: List<FavoriteItem>): String =
    json.encodeToString(ListSerializer(FavoriteItem.serializer()), favorites)

  private fun decodeFavorites(value: String): List<FavoriteItem> =
    json.decodeFromString(ListSerializer(FavoriteItem.serializer()), value)

  private companion object {
    val PROFILES = stringPreferencesKey("profiles_v2")
    val FAVORITES = stringPreferencesKey("favorites_v2")
  }
}

internal fun decodeStoredProfiles(value: String, cipher: CredentialCodec, json: Json): ProfileStoreState {
  if (value.isBlank()) return ProfileStoreState(emptyList(), emptySet())
  val stored = json.decodeFromString(ListSerializer(StoredProfile.serializer()), value)
  val unavailable = mutableSetOf<String>()
  val profiles = stored.map { profile ->
    val secretAccessKey = profile.secretAccessKey.decryptOrNull(cipher)
    val managementApiToken = if (profile.managementApiToken.isEmpty()) "" else profile.managementApiToken.decryptOrNull(cipher)
    if (secretAccessKey == null || profile.managementApiToken.isNotEmpty() && managementApiToken == null) {
      unavailable += profile.id
    }
    profile.toProfile(secretAccessKey.orEmpty(), managementApiToken.orEmpty())
  }
  return ProfileStoreState(profiles, unavailable)
}

private fun String.decryptOrNull(cipher: CredentialCodec): String? = try {
  cipher.decrypt(this)
} catch (_: Exception) {
  null
}

private fun StorageProfile.toStored(cipher: CredentialCodec) = StoredProfile(
  id = id,
  name = name,
  provider = provider,
  accountId = accountId,
  accessKeyId = accessKeyId,
  secretAccessKey = cipher.encrypt(secretAccessKey),
  managementApiToken = managementApiToken.takeIf(String::isNotEmpty)?.let(cipher::encrypt).orEmpty(),
  regionName = regionName,
  endpointUrl = endpointUrl,
  forcePathStyle = forcePathStyle,
  publicBaseUrl = publicBaseUrl,
)

@Serializable
internal data class StoredProfile(
  val id: String,
  val name: String,
  val provider: StorageProvider,
  val accountId: String = "",
  val accessKeyId: String,
  val secretAccessKey: String,
  val managementApiToken: String = "",
  val regionName: String = "",
  val endpointUrl: String = "",
  val forcePathStyle: Boolean = false,
  val publicBaseUrl: String = "",
) {
  fun toProfile(secretAccessKey: String, managementApiToken: String) = StorageProfile(
    id = id,
    name = name,
    provider = provider,
    accountId = accountId,
    accessKeyId = accessKeyId,
    secretAccessKey = secretAccessKey,
    managementApiToken = managementApiToken,
    regionName = regionName,
    endpointUrl = endpointUrl,
    forcePathStyle = forcePathStyle,
    publicBaseUrl = publicBaseUrl,
  )
}

@Serializable
private data class AppBackup(
  val profiles: List<StorageProfile>,
  val settings: AppSettingsSnapshot? = null,
  val favorites: List<FavoriteItem> = emptyList(),
)

data class ImportBackupResult(val profileCount: Int, val settings: AppSettingsSnapshot?)
