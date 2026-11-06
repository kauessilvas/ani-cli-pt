package dev.aniclipt.app.data

internal fun adjacentEpisode(episodes: List<Episode>, current: Episode?, offset: Int): Episode? {
    if (current == null || offset !in listOf(-1, 1)) return null
    val byId = episodes.indexOfFirst { it.id == current.id }
    val index = if (byId >= 0) byId else episodes.indexOfFirst { sameEpisodeNumber(it.number, current.number) }
    return if (index >= 0) episodes.getOrNull(index + offset) else null
}
