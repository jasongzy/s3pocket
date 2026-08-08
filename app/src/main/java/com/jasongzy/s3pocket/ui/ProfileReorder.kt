package com.jasongzy.s3pocket.ui

import kotlin.math.roundToInt

internal data class ItemDragState(
  val itemId: String,
  val sourceIndex: Int,
  val targetIndex: Int,
  val offset: Float,
  val stride: Float,
) {
  fun moveTo(offset: Float, stride: Float, itemCount: Int): ItemDragState {
    require(stride > 0f && itemCount > 0)
    val target = (sourceIndex + (offset / stride).roundToInt()).coerceIn(0, itemCount - 1)
    return copy(targetIndex = target, offset = offset, stride = stride)
  }

  fun translationFor(index: Int): Float = when {
    index == sourceIndex -> offset
    sourceIndex < targetIndex && index in (sourceIndex + 1)..targetIndex -> -stride
    sourceIndex > targetIndex && index in targetIndex until sourceIndex -> stride
    else -> 0f
  }
}

internal fun <T> List<T>.moveItem(fromIndex: Int, toIndex: Int): List<T> {
  if (fromIndex == toIndex) return this
  return toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
}
