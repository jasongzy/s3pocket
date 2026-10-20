package com.jasongzy.s3pocket

import com.jasongzy.s3pocket.data.CURRENT_BACKUP_FORMAT_VERSION
import com.jasongzy.s3pocket.data.decodeAppBackup
import com.jasongzy.s3pocket.data.validateBackup
import com.jasongzy.s3pocket.data.AppBackup
import com.jasongzy.s3pocket.data.AppSettingsSnapshot
import com.jasongzy.s3pocket.data.model.StorageProfile
import com.jasongzy.s3pocket.data.model.StorageProvider
import com.jasongzy.s3pocket.data.model.FavoriteItem
import com.jasongzy.s3pocket.data.model.FavoriteKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupFormatTest {
  @Test
  fun backupContentIsValidatedBeforeImport() {
    val profile = StorageProfile("id", "name", StorageProvider.AWS_S3,
      accessKeyId = "key", secretAccessKey = "secret", regionName = "us-east-1",
      publicBaseUrls = mapOf("bucket" to "https://cdn.example.com", "photos" to "https://cdn.example.com/photos"))
    val favorite = FavoriteItem("id", "bucket", "dir/", FavoriteKind.FOLDER)
    val backup = AppBackup(listOf(profile), AppSettingsSnapshot(), listOf(favorite), CURRENT_BACKUP_FORMAT_VERSION)
    val encoded = Json.encodeToString(backup)
    assertEquals(JsonPrimitive(CURRENT_BACKUP_FORMAT_VERSION), Json.parseToJsonElement(encoded).jsonObject["formatVersion"])
    val decoded = decodeAppBackup(encoded)
    validateBackup(decoded)
    assertEquals(backup, decoded)
    listOf(
      backup.copy(profiles = listOf(profile, profile)),
      backup.copy(favorites = listOf(favorite, favorite)),
      backup.copy(favorites = listOf(favorite.copy(profileId = "missing"))),
      backup.copy(favorites = listOf(favorite.copy(key = "file"))),
      backup.copy(profiles = listOf(profile.copy(secretAccessKey = ""))),
      backup.copy(profiles = listOf(profile.copy(publicBaseUrls = mapOf("" to "https://cdn.example.com")))),
      backup.copy(profiles = listOf(profile.copy(publicBaseUrls = mapOf("bucket" to "not-a-url")))),
      backup.copy(profiles = listOf(profile.copy(unassignedPublicUrl = "not-a-url"))),
      backup.copy(settings = AppSettingsSnapshot(presignedUrlExpiryMinutes = 0)),
      backup.copy(settings = AppSettingsSnapshot(metadataTemplate = mapOf("Key" to "", "key" to ""))),
    ).forEach { invalid ->
      assertThrows(IllegalArgumentException::class.java) { validateBackup(invalid) }
    }
  }

  @Test
  fun legacyPublicUrlsMigrateForBothBackupFormats() {
    val profiles = """[{"id":"id","name":"name","provider":"AWS_S3","accessKeyId":"key","secretAccessKey":"secret","regionName":"us-east-1","publicBaseUrl":"https://cdn.example.com"}]"""
    listOf(profiles, """{"profiles":$profiles}""").forEach { value ->
      val backup = decodeAppBackup(value)
      validateBackup(backup)
      val profile = backup.profiles.single()
      assertEquals("secret", profile.secretAccessKey)
      assertEquals(emptyMap<String, String>(), profile.publicBaseUrls)
      assertEquals("https://cdn.example.com", profile.unassignedPublicUrl)
      assertNull(profile.publicObjectUrl("bucket", "file"))
      assertEquals(CURRENT_BACKUP_FORMAT_VERSION, backup.formatVersion)
      val restored = decodeAppBackup(Json.encodeToString(backup))
      validateBackup(restored)
      assertEquals(backup, restored)
    }
  }

  @Test
  fun unversionedBackupObjectIsAccepted() {
    val backup = decodeAppBackup("""{"profiles":[],"settings":{"filePreviewEnabled":false}}""")

    assertEquals(CURRENT_BACKUP_FORMAT_VERSION, backup.formatVersion)
    assertEquals(false, backup.settings?.filePreviewEnabled)
  }

  @Test
  fun unsupportedObjectVersionIsRejected() {
    listOf("${CURRENT_BACKUP_FORMAT_VERSION + 1}", "0", "null", "\"invalid\"").forEach { version ->
      assertThrows(IllegalArgumentException::class.java) {
        decodeAppBackup("""{"profiles":[],"formatVersion":$version}""")
      }
    }
  }
}
