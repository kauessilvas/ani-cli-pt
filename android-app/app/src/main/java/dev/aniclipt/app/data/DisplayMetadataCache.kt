package dev.aniclipt.app.data

import androidx.room.withTransaction
import dev.aniclipt.app.data.local.AppDatabase
import dev.aniclipt.app.data.local.CachedEpisodeEntity
import dev.aniclipt.app.data.local.CachedMetadataEntity

/** Durable, typed metadata cache shared by ViewModels and workers. */
internal class DisplayMetadataCache(private val database: AppDatabase) {
    private val dao = database.appDao()

    suspend fun artwork(anime: Anime): Anime? {
        val row = dao.cachedMetadata(anime.slug) ?: return null
        if (System.currentTimeMillis() - row.artworkAt !in 0..ARTWORK_TTL) return null
        return anime.copy(
            poster = row.poster.ifBlank { anime.poster },
            banner = row.banner.ifBlank { anime.banner },
            synopsis = row.synopsis.ifBlank { anime.synopsis },
        )
    }

    suspend fun episodes(anime: Anime): List<Episode>? {
        val row = dao.cachedMetadata(anime.slug) ?: return null
        if (System.currentTimeMillis() - row.episodesAt !in 0..EPISODE_TTL) return null
        return dao.cachedEpisodes(anime.slug).map { episode ->
            Episode(episode.sourceEpisodeId, episode.number, episode.title, episode.originalTitle,
                episode.thumbnail, episode.episodeId)
        }.takeIf { it.isNotEmpty() }
    }

    suspend fun saveArtwork(anime: Anime) {
        if (anime.poster.isBlank() && anime.banner.isBlank() && anime.synopsis.isBlank()) return
        database.withTransaction {
            val previous = dao.cachedMetadata(anime.slug)
            dao.putCachedMetadata(CachedMetadataEntity(
                slug = anime.slug, poster = anime.poster, banner = anime.banner, synopsis = anime.synopsis,
                artworkAt = System.currentTimeMillis(), episodesAt = previous?.episodesAt ?: 0L,
            ))
            evictOldEntries()
        }
    }

    suspend fun saveEpisodes(anime: Anime, episodes: List<Episode>) {
        if (episodes.isEmpty() || episodes.size > 300) return
        database.withTransaction {
            val previous = dao.cachedMetadata(anime.slug)
            dao.putCachedMetadata(CachedMetadataEntity(
                slug = anime.slug, poster = previous?.poster.orEmpty(), banner = previous?.banner.orEmpty(),
                synopsis = previous?.synopsis.orEmpty(), artworkAt = previous?.artworkAt ?: 0L,
                episodesAt = System.currentTimeMillis(),
            ))
            dao.clearCachedEpisodes(anime.slug)
            dao.putCachedEpisodes(episodes.mapIndexed { index, item ->
                CachedEpisodeEntity(anime.slug, item.id, item.number, item.title, item.originalTitle,
                    item.thumbnail, item.episodeId, index)
            })
            evictOldEntries()
        }
    }

    private suspend fun evictOldEntries() {
        dao.cachedMetadataSlugsByRecency().drop(MAX_RELEASES).forEach { dao.deleteCachedMetadata(it) }
    }

    companion object {
        private const val MAX_RELEASES = 32
        private const val ARTWORK_TTL = 7 * 24 * 60 * 60_000L
        private const val EPISODE_TTL = 24 * 60 * 60_000L
    }
}

/** Preserve ready metadata while refreshing the source's episode list. */
internal fun reuseEpisodeMetadata(fresh: List<Episode>, cached: List<Episode>): List<Episode> {
    val byId = cached.associateBy { it.id }
    return fresh.map { episode ->
        val old = byId[episode.id]?.takeIf { it.number == episode.number } ?: return@map episode
        episode.copy(
            title = old.title.takeIf { it.isNotBlank() && it != old.originalTitle } ?: episode.title,
            thumbnail = old.thumbnail.ifBlank { episode.thumbnail },
            originalTitle = episode.originalTitle,
        )
    }
}
