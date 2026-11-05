package dev.aniclipt.app.data

private val tmdbSizePrefix = Regex("^https://image\\.tmdb\\.org/t/p/(?:original|w\\d+)/", RegexOption.IGNORE_CASE)

/** Reduce TMDB image transfer sizes without changing URLs from other providers. */
internal fun imageUrlForWidth(url: String, width: Int): String {
    val value = url.trim()
    if (value.isBlank() || value.equals("null", true) || value.equals("undefined", true)) return ""
    val size = if (width <= 342) "w342" else if (width <= 780) "w780" else "w1280"
    return tmdbSizePrefix.replaceFirst(value, "https://image.tmdb.org/t/p/$size/")
}
