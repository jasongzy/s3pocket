package com.jasongzy.s3pocket

import com.jasongzy.s3pocket.data.moveFavorites
import com.jasongzy.s3pocket.data.updateFavorites
import com.jasongzy.s3pocket.data.model.FavoriteItem
import com.jasongzy.s3pocket.data.model.FavoriteKind
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoritesTest {
  @Test
  fun batchAdditionPreservesExistingOrderAndIsRepeatable() {
    val first = FavoriteItem("profile", "bucket", "first", FavoriteKind.FILE)
    val second = first.copy(key = "second")
    val added = first.copy(key = "new")
    val current = listOf(first, second)
    val selection = listOf(added, first)
    val expected = current + added

    assertEquals(expected, current.updateFavorites(selection, true))
    assertEquals(expected, expected.updateFavorites(selection, true))
  }

  @Test
  fun batchRemovalUsesCompleteIdentityAndPreservesRemainingOrder() {
    val folder = FavoriteItem("profile", "bucket", "photos/", FavoriteKind.FOLDER)
    val file = folder.copy(key = "photos/image.jpg", kind = FavoriteKind.FILE)
    val otherProfile = folder.copy(profileId = "other")
    val otherBucket = file.copy(bucket = "other")
    val current = listOf(otherProfile, folder, otherBucket, file)
    val remaining = current.updateFavorites(listOf(folder, file), false)
    assertEquals(listOf(otherProfile, otherBucket), remaining)
    assertEquals(remaining, remaining.updateFavorites(listOf(folder, file), false))
    assertEquals(current, current.updateFavorites(emptyList(), false))
    assertEquals(listOf(folder, file), emptyList<FavoriteItem>().updateFavorites(listOf(folder, file), true))
  }

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
