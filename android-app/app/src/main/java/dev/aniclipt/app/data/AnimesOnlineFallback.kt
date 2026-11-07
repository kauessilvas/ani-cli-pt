package dev.aniclipt.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

internal fun fallbackTitleMatches(requested: String, candidate: String): Boolean {
    fun key(value: String): String = matchingTitle(value.replace(Regex("\\s+(?:19|20)\\d{2}$"), ""), null)
    return key(requested) == key(candidate)
}

/** Playback-only provider. Its pages are never used as catalog or metadata. */
class AnimesOnlineFallback {
    suspend fun resolve(anime: Anime, episode: Episode, dubbed: Boolean): PlaybackSource = withContext(Dispatchers.IO) {
        val query = URLEncoder.encode(anime.title, Charsets.UTF_8.name())
        val search = Jsoup.parse(fetch("$BASE/?s=$query"), BASE)
        val candidates = search.select("div.result-item article, div.result-item").mapNotNull { card ->
            val link = card.selectFirst("div.title a[href]") ?: return@mapNotNull null
            val title = link.text()
            val url = link.absUrl("href")
            if (!fallbackTitleMatches(anime.title, title) || !isSiteUrl(url, "/anime/")) null else url
        }.distinct().sortedByDescending { url -> url.contains("dublado", true) == dubbed }.take(4)
        for (url in candidates) {
            val source = runCatching { resolvePage(url, anime, episode, dubbed) }.getOrNull()
            if (source != null) return@withContext source
        }
        error("Nenhum vídeo compatível encontrado na fonte de fallback.")
    }

    private fun resolvePage(url: String, anime: Anime, episode: Episode, dubbed: Boolean): PlaybackSource? {
        val details = Jsoup.parse(fetch(url), url)
        val episodeUrl = details.select("#episodes a[href]").firstNotNullOfOrNull { link ->
            val number = Regex("(?i)epis[oó]dio\\s*([0-9]+(?:[.,][0-9]+)?)").find(link.text())?.groupValues?.get(1)
                ?: Regex("(?i)episodio-([0-9]+(?:[.-][0-9]+)?)").find(link.absUrl("href"))?.groupValues?.get(1)
            link.absUrl("href").takeIf { number != null && sameEpisodeNumber(number.replace(',', '.'), episode.number) && isSiteUrl(it, "/episodio/") }
        } ?: return null
        val player = Jsoup.parse(fetch(episodeUrl), episodeUrl)
        val option = player.select(".dooplay_player_option[data-post][data-nume][data-type]").firstOrNull { item ->
            val label = item.selectFirst(".title")?.text().orEmpty()
            if (dubbed) label.contains("dublado", true) else label.contains("legendado", true) || label.contains("sub", true)
        } ?: return null
        val post = option.attr("data-post").toIntOrNull()?.takeIf { it > 0 } ?: return null
        val nume = option.attr("data-nume").toIntOrNull()?.takeIf { it > 0 } ?: return null
        val type = option.attr("data-type").takeIf { it in setOf("tv", "movie") } ?: return null
        val payload = JSONObject(fetch("$BASE/wp-json/dooplayer/v2/$post/$type/$nume", episodeUrl))
        val embed = payload.optString("embed_url")
        if (!isSiteUrl(embed, "/jwplayer")) return null
        val source = URI(embed).rawQuery?.split('&')?.firstOrNull { it.startsWith("source=") }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) } ?: return null
        val path = runCatching { URI(source).path.lowercase(java.util.Locale.ROOT) }.getOrNull() ?: return null
        if (!source.startsWith("https://") || !(path.endsWith(".mp4") || path.endsWith(".m3u8"))) return null
        val connection = URL(source).openConnection() as HttpURLConnection
        connection.requestMethod = "HEAD"
        connection.connectTimeout = 6_000
        connection.readTimeout = 6_000
        connection.setRequestProperty("Referer", "$BASE/")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        val available = try { connection.responseCode in 200..299 } finally { connection.disconnect() }
        if (!available) return null
        return PlaybackSource(source, null, "$BASE/", anime.title, episode.number,
            if (dubbed) "Dublado PT-BR" else "Áudio original", if (dubbed) "Áudio dublado" else "Legenda da fonte", providerSlug = PROVIDER_SLUG)
    }

    private fun isSiteUrl(url: String, pathPrefix: String): Boolean = runCatching {
        URI(url).let { it.scheme == "https" && it.host == "animesonline.cloud" && it.path.startsWith(pathPrefix) }
    }.getOrDefault(false)

    private fun fetch(url: String, referer: String = "$BASE/"): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 12_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Referer", referer)
        return try { connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } } finally { connection.disconnect() }
    }

    companion object {
        const val PROVIDER_SLUG = "fallback:animesonline.cloud"
        private const val BASE = "https://animesonline.cloud"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/126 Safari/537.36"
    }
}
