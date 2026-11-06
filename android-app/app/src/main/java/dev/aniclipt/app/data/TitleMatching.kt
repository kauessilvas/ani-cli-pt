package dev.aniclipt.app.data

import java.text.Normalizer
import java.util.Locale
import java.util.Collections

private fun <K, V> boundedLruCache(maxEntries: Int): MutableMap<K, V> =
    Collections.synchronizedMap(object : LinkedHashMap<K, V>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
    })

private val diacriticsPattern = Regex("\\p{Mn}+")
private val episodePattern = Regex("(?i)\\b(?:s\\d{1,2}e\\d{1,3}|\\d{1,2}x\\d{1,3})\\b")
private val punctuationPattern = Regex("[^a-z0-9]+")
private val audioSuffixPattern = Regex("(?i)\\s*(?:[-–—]\\s*)?\\b(?:dublado|legendado)\\b\\s*$")
private val movieFormatPattern = Regex("\\b(movie|filme|film)\\b")
private val tvFormatPattern = Regex("\\b(tv|season|temporada)\\b")
private val partPattern = Regex("\\b(?:part|parte|cour)\\s*(\\d{1,2})\\b")
private val ignoredTitleTokens = setOf("the", "movie", "filme", "film", "tv", "dublado", "legendado")
private val normalizedTextCache = boundedLruCache<String, String>(2048)
private val titleTokenCache = boundedLruCache<String, Set<String>>(1024)

internal fun normalizeMatchText(value: String): String = normalizedTextCache[value] ?: run {
    Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(diacriticsPattern, "")
        .lowercase(Locale.ROOT)
        .replace(episodePattern, " ")
        .replace(punctuationPattern, " ")
        .trim()
        .also { normalizedTextCache[value] = it }
}

private fun withoutAudioSuffix(title: String) = title.replace(audioSuffixPattern, "")

internal fun matchingTitle(title: String, canonicalId: Int?): String =
    canonicalId?.let { "anilist:$it" } ?: normalizeMatchText(withoutAudioSuffix(title))

private val seasonPatterns = listOf(
    Regex("\\b(?:season|temporada)\\s*(\\d{1,2})\\b"),
    Regex("\\b(\\d{1,2})(?:st|nd|rd|th|a|o)?\\s+(?:season|temporada)\\b"),
    Regex("\\bs(\\d{1,2})\\b"),
)

internal fun explicitSeason(title: String, slug: String = ""): Int? {
    val slugSeason = Regex("#s(\\d{1,2})$", RegexOption.IGNORE_CASE).find(slug)?.groupValues?.get(1)?.toIntOrNull()
    val normalized = normalizeMatchText(title)
    val titleSeason = seasonPatterns.firstNotNullOfOrNull { it.find(normalized)?.groupValues?.get(1)?.toIntOrNull() }
    // A title marked as season 2 must not become season 1 just because a catalog appends #s1.
    return titleSeason ?: slugSeason
}

private fun explicitPart(title: String): Int? {
    val text = normalizeMatchText(title)
    return partPattern.find(text)?.groupValues?.get(1)?.toIntOrNull()
}

internal fun explicitFormat(title: String, metadata: List<String> = emptyList()): String? {
    val text = normalizeMatchText(title)
    if (movieFormatPattern.containsMatchIn(text)) return "MOVIE"
    if (tvFormatPattern.containsMatchIn(text)) return "TV"
    val formats = metadata.map { normalizeMatchText(it) }.toSet()
    return when {
        formats.any { it in setOf("movie", "filme", "film") } -> "MOVIE"
        formats.any { it in setOf("tv", "tv series", "serie", "series") } -> "TV"
        else -> null
    }
}

internal fun identityScore(title: String, format: String, titles: List<String>, sourceFormat: String? = explicitFormat(title)): Double? {
    if (sourceFormat == "MOVIE" && format != "MOVIE" || sourceFormat == "TV" && format == "MOVIE") return null
    fun tokens(value: String): Set<String> = titleTokenCache[value] ?: normalizeMatchText(withoutAudioSuffix(value)).split(' ')
        .filter { it.isNotBlank() && it !in ignoredTitleTokens }.toSet().also { titleTokenCache[value] = it }
    val input = tokens(title)
    if (input.isEmpty()) return null
    val season = explicitSeason(title)
    val part = explicitPart(title)
    return titles.maxOfOrNull { alias ->
        val aliasSeason = explicitSeason(alias)
        val aliasPart = explicitPart(alias)
        val other = tokens(alias)
        if (other.isEmpty() || season != null && aliasSeason != null && season != aliasSeason || part != null && aliasPart != null && part != aliasPart) 0.0
        else 2.0 * input.intersect(other).size / (input.size + other.size)
    }?.takeIf { it >= .85 }
}

/** Only use another provider if identity, format and known season/part are compatible. */
internal fun canUseAsAlternative(first: Anime, second: Anime, firstId: Int?, secondId: Int?): Boolean {
    val formatA = explicitFormat(first.title, first.metadata)
    val formatB = explicitFormat(second.title, second.metadata)
    if (formatA != null && formatB != null && formatA != formatB) return false
    val seasonA = explicitSeason(first.title, first.slug)
    val seasonB = explicitSeason(second.title, second.slug)
    if (seasonA != null && seasonB != null && seasonA != seasonB) return false
    if (seasonA != seasonB && ((seasonA ?: 1) > 1 || (seasonB ?: 1) > 1)) return false
    val partA = explicitPart(first.title)
    val partB = explicitPart(second.title)
    if (partA != partB && (partA != null || partB != null)) return false
    if (firstId != null && secondId != null) return firstId == secondId
    // If only one side has an identity, exact normalized title is still required.
    return matchingTitle(first.title, null) == matchingTitle(second.title, null)
}

internal fun sameEpisodeNumber(first: String, second: String): Boolean {
    val a = first.trim().toBigDecimalOrNull() ?: return false
    val b = second.trim().toBigDecimalOrNull() ?: return false
    return a.signum() >= 0 && b.signum() >= 0 && a.compareTo(b) == 0
}

/** Filter provider noise only when there is at least one reliable result. */
internal fun filterRelevantResults(results: List<Anime>, query: String, queryIds: Set<Int>, relatedTitles: List<String> = emptyList()): List<Anime> {
    val normalizedQueries = (listOf(query) + relatedTitles).map(::normalizeMatchText).filter(String::isNotBlank).distinct()
    if (normalizedQueries.isEmpty()) return results
    val relevant = results.filter { anime ->
        (listOf(anime) + anime.alternatives).any { candidate ->
            normalizedQueries.any { normalizeMatchText(candidate.title).contains(it) } ||
                candidate.anilistId?.let { it in queryIds } == true
        }
    }
    return relevant.ifEmpty { results }
}
