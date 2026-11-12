package dev.aniclipt.app.data.local

import androidx.room.withTransaction
import dev.aniclipt.app.addSearchHistory
import dev.aniclipt.app.removeSearchHistory
import kotlinx.coroutines.flow.Flow

/** Atomic domain operations shared by UI ViewModels and FavoriteEpisodeWorker. */
class LocalAnimeStore(private val database: AppDatabase) {
    private val dao = database.appDao()

    val favorites: Flow<List<FavoriteEntity>> = dao.observeFavorites()
    val favoriteMetadata: Flow<List<FavoriteMetadataEntity>> = dao.observeFavoriteMetadata()
    val watchHistory: Flow<List<WatchHistoryEntity>> = dao.observeWatchHistory()
    val completedEpisodes: Flow<List<CompletedEpisodeEntity>> = dao.observeCompletedEpisodes()
    val watchedEpisodes: Flow<List<WatchedEpisodeEntity>> = dao.observeWatchedEpisodes()
    val recentSearches: Flow<List<RecentSearchEntity>> = dao.observeRecentSearches()

    suspend fun toggleFavorite(favorite: FavoriteEntity, metadata: List<String>): Boolean = database.withTransaction {
        if (dao.favoriteById(favorite.releaseId) != null) {
            dao.deleteFavorite(favorite.releaseId)
            dao.clearFavoriteEpisodeSnapshot(favorite.slug)
            dao.clearFavoriteEpisodeSnapshotState(favorite.slug)
            false
        } else {
            dao.putFavorite(favorite)
            dao.putFavoriteMetadata(metadata.mapIndexed { index, value -> FavoriteMetadataEntity(favorite.releaseId, index, value) })
            true
        }
    }

    suspend fun recordWatchProgress(
        item: WatchHistoryEntity,
        secondsDelta: Long,
        episodeKey: String,
        completed: Boolean,
    ) = database.withTransaction {
        val previous = dao.watchHistoryById(item.releaseId)
        dao.putWatchHistory(item.copy(secondsWatched = (previous?.secondsWatched ?: 0L) + secondsDelta.coerceAtLeast(0L)))
        dao.putWatchedEpisodes(listOf(WatchedEpisodeEntity(item.releaseId, episodeKey)))
        if (completed) dao.putCompletedEpisodes(listOf(CompletedEpisodeEntity(item.releaseId, episodeKey)))
    }

    suspend fun removeWatchHistory(releaseId: String) = database.withTransaction {
        dao.deleteWatchHistory(releaseId) // completed_episodes is removed by its foreign key.
        dao.deleteWatchedEpisodes(releaseId)
    }

    /** Returns IDs that appeared since the previous snapshot. A missing snapshot is a baseline. */
    suspend fun replaceFavoriteEpisodeSnapshot(slug: String, ids: Set<String>): Set<String> = database.withTransaction {
        val previouslyCaptured = dao.favoriteEpisodeSnapshotState(slug) != null
        val previous = dao.favoriteEpisodeSnapshot(slug)
        dao.clearFavoriteEpisodeSnapshot(slug)
        dao.putFavoriteEpisodeSnapshot(ids.map { FavoriteEpisodeSnapshotEntity(slug, it) })
        dao.putFavoriteEpisodeSnapshotState(FavoriteEpisodeSnapshotStateEntity(slug, System.currentTimeMillis()))
        if (!previouslyCaptured) emptySet() else ids - previous.map { it.sourceEpisodeId }.toSet()
    }

    suspend fun replaceRecentSearches(terms: List<String>) = database.withTransaction {
        dao.clearRecentSearches()
        dao.putRecentSearches(terms.take(8).mapIndexed { index, term -> RecentSearchEntity(term, index) })
    }

    suspend fun addRecentSearch(term: String) = database.withTransaction {
        val updated = addSearchHistory(dao.recentSearches().map { it.term }, term)
        dao.clearRecentSearches()
        dao.putRecentSearches(updated.mapIndexed { index, value -> RecentSearchEntity(value, index) })
    }

    suspend fun removeRecentSearch(term: String) = database.withTransaction {
        val updated = removeSearchHistory(dao.recentSearches().map { it.term }, term)
        dao.clearRecentSearches()
        dao.putRecentSearches(updated.mapIndexed { index, value -> RecentSearchEntity(value, index) })
    }

    suspend fun saveCachedEpisodes(metadata: CachedMetadataEntity, episodes: List<CachedEpisodeEntity>) = database.withTransaction {
        dao.putCachedMetadata(metadata)
        dao.clearCachedEpisodes(metadata.slug)
        dao.putCachedEpisodes(episodes)
    }
}
