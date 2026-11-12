package dev.aniclipt.app

import android.content.SharedPreferences
import dev.aniclipt.app.data.Anime
import dev.aniclipt.app.data.IdentityRegistry
import org.json.JSONArray
import org.json.JSONObject

/** Converts legacy slug keys once, retaining source slugs for playback and notification checks. */
internal fun migrateSavedIdentity(prefs: SharedPreferences, registry: IdentityRegistry) {
    if (prefs.getBoolean("saved_identity_v1", false)) return
    val favorites = runCatching { JSONArray(prefs.getString("favorite_items", "[]")) }.getOrDefault(JSONArray())
    val watchHistory = runCatching { JSONArray(prefs.getString("watch_history", "[]")) }.getOrDefault(JSONArray())
    val bySlug = mutableMapOf<String, String>()
    fun assign(row: JSONObject): String? {
        val slug = row.optString("slug").takeIf(String::isNotBlank) ?: return null
        val title = row.optString("title").takeIf(String::isNotBlank) ?: return null
        val anime = registry.assign(listOf(Anime(slug, title, anilistId = row.optInt("anilist_id").takeIf { it > 0 }))).first()
        row.put("work_id", anime.workId).put("release_id", anime.releaseId)
        bySlug[slug] = anime.releaseId
        return anime.releaseId
    }
    val newFavorites = mutableSetOf<String>()
    for (index in 0 until favorites.length()) favorites.optJSONObject(index)?.let { assign(it)?.let(newFavorites::add) }
    for (index in 0 until watchHistory.length()) watchHistory.optJSONObject(index)?.let(::assign)
    val oldFavorites = prefs.getStringSet("favorites", emptySet()).orEmpty()
    newFavorites += oldFavorites.map { bySlug[it] ?: it }
    val oldWatched = prefs.getString("history", "").orEmpty()
    val newWatched = oldWatched.split(',').filter(String::isNotBlank).map { entry ->
        val delimiter = entry.lastIndexOf(':')
        if (delimiter < 0) entry else {
            val slug = entry.substring(0, delimiter)
            val number = entry.substring(delimiter + 1)
            "${bySlug[slug] ?: slug}:$number"
        }
    }.distinct().joinToString(",")
    prefs.edit()
        .putString("favorite_items", favorites.toString())
        .putStringSet("favorites", newFavorites)
        .putString("watch_history", watchHistory.toString())
        .putString("history", newWatched)
        .putBoolean("saved_identity_v1", true)
        .apply()
}
