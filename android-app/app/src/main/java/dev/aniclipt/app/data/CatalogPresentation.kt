package dev.aniclipt.app.data

/** Only label audio modes confirmed by a playable stream, never catalog claims or alternatives. */
internal fun catalogTags(anime: Anime): List<String> {
    val tags = mutableListOf<String>()
    if ("dublado" in anime.confirmedAudio) tags.add("Dublado")
    if ("legendado" in anime.confirmedAudio) tags.add("Legendado")
    return tags.distinct()
}

internal fun showInCatalog(anime: Anime, hideAdult: Boolean): Boolean =
    anime.availability != Availability.EMPTY && (!hideAdult || anime.isAdult != true && !knownSuggestiveTitle(anime.title))

/** Explicit sexual-content labels; broad genres such as romance and harem are not enough. */
internal fun hasAdultContentLabels(genres: List<String>, tags: List<Pair<String, Int>> = emptyList()): Boolean =
    genres.any { it.trim().lowercase(java.util.Locale.ROOT) in setOf("ecchi", "hentai") } ||
        tags.any { (name, rank) -> name.equals("nudity", ignoreCase = true) && rank >= 75 }

private fun knownSuggestiveTitle(title: String): Boolean =
    matchingTitle(title, null) == "mayoi neko overrun"

/** An episode list does not guarantee that its video streams are currently playable. */
internal fun availabilityFromEpisodeCount(count: Int?, hasAlternatives: Boolean): Availability = when {
    count == null -> Availability.UNKNOWN
    count > 0 -> Availability.LISTED
    hasAlternatives -> Availability.UNKNOWN
    else -> Availability.EMPTY
}

internal fun aggregateAdultFlags(items: List<Anime>): Boolean? = when {
    items.any { it.isAdult == true } -> true
    items.isNotEmpty() && items.all { it.isAdult == false } -> false
    else -> null
}

/** Keep the visible artwork until the selected season's details are ready. */
internal fun seasonWithRetainedArtwork(next: Anime, current: Anime?): Anime {
    if (current == null) return next
    val displayed = current.banner.ifBlank { current.poster }
    if (displayed.isBlank()) return next
    return next.copy(banner = displayed, poster = current.poster.ifBlank { next.poster })
}
