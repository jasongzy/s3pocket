package com.jasongzy.s3pocket.data.security

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class ProfileBackupCipher {
  private val json = Json
  private val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(KEY_SEED.toByteArray()), "AES")

  fun encrypt(value: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key)
    return json.encodeToString(
      BackupEnvelope(
        iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
        payload = Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP),
      )
    )
  }

  fun decrypt(value: String): String {
    val envelope = json.decodeFromString<BackupEnvelope>(value)
    require(envelope.version == 1) { "Unsupported backup version" }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(envelope.iv, Base64.NO_WRAP)))
    return String(cipher.doFinal(Base64.decode(envelope.payload, Base64.NO_WRAP)))
  }

  private companion object {
    const val KEY_SEED = "com.jasongzy.s3pocket.profile-backup.v1"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
  }
}

@Serializable
private data class BackupEnvelope(
  val version: Int = 1,
  val iv: String,
  val payload: String,
)
