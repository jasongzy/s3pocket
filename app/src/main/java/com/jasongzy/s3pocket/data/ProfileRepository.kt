package com.jasongzy.s3pocket.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jasongzy.s3pocket.data.model.StorageProfile
import com.jasongzy.s3pocket.data.model.FavoriteItem
import com.jasongzy.s3pocket.data.security.CredentialCipher
import com.jasongzy.s3pocket.data.security.ProfileBackupCipher
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

private val Context.profileDataStore by preferencesDataStore(name = "profiles")

class ProfileRepository(
  private val context: Context,
  private val cipher: CredentialCipher = CredentialCipher(),
  private val backupCipher: ProfileBackupCipher = ProfileBackupCipher(),
) {
  private val json = Json { ignoreUnknownKeys = true }

  val profiles: Flow<List<StorageProfile>> =
    context.profileDataStore.data.map { preferences ->
      preferences[PROFILES]?.let(cipher::decrypt)?.let(::decode) ?: emptyList()
    }

  val favorites: Flow<List<FavoriteItem>> =
    context.profileDataStore.data.map { preferences ->
      preferences[FAVORITES]?.let(cipher::decrypt)?.let(::decodeFavorites) ?: emptyList()
    }

  suspend fun find(id: String): StorageProfile? = profiles.first().firstOrNull { it.id == id }

  suspend fun get(id: String): StorageProfile = requireNotNull(find(id)) { "Profile not found" }

  suspend fun save(profile: StorageProfile): StorageProfile {
    val saved = profile.copy(id = profile.id.ifBlank { UUID.randomUUID().toString() })
    context.profileDataStore.edit { preferences ->
      val profiles = preferences[PROFILES]?.let(cipher::decrypt)?.let(::decode).orEmpty()
      val index = profiles.indexOfFirst { it.id == saved.id }
      val updated = if (index < 0) profiles + saved else profiles.toMutableList().apply { this[index] = saved }
      preferences[PROFILES] = cipher.encrypt(encode(updated))
    }
    return saved
  }

  suspend fun reorder(profileIds: List<String>) {
    context.profileDataStore.edit { preferences ->
      val current = preferences[PROFILES]?.let(cipher::decrypt)?.let(::decode).orEmpty()
      require(profileIds.size == current.size && profileIds.toSet() == current.map { it.id }.toSet()) {
        "Invalid profile order"
      }
      val profilesById = current.associateBy { it.id }
      preferences[PROFILES] = cipher.encrypt(encode(profileIds.map { profilesById.getValue(it) }))
    }
  }

  suspend fun delete(id: String) {
    context.profileDataStore.edit { preferences ->
      val profiles = preferences[PROFILES]?.let(cipher::decrypt)?.let(::decode).orEmpty()
      preferences[PROFILES] = cipher.encrypt(encode(profiles.filterNot { it.id == id }))
      val favorites = preferences[FAVORITES]?.let(cipher::decrypt)?.let(::decodeFavorites).orEmpty()
      preferences[FAVORITES] = cipher.encrypt(encodeFavorites(favorites.filterNot { it.profileId == id }))
    }
  }

  suspend fun setFavorite(favorite: FavoriteItem, enabled: Boolean) {
    context.profileDataStore.edit { preferences ->
      val current = preferences[FAVORITES]?.let(cipher::decrypt)?.let(::decodeFavorites).orEmpty()
      val updated = current.filterNot { it.id == favorite.id }.let { if (enabled) it + favorite else it }
      preferences[FAVORITES] = cipher.encrypt(encodeFavorites(updated))
    }
  }

  suspend fun setFavorites(items: List<FavoriteItem>, enabled: Boolean) {
    val ids = items.mapTo(mutableSetOf()) { it.id }
    context.profileDataStore.edit { preferences ->
      val current = preferences[FAVORITES]?.let(cipher::decrypt)?.let(::decodeFavorites).orEmpty()
      val retained = current.filterNot { it.id in ids }
      preferences[FAVORITES] = cipher.encrypt(encodeFavorites(if (enabled) retained + items else retained))
    }
  }

  suspend fun reorderFavorites(favoriteIds: List<String>) {
    context.profileDataStore.edit { preferences ->
      val current = preferences[FAVORITES]?.let(cipher::decrypt)?.let(::decodeFavorites).orEmpty()
      require(favoriteIds.size == current.size && favoriteIds.toSet() == current.map { it.id }.toSet()) {
        "Invalid favorite order"
      }
      val favoritesById = current.associateBy { it.id }
      preferences[FAVORITES] = cipher.encrypt(encodeFavorites(favoriteIds.map { favoritesById.getValue(it) }))
    }
  }

  suspend fun exportBackup(settings: AppSettingsSnapshot?): String =
    backupCipher.encrypt(json.encodeToString(AppBackup(profiles.first(), settings, favorites.first())))

  suspend fun importBackup(value: String): ImportBackupResult {
    val decrypted = backupCipher.decrypt(value)
    val backup = runCatching { json.decodeFromString<AppBackup>(decrypted) }
      .getOrElse { AppBackup(profiles = decode(decrypted)) }
    val imported = backup.profiles
    require(imported.all { it.id.isNotBlank() && it.name.isNotBlank() }) { "Invalid profile backup" }
    context.profileDataStore.edit { preferences ->
      val current = preferences[PROFILES]?.let(cipher::decrypt)?.let(::decode).orEmpty()
      val merged = current.toMutableList()
      imported.forEach { profile ->
        val index = merged.indexOfFirst { it.id == profile.id }
        if (index < 0) merged += profile else merged[index] = profile
      }
      preferences[PROFILES] = cipher.encrypt(encode(merged))
      val favoriteIds = merged.mapTo(mutableSetOf()) { it.id }
      val currentFavorites = preferences[FAVORITES]?.let(cipher::decrypt)?.let(::decodeFavorites).orEmpty()
      val importedFavorites = backup.favorites.filter { it.profileId in favoriteIds }
      val importedIds = importedFavorites.mapTo(mutableSetOf()) { it.id }
      preferences[FAVORITES] = cipher.encrypt(encodeFavorites(currentFavorites.filterNot { it.id in importedIds } + importedFavorites))
    }
    return ImportBackupResult(imported.size, backup.settings)
  }

  private fun encode(profiles: List<StorageProfile>): String =
    json.encodeToString(ListSerializer(StorageProfile.serializer()), profiles)

  private fun decode(value: String): List<StorageProfile> =
    json.decodeFromString(ListSerializer(StorageProfile.serializer()), value)

  private fun encodeFavorites(favorites: List<FavoriteItem>): String =
    json.encodeToString(ListSerializer(FavoriteItem.serializer()), favorites)

  private fun decodeFavorites(value: String): List<FavoriteItem> =
    json.decodeFromString(ListSerializer(FavoriteItem.serializer()), value)

  private companion object {
    val PROFILES = stringPreferencesKey("encrypted_profiles")
    val FAVORITES = stringPreferencesKey("encrypted_favorites")
  }
}

@Serializable
private data class AppBackup(
  val profiles: List<StorageProfile>,
  val settings: AppSettingsSnapshot? = null,
  val favorites: List<FavoriteItem> = emptyList(),
)

data class ImportBackupResult(val profileCount: Int, val settings: AppSettingsSnapshot?)
