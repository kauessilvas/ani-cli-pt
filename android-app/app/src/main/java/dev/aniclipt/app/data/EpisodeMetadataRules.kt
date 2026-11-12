package dev.aniclipt.app.data

internal data class EpisodeMetadata(val number: Int, val title: String, val still: String)

/** Gate only the first visible rows; later rows can be refreshed without exposing fake stills. */
internal fun hasUsableEpisodeMetadata(anime: Anime, episodes: List<Episode>): Boolean {
    val first = episodes.take(8)
    return first.isNotEmpty() && first.any { it.thumbnail.isNotBlank() } && first.all { episode ->
        episode.title.isNotBlank() && !genericEpisodeTitle(episode.title, episode.number) &&
            (!anime.slug.startsWith("hi:") || episode.title != episode.originalTitle)
    }
}

private fun genericEpisodeTitle(title: String, number: String): Boolean {
    val normalized = normalizeMatchText(title)
    val expected = number.trim().toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: number
    return normalized.isBlank() || normalized == "episode $expected" || normalized == "episodio $expected" || normalized == "full"
}

private fun sameEpisodeTitle(source: String, reference: String): Boolean {
    val a = normalizeMatchText(source)
    val b = normalizeMatchText(reference)
    if (a.isBlank() || b.isBlank()) return false
    if (a == b) return true
    val shorter = if (a.length <= b.length) a else b
    val longer = if (a.length > b.length) a else b
    val words = shorter.split(' ').filter { it.isNotBlank() }
    if (words.size >= 2 && longer.startsWith("$shorter ")) return true
    val ignored = setOf("a", "an", "and", "for", "in", "of", "the", "to", "she", "he", "his", "her")
    val sourceWords = a.split(' ').filter { it.isNotBlank() && it !in ignored && it.toIntOrNull() == null }.toSet()
    val referenceWords = b.split(' ').filter { it.isNotBlank() && it !in ignored && it.toIntOrNull() == null }.toSet()
    val common = sourceWords.intersect(referenceWords).size
    return common >= 2 && 2.0 * common / (sourceWords.size + referenceWords.size) >= .55
}

/** Match a whole season before copying any translated title or still. */
internal fun mergeVerifiedEpisodeMetadata(
    episodes: List<Episode>,
    english: List<EpisodeMetadata>,
    portuguese: List<EpisodeMetadata>,
): List<Episode> {
    if (episodes.size < 2 || english.isEmpty() || portuguese.isEmpty()) return episodes
    val en = english.associateBy { it.number }
    val pt = portuguese.associateBy { it.number }
    val numbered = episodes.mapNotNull { episode ->
        val number = episode.number.toIntOrNull() ?: return@mapNotNull null
        if (number <= 0 || en[number] == null || pt[number] == null) return@mapNotNull null
        episode to number
    }
    if (numbered.size != episodes.size) return episodes
    val sample = numbered.take(8).filterNot { (episode, _) -> genericEpisodeTitle(episode.originalTitle, episode.number) }
    val matched = sample.count { (episode, number) -> sameEpisodeTitle(episode.originalTitle, en.getValue(number).title) }
    val reliable = if (sample.isEmpty()) episodes.size == english.size else {
        val required = if (episodes.size == english.size) .5 else .6
        matched >= maxOf(2, kotlin.math.ceil(sample.size * required).toInt())
    }
    if (!reliable) return episodes
    return numbered.map { (episode, number) ->
        val localized = pt.getValue(number)
        val title = localized.title.takeIf { it.isNotBlank() && !genericEpisodeTitle(it, episode.number) }
            ?: episode.title.takeUnless { genericEpisodeTitle(it, episode.number) }
            ?: "Episódio ${episode.number}"
        episode.copy(title = title, thumbnail = localized.still.ifBlank { episode.thumbnail })
    }
}

/** The work and release were matched before this call; accept only aligned episode numbers. */
internal fun mergeOnlineEpisodeMetadata(episodes: List<Episode>, online: List<OnlineEpisodeMetadata>, preferPortugueseTitles: Boolean): List<Episode> {
    if (episodes.size < 2 || online.size < episodes.size) return episodes
    val byNumber = online.associateBy { it.number }
    val numbers = episodes.map { it.number.toIntOrNull() ?: return episodes }
    if (numbers.any { it <= 0 || byNumber[it] == null } || numbers.distinct().size != numbers.size) return episodes
    if (online.size != episodes.size && numbers.sorted() != (1..episodes.size).toList()) return episodes
    return episodes.map { episode ->
        val match = byNumber.getValue(episode.number.toInt())
        val replaceTitle = genericEpisodeTitle(episode.title, episode.number) ||
            preferPortugueseTitles && episode.title == episode.originalTitle
        episode.copy(
            title = if (replaceTitle) match.title.ifBlank { "Episódio ${episode.number}" } else episode.title,
            thumbnail = episode.thumbnail.ifBlank { match.still },
        )
    }
}
