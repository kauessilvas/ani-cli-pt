package dev.aniclipt.app.data

private val finalSeasonPattern = Regex("\\b(?:the\\s+)?final\\s+season\\b")
private val numberedSeasonPattern = Regex("\\b(?:season|temporada)\\s*\\d+\\b|\\b\\d+(?:st|nd|rd|th)\\s+(?:season|temporada)\\b|\\bs\\s*\\d+\\b")
private val partPattern = Regex("\\b(?:part|parte|cour)\\s*(\\d+)\\b")

/** A work family is only used to propose navigation siblings, never to merge releases. */
internal fun seasonFamilyKey(title: String): String = normalizeMatchText(title)
    .replace(finalSeasonPattern, " ")
    .replace(numberedSeasonPattern, " ")
    .replace(partPattern, " ")
    .replace(Regex("\\s+"), " ")
    .trim()

internal fun isFinalSeason(title: String): Boolean = finalSeasonPattern.containsMatchIn(normalizeMatchText(title))

internal fun seasonPart(title: String): Int? = partPattern.find(normalizeMatchText(title))?.groupValues?.get(1)?.toIntOrNull()

internal fun seasonOrder(anime: Anime): Triple<Int, Int, Int> = if (isFinalSeason(anime.title)) {
    Triple(1, Int.MAX_VALUE, seasonPart(anime.title) ?: 1)
} else Triple(0, explicitSeason(anime.title, anime.slug) ?: 1, seasonPart(anime.title) ?: 0)

internal fun seasonDisplayLabel(anime: Anime, seasons: List<Anime>): String {
    if (isFinalSeason(anime.title)) return "Temporada final" + (seasonPart(anime.title)?.let { " · Parte $it" } ?: "")
    explicitSeason(anime.title, anime.slug)?.let { number ->
        return "Temporada $number" + (seasonPart(anime.title)?.let { " · Parte $it" } ?: "")
    }
    seasonPart(anime.title)?.let { return "Parte $it" }
    val first = seasons.indexOfFirst { it.slug == anime.slug } == 0
    return if (first || seasons.size == 1) "Temporada 1" else anime.title
}

internal fun seasonWatchLabel(anime: Anime, seasons: List<Anime>, episodeNumber: String): String {
    val context = if (isFinalSeason(anime.title)) {
        "Final" + (seasonPart(anime.title)?.let { " P$it" } ?: "")
    } else {
        val label = seasonDisplayLabel(anime, seasons)
        Regex("^Temporada (\\d+)(?: · Parte (\\d+))?$").matchEntire(label)?.let { match ->
            "T${match.groupValues[1]}" + (match.groupValues[2].takeIf(String::isNotBlank)?.let { " P$it" } ?: "")
        }.orEmpty()
    }
    return "Assistir " + (context.takeIf(String::isNotBlank)?.let { "$it " } ?: "") + "EP.$episodeNumber"
}
