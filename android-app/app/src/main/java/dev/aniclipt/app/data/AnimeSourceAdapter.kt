package dev.aniclipt.app.data

/** Provider-specific parsing stays behind this contract. A provider may expose only some capabilities. */
interface AnimeSourceAdapter {
    val id: String
    val searchable: Boolean get() = true
    val supportsExternalSubtitles: Boolean get() = false
    fun accepts(slug: String): Boolean
    suspend fun search(query: String): List<Anime> = emptyList()
    suspend fun episodes(anime: Anime): List<Episode> = emptyList()
    suspend fun enrichEpisodes(anime: Anime, episodes: List<Episode>): List<Episode> = episodes
    suspend fun localizeEpisodeTitle(episode: Episode): Episode = episode
    suspend fun seasons(anime: Anime): List<Anime> = listOf(anime)
    suspend fun artwork(anime: Anime): Anime = anime
    suspend fun resolve(anime: Anime, episode: Episode, dubbed: Boolean, externalSubtitles: Boolean, apiKey: String): PlaybackSource =
        error("Reprodução indisponível nesta fonte.")
}
