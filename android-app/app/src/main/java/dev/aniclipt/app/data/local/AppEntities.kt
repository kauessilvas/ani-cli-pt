package dev.aniclipt.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "watch_history", primaryKeys = ["releaseId"], indices = [Index("lastWatchedMillis")])
data class WatchHistoryEntity(
    val releaseId: String,
    val workId: String,
    val slug: String,
    val title: String,
    val poster: String,
    val anilistId: Int?,
    val isAdult: Boolean?,
    val secondsWatched: Long,
    val lastEpisode: String,
    val lastEpisodeId: String,
    val lastEpisodeUid: String,
    val lastEpisodeNumber: String,
    val lastEpisodeTitle: String,
    val resumePositionMs: Long,
    val episodeDurationMs: Long,
    val lastWatchedMillis: Long,
)

@Entity(
    tableName = "completed_episodes",
    primaryKeys = ["releaseId", "episodeKey"],
    foreignKeys = [ForeignKey(entity = WatchHistoryEntity::class, parentColumns = ["releaseId"], childColumns = ["releaseId"], onDelete = ForeignKey.CASCADE)],
)
data class CompletedEpisodeEntity(val releaseId: String, val episodeKey: String)

@Entity(tableName = "watched_episodes", primaryKeys = ["releaseId", "episodeKey"])
data class WatchedEpisodeEntity(val releaseId: String, val episodeKey: String)

@Entity(tableName = "favorites", primaryKeys = ["releaseId"], indices = [Index("slug")])
data class FavoriteEntity(
    val releaseId: String,
    val workId: String,
    val slug: String,
    val title: String,
    val poster: String,
    val synopsis: String,
    val anilistId: Int?,
    val isAdult: Boolean?,
)

@Entity(
    tableName = "favorite_metadata",
    primaryKeys = ["releaseId", "position"],
    foreignKeys = [ForeignKey(entity = FavoriteEntity::class, parentColumns = ["releaseId"], childColumns = ["releaseId"], onDelete = ForeignKey.CASCADE)],
)
data class FavoriteMetadataEntity(val releaseId: String, val position: Int, val value: String)

@Entity(tableName = "identity_links", primaryKeys = ["key"], indices = [Index("releaseId"), Index("workId")])
data class IdentityLinkEntity(val key: String, val workId: String, val releaseId: String)

@Entity(tableName = "identity_aliases", primaryKeys = ["fromReleaseId"])
data class IdentityAliasEntity(val fromReleaseId: String, val toReleaseId: String)

@Entity(tableName = "cached_metadata", primaryKeys = ["slug"])
data class CachedMetadataEntity(
    val slug: String,
    val poster: String,
    val banner: String,
    val synopsis: String,
    val artworkAt: Long,
    val episodesAt: Long,
)

@Entity(
    tableName = "cached_episodes",
    primaryKeys = ["slug", "position"],
    foreignKeys = [ForeignKey(entity = CachedMetadataEntity::class, parentColumns = ["slug"], childColumns = ["slug"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("slug")],
)
data class CachedEpisodeEntity(
    val slug: String,
    val sourceEpisodeId: String,
    val number: String,
    val title: String,
    val originalTitle: String,
    val thumbnail: String,
    val episodeId: String,
    val position: Int,
)

@Entity(tableName = "favorite_episode_snapshots", primaryKeys = ["slug", "sourceEpisodeId"])
data class FavoriteEpisodeSnapshotEntity(val slug: String, val sourceEpisodeId: String)

@Entity(tableName = "favorite_episode_snapshot_state", primaryKeys = ["slug"])
data class FavoriteEpisodeSnapshotStateEntity(val slug: String, val capturedAt: Long)

@Entity(tableName = "recent_searches", primaryKeys = ["term"])
data class RecentSearchEntity(val term: String, val position: Int)

@Entity(tableName = "migration_markers", primaryKeys = ["name"])
data class MigrationMarkerEntity(val name: String, val completedAt: Long)
