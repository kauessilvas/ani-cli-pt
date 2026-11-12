package dev.aniclipt.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

data class SkipInterval(val type: String, val startMs: Long, val endMs: Long) {
    val label: String get() = if (type == "op") "Pular abertura" else "Pular encerramento"
}

internal fun activeSkipInterval(intervals: List<SkipInterval>, positionMs: Long, durationMs: Long): SkipInterval? =
    intervals.firstOrNull { interval ->
        interval.startMs >= 0 && interval.endMs > interval.startMs &&
            (durationMs <= 0 || interval.endMs <= durationMs + 5_000) &&
            positionMs in interval.startMs until interval.endMs
    }

class AniSkipRepository {
    suspend fun intervals(anime: Anime, episode: Episode): List<SkipInterval> = withContext(Dispatchers.IO) {
        val episodeNumber = episode.number.toIntOrNull()?.takeIf { it > 0 } ?: return@withContext emptyList()
        val malId = resolveMalId(anime) ?: return@withContext emptyList()
        val response = fetch("https://api.aniskip.com/v1/skip-times/$malId/$episodeNumber?types=op&types=ed")
        val payload = JSONObject(response)
        if (!payload.optBoolean("found")) return@withContext emptyList()
        val results = payload.optJSONArray("results") ?: return@withContext emptyList()
        (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val type = item.optString("skip_type")
            if (type != "op" && type != "ed") return@mapNotNull null
            val interval = item.optJSONObject("interval") ?: return@mapNotNull null
            val start = interval.optDouble("start_time")
            val end = interval.optDouble("end_time")
            if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start) return@mapNotNull null
            SkipInterval(type, (start * 1000).toLong(), (end * 1000).toLong())
        }.sortedBy { it.startMs }
    }

    private fun resolveMalId(anime: Anime): Int? {
        val media = if (anime.anilistId != null) {
            graphql("query (\$id: Int) { Media(id: \$id, type: ANIME) { idMal } }", JSONObject().put("id", anime.anilistId))
                .optJSONObject("data")?.optJSONObject("Media")
        } else {
            val rows = graphql("query (\$search: String) { Page(perPage: 8) { media(search: \$search, type: ANIME) { idMal format title { romaji english native } } } }", JSONObject().put("search", anime.title))
                .optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")
            (0 until (rows?.length() ?: 0)).mapNotNull { rows?.optJSONObject(it) }
                .firstOrNull { item ->
                    val title = item.optJSONObject("title") ?: return@firstOrNull false
                    identityScore(anime.title, item.optString("format"),
                        listOf(title.optString("romaji"), title.optString("english"), title.optString("native")),
                        explicitFormat(anime.title, anime.metadata)) != null
                }
        }
        return media?.optInt("idMal")?.takeIf { it > 0 }
    }

    private fun graphql(query: String, variables: JSONObject): JSONObject {
        return HttpTransport.execute(
            url = "https://graphql.anilist.co", method = "POST",
            body = JSONObject().put("query", query).put("variables", variables).toString(),
            connectTimeoutMillis = 8_000, readTimeoutMillis = 10_000,
        ).use { response ->
            if (!response.isSuccessful) JSONObject() else JSONObject(response.body?.string().orEmpty())
        }
    }

    private fun fetch(url: String): String {
        return HttpTransport.execute(url, connectTimeoutMillis = 8_000, readTimeoutMillis = 10_000).use { response ->
            if (!response.isSuccessful) "{}" else response.body?.string().orEmpty()
        }
    }
}
