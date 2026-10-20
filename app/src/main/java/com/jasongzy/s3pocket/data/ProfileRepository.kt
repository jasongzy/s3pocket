package com.jasongzy.s3pocket.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jasongzy.s3pocket.data.model.FavoriteItem
import com.jasongzy.s3pocket.data.model.FavoriteKind
import com.jasongzy.s3pocket.data.model.isBucketName
import com.jasongzy.s3pocket.data.model.StorageProfile
import com.jasongzy.s3pocket.data.model.StorageProvider
import com.jasongzy.s3pocket.data.security.CredentialCipher
import com.jasongzy.s3pocket.data.security.CredentialCodec
import com.jasongzy.s3pocket.data.security.ProfileBackupCipher
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
  }.flowOn(Dispatchers.IO)

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

  suspend fun save(profile: StorageProfile): StorageProfile = withContext(Dispatchers.IO) {
    require(profile.validationError() == null) { "Invalid profile: ${profile.validationError()}" }
    val saved = profile.copy(id = profile.id.ifBlank { UUID.randomUUID().toString() })
    context.profileDataStore.edit { preferences ->
      val profiles = decodeStored(preferences[PROFILES].orEmpty()).toMutableList()
      val stored = saved.toStored(cipher)
      val index = profiles.indexOfFirst { it.id == saved.id }
      if (index < 0) profiles += stored else profiles[index] = stored
      preferences[PROFILES] = encodeStored(profiles)
    }
    saved
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

  suspend fun exportBackup(settings: AppSettingsSnapshot?): String = withContext(Dispatchers.IO) {
    val snapshot = state.first()
    require(snapshot.unavailableCredentialIds.isEmpty()) { "Re-enter unavailable credentials before exporting a backup." }
    backupCipher.encrypt(
      json.encodeToString(AppBackup(snapshot.profiles, settings, favorites.first(), CURRENT_BACKUP_FORMAT_VERSION))
    )
  }

  suspend fun importBackup(value: String): ImportBackupResult = withContext(Dispatchers.IO) {
    val decrypted = backupCipher.decrypt(value)
    val backup = decodeAppBackup(decrypted, json)
    validateBackup(backup)
    val imported = backup.profiles
    context.profileDataStore.edit { preferences ->
      val current = decodeStored(preferences[PROFILES].orEmpty()).toMutableList()
      imported.forEach { profile ->
        val stored = profile.toStored(cipher)
        val index = current.indexOfFirst { it.id == profile.id }
        if (index < 0) current += stored else current[index] = stored
      }
      preferences[PROFILES] = encodeStored(current)
      val currentFavorites = preferences[FAVORITES]?.let(::decodeFavorites).orEmpty()
      val importedFavorites = backup.favorites
      val importedIds = importedFavorites.mapTo(mutableSetOf()) { it.id }
      preferences[FAVORITES] = encodeFavorites(currentFavorites.filterNot { it.id in importedIds } + importedFavorites)
    }
    ImportBackupResult(imported.size, backup.settings, imported.any { it.unassignedPublicUrl.isNotEmpty() })
  }

  private fun decodeStored(value: String): List<StoredProfile> =
    decodeStoredList(value, json)

  private fun encodeStored(profiles: List<StoredProfile>): String =
    json.encodeToString(ListSerializer(StoredProfile.serializer()), profiles)

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
  val stored = decodeStoredList(value, json)
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
  publicBaseUrls = publicBaseUrls,
  unassignedPublicUrl = unassignedPublicUrl,
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
  val publicBaseUrls: Map<String, String> = emptyMap(),
  val unassignedPublicUrl: String = "",
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
    publicBaseUrls = publicBaseUrls,
    unassignedPublicUrl = unassignedPublicUrl,
  )
}

private fun decodeStoredList(value: String, json: Json): List<StoredProfile> =
  if (value.isBlank()) emptyList()
  else json.decodeFromJsonElement(json.parseToJsonElement(value).jsonArray.migratePublicUrls())

private fun JsonArray.migratePublicUrls(): JsonArray = JsonArray(map { element ->
  val fields = element.jsonObject.toMutableMap()
  val url = fields.remove("publicBaseUrl")?.jsonPrimitive?.contentOrNull.orEmpty()
  if (url.isNotBlank()) fields["unassignedPublicUrl"] = JsonPrimitive(url)
  JsonObject(fields)
})

internal const val CURRENT_BACKUP_FORMAT_VERSION = 1

@Serializable
internal data class AppBackup(
  val profiles: List<StorageProfile>,
  val settings: AppSettingsSnapshot? = null,
  val favorites: List<FavoriteItem> = emptyList(),
  val formatVersion: Int,
)

internal fun decodeAppBackup(value: String, json: Json = Json { ignoreUnknownKeys = true }): AppBackup =
  when (val element = json.parseToJsonElement(value)) {
    is JsonArray -> AppBackup(
      profiles = json.decodeFromJsonElement(element.migratePublicUrls()),
      formatVersion = CURRENT_BACKUP_FORMAT_VERSION,
    )
    is JsonObject -> json.decodeFromJsonElement<AppBackup>(
      if ("formatVersion" in element) element else JsonObject(element + mapOf(
        "profiles" to element.getValue("profiles").jsonArray.migratePublicUrls(),
        "formatVersion" to JsonPrimitive(CURRENT_BACKUP_FORMAT_VERSION),
      ))
    ).also {
      require(it.formatVersion == CURRENT_BACKUP_FORMAT_VERSION) { "Unsupported backup format" }
    }
    else -> throw IllegalArgumentException("Invalid backup format")
  }

internal fun validateBackup(backup: AppBackup) {
  val profileIds = backup.profiles.map { it.id }
  require(profileIds.distinct().size == profileIds.size) { "Duplicate profile ID" }
  backup.profiles.forEach { profile ->
    require(profile.id.isNotBlank() && profile.id.none(Char::isISOControl)) { "Invalid profile ID" }
    require(profile.validationError() == null) { "Invalid profile '${profile.name}': ${profile.validationError()}" }
  }
  require(backup.favorites.distinctBy { it.id }.size == backup.favorites.size) { "Duplicate favorite" }
  backup.favorites.forEach { favorite ->
    require(favorite.profileId in profileIds && favorite.bucket.isBucketName()) { "Invalid favorite destination" }
    require(when (favorite.kind) {
      FavoriteKind.BUCKET -> favorite.key.isEmpty()
      FavoriteKind.FOLDER -> favorite.key.isNotEmpty() && favorite.key.endsWith('/')
      FavoriteKind.FILE -> favorite.key.isNotEmpty() && !favorite.key.endsWith('/')
    }) { "Invalid favorite path" }
  }
  backup.settings?.let { settings ->
    require(settings.presignedUrlExpiryMinutes in 1..MAX_PRESIGNED_URL_EXPIRY_MINUTES) { "Invalid link expiry" }
    require(settings.languageTag in setOf("", "en", "zh-CN")) { "Unsupported app language" }
    val keys = settings.metadataTemplate.keys
    require(keys.all { it.isNotBlank() && it == it.trim() } &&
      keys.map { it.lowercase() }.distinct().size == keys.size) { "Invalid metadata template" }
  }
}

data class ImportBackupResult(
  val profileCount: Int,
  val settings: AppSettingsSnapshot?,
  val hasUnassignedPublicUrls: Boolean,
)
