package dev.aniclipt.app.data

/** Only label information explicitly provided by a catalog; unknown languages remain unknown. */
internal fun catalogTags(anime: Anime): List<String> {
    val raw = (listOf(anime) + anime.alternatives).flatMap { it.metadata }.joinToString(" ").lowercase(java.util.Locale.ROOT)
    val tags = mutableListOf<String>()
    if (Regex("\\b(dublado|dubbed|dub)\\b").containsMatchIn(raw)) tags.add("Dublado")
    if (Regex("\\b(legendado|subbed|sub)\\b").containsMatchIn(raw)) tags.add("Legendado")
    if (Regex("\\b(original|japones|japonês)\\b").containsMatchIn(raw)) tags.add("Áudio original")
    if (Regex("\\b(pt-br|pt_br|portugu[eê]s)\\b").containsMatchIn(raw)) tags.add("PT-BR informado")
    if (Regex("\\b(english|ingl[eê]s)\\b").containsMatchIn(raw)) tags.add("Inglês informado")
    return tags.distinct()
}

internal fun showInCatalog(anime: Anime, hideAdult: Boolean): Boolean =
    anime.availability != Availability.EMPTY && (!hideAdult || anime.isAdult != true)

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
