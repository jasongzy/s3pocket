package com.jasongzy.s3pocket

import com.jasongzy.s3pocket.ui.uniqueUploadKeys
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException

class UploadKeysTest {
  @Test
  fun avoidsRemoteAndBatchConflicts() = runTest {
    val existing = setOf("folder/photo.jpg", "folder/photo (1).jpg")
    val lookups = mutableListOf<String>()

    assertEquals(
      listOf("folder/photo (2).jpg", "folder/photo (3).jpg", "folder/free.txt"),
      uniqueUploadKeys(
        listOf("folder/photo.jpg", "folder/photo.jpg", "folder/free.txt"),
      ) { key -> lookups += key; key in existing },
    )
    assertEquals(lookups.distinct(), lookups)
  }

  @Test
  fun preservesPathsAndHandlesNamesWithoutExtensions() = runTest {
    assertEquals(
      listOf("folder/.env (1)", "folder/README (1)", "folder/archive.tar (1).gz"),
      uniqueUploadKeys(listOf("folder/.env", "folder/README", "folder/archive.tar.gz")) {
        !it.contains(" (1)")
      },
    )
  }

  @Test
  fun lookupFailureStopsKeySelection() = runTest {
    val error = IOException("Connection failed")
    val result = runCatching { uniqueUploadKeys(listOf("file")) { throw error } }

    assertSame(error, result.exceptionOrNull())
  }
}
