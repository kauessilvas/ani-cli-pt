package dev.aniclipt.app.data.local

import android.content.Context
import androidx.room.withTransaction
import dev.aniclipt.app.data.IdentityRegistry
import dev.aniclipt.app.migrateSavedIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Import once before switching every writer (including workers) to Room. Legacy keys remain untouched. */
class LegacyDataMigration(private val context: Context, private val database: AppDatabase = AppDatabase.get(context)) {
    suspend fun migrateIfNeeded() {
        val prefs = context.applicationContext.getSharedPreferences("ani-cli-pt", Context.MODE_PRIVATE)
        migrateSavedIdentity(prefs, IdentityRegistry(prefs))
        val dao = database.appDao()
        database.withTransaction {
            if (dao.migrationMarker(MARKER) != null) return@withTransaction

            val favorites = parseArray(prefs.getString("favorite_items", null))
            val savedSlugs = mutableSetOf<String>()
            for (index in 0 until favorites.length()) {
                val row = favorites.optJSONObject(index) ?: continue
                val slug = row.optString("slug").takeIf(String::isNotBlank) ?: continue
                val key = row.optString("release_id").ifBlank { slug }
                savedSlugs += slug
                savedSlugs += key
                dao.putFavorite(FavoriteEntity(
                    releaseId = key, workId = row.optString("work_id"), slug = slug,
                    title = row.optString("title").ifBlank { slug }, poster = row.optString("poster"),
                    synopsis = row.optString("synopsis"), anilistId = row.optInt("anilist_id").takeIf { it > 0 },
                    isAdult = row.optBooleanOrNull("is_adult"),
                ))
                val tags = row.optJSONArray("metadata") ?: JSONArray()
                dao.putFavoriteMetadata((0 until tags.length()).mapNotNull { position ->
                    tags.optString(position).takeIf(String::isNotBlank)?.let { FavoriteMetadataEntity(key, position, it) }
                })
            }
            // Some old installs had only the string set; preserve those favorites as best as possible.
            prefs.getStringSet("favorites", emptySet()).orEmpty().forEach { slug ->
                if (slug.isNotBlank() && slug !in savedSlugs && dao.favoriteById(slug) == null) {
                    dao.putFavorite(FavoriteEntity(slug, "", slug, slug.substringAfterLast('/').replace('-', ' '), "", "", null, null))
                }
            }

            val history = parseArray(prefs.getString("watch_history", null))
            for (index in 0 until history.length()) {
                val row = history.optJSONObject(index) ?: continue
                val slug = row.optString("slug").takeIf(String::isNotBlank) ?: continue
                val title = row.optString("title").takeIf(String::isNotBlank) ?: continue
                val key = row.optString("release_id").ifBlank { slug }
                dao.putWatchHistory(WatchHistoryEntity(
                    releaseId = key, workId = row.optString("work_id"), slug = slug, title = title,
                    poster = row.optString("poster"), anilistId = row.optInt("anilist_id").takeIf { it > 0 },
                    isAdult = row.optBooleanOrNull("is_adult"), secondsWatched = row.optLong("seconds"),
                    lastEpisode = row.optString("episode"), lastEpisodeId = row.optString("episode_id"),
                    lastEpisodeUid = row.optString("episode_uid"), lastEpisodeNumber = row.optString("episode_number").ifBlank {
                        Regex("(?:epis[oó]dio|episode)\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
                            .find(row.optString("episode"))?.groupValues?.getOrNull(1).orEmpty()
                    },
                    lastEpisodeTitle = row.optString("episode_title"), resumePositionMs = row.optLong("position_ms"),
                    episodeDurationMs = row.optLong("duration_ms"), lastWatchedMillis = row.optLong("watched_at"),
                ))
                val completed = row.optJSONArray("completed_episodes") ?: JSONArray()
                dao.putCompletedEpisodes((0 until completed.length()).mapNotNull { offset ->
                    completed.optString(offset).takeIf(String::isNotBlank)?.let { CompletedEpisodeEntity(key, it) }
                })
            }
            val watched = prefs.getString("history", "").orEmpty().split(',').mapNotNull { item ->
                val separator = item.lastIndexOf(':')
                if (separator <= 0 || separator == item.lastIndex) null
                else WatchedEpisodeEntity(item.substring(0, separator), item.substring(separator + 1))
            }
            dao.putWatchedEpisodes(watched)

            val links = parseArray(prefs.getString("identity_links_v1", null))
            dao.putIdentityLinks((0 until links.length()).mapNotNull { index ->
                links.optJSONObject(index)?.let { row ->
                    val key = row.optString("key")
                    if (key.isBlank()) null else IdentityLinkEntity(key, row.optString("work"), row.optString("release"))
                }
            })
            val aliases = parseArray(prefs.getString("identity_aliases_v1", null))
            dao.putIdentityAliases((0 until aliases.length()).mapNotNull { index ->
                aliases.optJSONObject(index)?.let { row ->
                    val from = row.optString("from")
                    if (from.isBlank()) null else IdentityAliasEntity(from, row.optString("to"))
                }
            })

            val cacheIndex = parseArray(prefs.getString("display_metadata_index_v1", null))
            for (index in 0 until cacheIndex.length()) {
                val slug = cacheIndex.optString(index).takeIf(String::isNotBlank) ?: continue
                val cacheKey = "display_metadata_v1_${UUID.nameUUIDFromBytes(slug.toByteArray())}"
                val row = runCatching { JSONObject(prefs.getString(cacheKey, null) ?: return@runCatching null) }.getOrNull() ?: continue
                if (row.optString("slug") != slug) continue
                dao.putCachedMetadata(CachedMetadataEntity(slug, row.optString("poster"), row.optString("banner"),
                    row.optString("synopsis"), row.optLong("artworkAt"), row.optLong("episodesAt")))
                val episodes = row.optJSONArray("episodes") ?: JSONArray()
                dao.putCachedEpisodes((0 until episodes.length()).mapNotNull { position ->
                    val episode = episodes.optJSONObject(position) ?: return@mapNotNull null
                    val id = episode.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
                    CachedEpisodeEntity(slug, id, episode.optString("number"), episode.optString("title"),
                        episode.optString("original"), episode.optString("thumbnail"), episode.optString("episodeId"), position)
                })
            }

            val recent = parseArray(prefs.getString("search_history", null))
            dao.putRecentSearches((0 until recent.length()).mapNotNull { index ->
                recent.optString(index).takeIf(String::isNotBlank)?.let { RecentSearchEntity(it, index) }
            })
            prefs.all.filterKeys { it.startsWith("episode_snapshot:") }.forEach { (key, value) ->
                val slug = key.removePrefix("episode_snapshot:")
                val episodes = parseArray(value as? String)
                dao.putFavoriteEpisodeSnapshot((0 until episodes.length()).mapNotNull { index ->
                    episodes.optString(index).takeIf(String::isNotBlank)?.let { FavoriteEpisodeSnapshotEntity(slug, it) }
                })
                dao.putFavoriteEpisodeSnapshotState(FavoriteEpisodeSnapshotStateEntity(slug, System.currentTimeMillis()))
            }
            dao.putMigrationMarker(MigrationMarkerEntity(MARKER, System.currentTimeMillis()))
        }
    }

    private fun parseArray(raw: String?): JSONArray = runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())

    private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
        if (has(key) && !isNull(key)) optBoolean(key) else null

    companion object { private const val MARKER = "shared_prefs_v1" }
}
