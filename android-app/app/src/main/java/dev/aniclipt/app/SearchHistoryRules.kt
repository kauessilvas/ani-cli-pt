package dev.aniclipt.app

import java.util.Locale

internal fun addSearchHistory(history: List<String>, term: String, limit: Int = 8): List<String> {
    val value = term.trim().replace(Regex("\\s+"), " ")
    if (value.isBlank()) return history
    val key = value.lowercase(Locale.ROOT)
    return (listOf(value) + history.filterNot { it.lowercase(Locale.ROOT) == key }).take(limit)
}

internal fun removeSearchHistory(history: List<String>, term: String): List<String> =
    history.filterNot { it.equals(term, ignoreCase = true) }
