package dev.aniclipt.app

internal fun addWatchedEpisode(history: String, slug: String, episodeNumber: String): String {
    val key = "$slug:$episodeNumber"
    val entries = history.split(',').filter { it.isNotBlank() }
    return if (key in entries) history else (entries + key).takeLast(500).joinToString(",")
}

internal fun savedResumePosition(positionMs: Long, completed: Boolean): Long =
    if (completed) 0L else positionMs.coerceAtLeast(0L)
