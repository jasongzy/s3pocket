package com.jasongzy.s3pocket

import com.jasongzy.s3pocket.data.storage.ObjectMove
import com.jasongzy.s3pocket.data.storage.folderMoves
import com.jasongzy.s3pocket.ui.pathEntries
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderMovesTest {
  @Test
  fun navigationAndMovesPreserveEmptyPathSegments() {
    assertEquals(
      listOf("bucket" to "", "/" to "/", "source" to "/source/", "/" to "/source//"),
      pathEntries("bucket", "/source//"),
    )
    assertEquals(listOf("bucket" to ""), pathEntries("bucket", ""))
    assertEquals(
      listOf(ObjectMove("/source//", "/target//"), ObjectMove("/source//file", "/target//file")),
      folderMoves(listOf("/source//", "/source//file"), "/source//", "/target//"),
    )
  }

  @Test
  fun preservesRelativePaths() {
    assertEquals(
      listOf(
        ObjectMove("source/a.txt", "archive/a.txt"),
        ObjectMove("source/nested/b.txt", "archive/nested/b.txt"),
      ),
      folderMoves(
        listOf("source/a.txt", "source/nested/b.txt"),
        "source/",
        "archive/",
      ),
    )
  }
}
