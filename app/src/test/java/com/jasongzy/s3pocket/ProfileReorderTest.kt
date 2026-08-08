package com.jasongzy.s3pocket

import com.jasongzy.s3pocket.ui.ItemDragState
import com.jasongzy.s3pocket.ui.moveItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileReorderTest {
  @Test
  fun dragDown_movesTargetAndShiftsInterveningItemUp() {
    val drag = ItemDragState("first", 0, 0, 0f, 100f).moveTo(60f, 100f, 2)

    assertEquals(1, drag.targetIndex)
    assertEquals(60f, drag.translationFor(0))
    assertEquals(-100f, drag.translationFor(1))
    assertEquals(listOf("second", "first"), listOf("first", "second").moveItem(0, 1))
  }

  @Test
  fun dragUp_movesTargetAndShiftsInterveningItemDown() {
    val drag = ItemDragState("second", 1, 1, 0f, 100f).moveTo(-60f, 100f, 2)

    assertEquals(0, drag.targetIndex)
    assertEquals(100f, drag.translationFor(0))
    assertEquals(-60f, drag.translationFor(1))
    assertEquals(listOf("second", "first"), listOf("first", "second").moveItem(1, 0))
  }
}
