package com.jasongzy.s3pocket

import com.jasongzy.s3pocket.data.StoredProfile
import com.jasongzy.s3pocket.data.decodeStoredProfiles
import com.jasongzy.s3pocket.data.model.StorageProvider
import com.jasongzy.s3pocket.data.security.CredentialCodec
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCredentialRecoveryTest {
  private val json = Json

  @Test
  fun unavailableSecretPreservesProfileConfiguration() {
    val stored = StoredProfile(
      id = "profile-id",
      name = "Archive",
      provider = StorageProvider.AWS_S3,
      accessKeyId = "access-key-id",
      secretAccessKey = "unreadable",
      regionName = "ap-southeast-1",
      endpointUrl = "https://objects.example.com",
      forcePathStyle = true,
      publicBaseUrls = mapOf("public" to "https://cdn.example.com", "photos" to "https://images.example.com"),
      unassignedPublicUrl = "https://unassigned.example.com",
    )

    val state = decodeStoredProfiles(
      json.encodeToString(ListSerializer(StoredProfile.serializer()), listOf(stored)),
      FailingCredentialCodec,
      json,
    )

    assertEquals(setOf("profile-id"), state.unavailableCredentialIds)
    with(state.profiles.single()) {
      assertEquals("Archive", name)
      assertEquals("access-key-id", accessKeyId)
      assertEquals("ap-southeast-1", regionName)
      assertEquals("https://objects.example.com", endpointUrl)
      assertEquals(stored.publicBaseUrls, publicBaseUrls)
      assertEquals(stored.unassignedPublicUrl, unassignedPublicUrl)
      assertTrue(forcePathStyle)
      assertTrue(secretAccessKey.isEmpty())
    }
  }

  @Test
  fun storedPublicUrlMigratesWithoutChangingEncryptedCredentials() {
    val stored = """[{"id":"id","name":"Storage","provider":"AWS_S3","accessKeyId":"key","secretAccessKey":"encrypted-secret","publicBaseUrl":"https://cdn.example.com/path"}]"""
    val profile = decodeStoredProfiles(stored, PassingCredentialCodec, json).profiles.single()
    assertEquals("https://cdn.example.com/path", profile.unassignedPublicUrl)
    assertTrue(profile.publicBaseUrls.isEmpty())
    assertEquals("encrypted-secret", profile.secretAccessKey)
  }

  @Test
  fun optionalMissingManagementTokenDoesNotRequireRecovery() {
    val stored = StoredProfile(
      id = "profile-id",
      name = "R2",
      provider = StorageProvider.CLOUDFLARE_R2,
      accessKeyId = "access-key-id",
      secretAccessKey = "encrypted-secret",
    )

    val state = decodeStoredProfiles(
      json.encodeToString(ListSerializer(StoredProfile.serializer()), listOf(stored)),
      PassingCredentialCodec,
      json,
    )

    assertTrue(state.unavailableCredentialIds.isEmpty())
    assertEquals("encrypted-secret", state.profiles.single().secretAccessKey)
    assertTrue(state.profiles.single().managementApiToken.isEmpty())
  }

  @Test
  fun unavailableManagementTokenRequiresRecovery() {
    val stored = StoredProfile(
      id = "profile-id",
      name = "R2",
      provider = StorageProvider.CLOUDFLARE_R2,
      accessKeyId = "access-key-id",
      secretAccessKey = "secret:encrypted-secret",
      managementApiToken = "token:encrypted-token",
    )

    val state = decodeStoredProfiles(
      json.encodeToString(ListSerializer(StoredProfile.serializer()), listOf(stored)),
      SelectiveCredentialCodec,
      json,
    )

    assertEquals(setOf("profile-id"), state.unavailableCredentialIds)
    assertEquals("encrypted-secret", state.profiles.single().secretAccessKey)
    assertTrue(state.profiles.single().managementApiToken.isEmpty())
  }
}

private object FailingCredentialCodec : CredentialCodec {
  override fun encrypt(value: String): String = value

  override fun decrypt(value: String): String = error("Keystore key unavailable")
}

private object PassingCredentialCodec : CredentialCodec {
  override fun encrypt(value: String): String = value

  override fun decrypt(value: String): String = value
}

private object SelectiveCredentialCodec : CredentialCodec {
  override fun encrypt(value: String): String = value

  override fun decrypt(value: String): String = when {
    value.startsWith("secret:") -> value.removePrefix("secret:")
    else -> error("Keystore key unavailable")
  }
}
