package com.jasongzy.s3pocket

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {
  @Test
  fun repeatedBackDuringExitOnlyRemovesTheOriginatingPage() {
    val editor = ProfileEditorRoute("profile")
    val stack = mutableListOf<NavKey>(ProfilesRoute, ProfileInfoRoute("profile"), editor)

    assertTrue(stack.popIfCurrent(editor))
    assertFalse(stack.popIfCurrent(editor))
    assertEquals(listOf(ProfilesRoute, ProfileInfoRoute("profile")), stack)
  }

  @Test
  fun lateSaveCallbackLeavesTheCurrentPageIntact() {
    val stack = mutableListOf<NavKey>(ProfilesRoute, SettingsRoute)

    if (stack.popIfCurrent(ProfileEditorRoute())) stack.add(BucketsRoute("profile"))

    assertEquals(listOf(ProfilesRoute, SettingsRoute), stack)
    assertTrue(stack.popIfCurrent(SettingsRoute))
    assertFalse(stack.popIfCurrent(ProfilesRoute))
    assertEquals(listOf(ProfilesRoute), stack)
  }
}
