package com.jasongzy.s3pocket.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class MetadataOperator(val needsValue: Boolean = false) {
  EXISTS, MISSING, EMPTY, NOT_EMPTY, EQUALS(true), NOT_EQUALS(true), CONTAINS(true), NOT_CONTAINS(true),
}

@Serializable
data class MetadataCondition(
  val key: String = "",
  val operator: MetadataOperator = MetadataOperator.EXISTS,
  val value: String = "",
) {
  fun matches(metadata: Map<String, String>, ignoreCase: Boolean): Boolean {
    val entry = metadata.entries.firstOrNull { it.key.equals(key.trim(), ignoreCase = true) }
    return when (operator) {
      MetadataOperator.EXISTS -> entry != null
      MetadataOperator.MISSING -> entry == null
      MetadataOperator.EMPTY -> entry != null && entry.value.isEmpty()
      MetadataOperator.NOT_EMPTY -> entry != null && entry.value.isNotEmpty()
      MetadataOperator.EQUALS -> entry != null && entry.value.equals(value, ignoreCase)
      MetadataOperator.NOT_EQUALS -> entry != null && !entry.value.equals(value, ignoreCase)
      MetadataOperator.CONTAINS -> entry != null && entry.value.contains(value, ignoreCase)
      MetadataOperator.NOT_CONTAINS -> entry != null && !entry.value.contains(value, ignoreCase)
    }
  }
}

@Serializable
data class MetadataFilter(
  val conditions: List<MetadataCondition> = listOf(MetadataCondition()),
  val matchAll: Boolean = true,
  val ignoreCase: Boolean = false,
) {
  val isValid: Boolean
    get() = conditions.isNotEmpty() && conditions.all {
      it.key.isNotBlank() && (!it.operator.needsValue || it.value.isNotEmpty())
    }

  fun matches(metadata: Map<String, String>): Boolean =
    if (matchAll) conditions.all { it.matches(metadata, ignoreCase) }
    else conditions.any { it.matches(metadata, ignoreCase) }

  fun matches(item: ObjectItem, metadata: Map<String, String>?): Boolean =
    !item.isFolder && metadata?.let(::matches) == true
}
