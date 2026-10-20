package com.jasongzy.s3pocket

import com.jasongzy.s3pocket.data.moveFavorites
import com.jasongzy.s3pocket.data.model.FavoriteItem
import com.jasongzy.s3pocket.data.model.FavoriteKind
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteMovesTest {
  @Test
  fun renameKeepsOrderAndDoesNotChangeOtherConnections() {
    val source = FavoriteItem("profile", "bucket", "old.txt", FavoriteKind.FILE)
    val other = source.copy(profileId = "other")
    val favorites = listOf(source, other, source.copy(key = "new.txt"))
    assertEquals(
      listOf(source.copy(key = "new.txt"), other),
      favorites.moveFavorites("profile", "bucket", "old.txt", "new.txt", false),
    )
  }

  @Test
  fun folderMovePreservesRelativePathsAndSiblingPrefixes() {
    val folder = FavoriteItem("profile", "bucket", "dir/", FavoriteKind.FOLDER)
    val file = folder.copy(key = "dir/nested/file", kind = FavoriteKind.FILE)
    val sibling = file.copy(key = "directory/file")
    assertEquals(
      listOf(folder.copy(key = "new/"), file.copy(key = "new/nested/file"), sibling),
      listOf(folder, file, sibling).moveFavorites("profile", "bucket", "dir/", "new/", true),
    )
  }
}
