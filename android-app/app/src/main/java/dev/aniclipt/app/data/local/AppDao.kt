package dev.aniclipt.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Query("SELECT * FROM watch_history ORDER BY lastWatchedMillis DESC")
    fun observeWatchHistory(): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history ORDER BY lastWatchedMillis DESC")
    suspend fun watchHistory(): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE releaseId = :releaseId LIMIT 1")
    suspend fun watchHistoryById(releaseId: String): WatchHistoryEntity?

    @Upsert
    suspend fun putWatchHistory(entity: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE releaseId = :releaseId")
    suspend fun deleteWatchHistory(releaseId: String)

    @Query("DELETE FROM watched_episodes WHERE releaseId = :releaseId")
    suspend fun deleteWatchedEpisodes(releaseId: String)

    @Query("SELECT * FROM completed_episodes WHERE releaseId = :releaseId")
    suspend fun completedEpisodes(releaseId: String): List<CompletedEpisodeEntity>

    @Query("SELECT * FROM completed_episodes")
    fun observeCompletedEpisodes(): Flow<List<CompletedEpisodeEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putCompletedEpisodes(episodes: List<CompletedEpisodeEntity>)

    @Query("DELETE FROM completed_episodes WHERE releaseId = :releaseId")
    suspend fun clearCompletedEpisodes(releaseId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putWatchedEpisodes(episodes: List<WatchedEpisodeEntity>)

    @Query("SELECT * FROM watched_episodes")
    fun observeWatchedEpisodes(): Flow<List<WatchedEpisodeEntity>>

    @Query("SELECT * FROM favorites ORDER BY title COLLATE NOCASE")
    fun observeFavorites(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites ORDER BY title COLLATE NOCASE")
    suspend fun favorites(): List<FavoriteEntity>

    @Query("SELECT * FROM favorites WHERE releaseId = :releaseId LIMIT 1")
    suspend fun favoriteById(releaseId: String): FavoriteEntity?

    @Upsert
    suspend fun putFavorite(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE releaseId = :releaseId")
    suspend fun deleteFavorite(releaseId: String)

    @Query("SELECT * FROM favorite_metadata WHERE releaseId = :releaseId ORDER BY position")
    suspend fun favoriteMetadata(releaseId: String): List<FavoriteMetadataEntity>

    @Query("SELECT * FROM favorite_metadata ORDER BY releaseId, position")
    fun observeFavoriteMetadata(): Flow<List<FavoriteMetadataEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFavoriteMetadata(values: List<FavoriteMetadataEntity>)

    @Query("SELECT * FROM identity_links")
    suspend fun identityLinks(): List<IdentityLinkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putIdentityLinks(links: List<IdentityLinkEntity>)

    @Query("SELECT * FROM identity_aliases")
    suspend fun identityAliases(): List<IdentityAliasEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putIdentityAliases(aliases: List<IdentityAliasEntity>)

    @Query("SELECT * FROM cached_metadata WHERE slug = :slug")
    suspend fun cachedMetadata(slug: String): CachedMetadataEntity?

    @Query("SELECT slug FROM cached_metadata ORDER BY MAX(artworkAt, episodesAt) DESC")
    suspend fun cachedMetadataSlugsByRecency(): List<String>

    @Query("DELETE FROM cached_metadata WHERE slug = :slug")
    suspend fun deleteCachedMetadata(slug: String)

    @Upsert
    suspend fun putCachedMetadata(entity: CachedMetadataEntity)

    @Query("SELECT * FROM cached_episodes WHERE slug = :slug ORDER BY position")
    suspend fun cachedEpisodes(slug: String): List<CachedEpisodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCachedEpisodes(episodes: List<CachedEpisodeEntity>)

    @Query("DELETE FROM cached_episodes WHERE slug = :slug")
    suspend fun clearCachedEpisodes(slug: String)

    @Query("SELECT * FROM favorite_episode_snapshots WHERE slug = :slug")
    suspend fun favoriteEpisodeSnapshot(slug: String): List<FavoriteEpisodeSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putFavoriteEpisodeSnapshot(episodes: List<FavoriteEpisodeSnapshotEntity>)

    @Query("DELETE FROM favorite_episode_snapshots WHERE slug = :slug")
    suspend fun clearFavoriteEpisodeSnapshot(slug: String)

    @Query("SELECT * FROM favorite_episode_snapshot_state WHERE slug = :slug LIMIT 1")
    suspend fun favoriteEpisodeSnapshotState(slug: String): FavoriteEpisodeSnapshotStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFavoriteEpisodeSnapshotState(state: FavoriteEpisodeSnapshotStateEntity)

    @Query("DELETE FROM favorite_episode_snapshot_state WHERE slug = :slug")
    suspend fun clearFavoriteEpisodeSnapshotState(slug: String)

    @Query("SELECT * FROM recent_searches ORDER BY position LIMIT 8")
    fun observeRecentSearches(): Flow<List<RecentSearchEntity>>

    @Query("SELECT * FROM recent_searches ORDER BY position LIMIT 8")
    suspend fun recentSearches(): List<RecentSearchEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRecentSearches(items: List<RecentSearchEntity>)

    @Query("DELETE FROM recent_searches")
    suspend fun clearRecentSearches()

    @Query("SELECT * FROM migration_markers WHERE name = :name LIMIT 1")
    suspend fun migrationMarker(name: String): MigrationMarkerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMigrationMarker(marker: MigrationMarkerEntity)
}
