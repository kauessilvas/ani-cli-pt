package dev.aniclipt.app.data

internal fun cleanSynopsis(value: String): String = value.trim().takeUnless {
    it.isBlank() || it.equals("null", ignoreCase = true) || it.equals("undefined", ignoreCase = true)
}.orEmpty()

/** The English catalog needs localization before its synopsis is displayed. */
internal fun initialSynopsis(anime: Anime): String =
    if (anime.slug.startsWith("hi:")) "" else cleanSynopsis(anime.synopsis)

internal data class SynopsisCandidate(val overview: String, val names: List<String>)

internal fun chooseLocalizedSynopsis(title: String, format: String, candidates: List<SynopsisCandidate>): String? {
    val matches = candidates.mapNotNull { candidate ->
        val overview = cleanSynopsis(candidate.overview).takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val score = identityScore(title, format, candidate.names, null) ?: return@mapNotNull null
        overview to score
    }.sortedByDescending { it.second }
    if (matches.isEmpty()) return null
    if (matches.size > 1 && matches[0].second - matches[1].second < .05 && matches[0].first != matches[1].first) return null
    return matches[0].first
}
