package com.jasongzy.s3pocket

import com.jasongzy.s3pocket.data.transfer.isDescendantOf
import com.jasongzy.s3pocket.data.transfer.TransferStaging
import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferStagingTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test
  fun cacheCleanupProtectsPreparationAndSessionCloseReclaimsIt() {
    val staging = TransferStaging(temporary.root)
    val session = staging.openSession()
    val active = session.create("source-", ".tmp")
    val orphan = File(staging.directory, "orphan.tmp").apply { writeText("orphan") }
    staging.clearUnused()
    assertTrue(active.exists())
    assertFalse(orphan.exists())
    session.close()
    assertFalse(active.exists())
    assertTrue(runCatching { session.create("late-", ".tmp") }.isFailure)
  }

  @Test
  fun descendantCheckDoesNotMatchSiblingPrefix() {
    val root = File("build/cache")

    assertTrue(File(root, "source.tmp").isDescendantOf(root))
    assertFalse(File("build/cache-other/source.tmp").isDescendantOf(root))
  }
}
