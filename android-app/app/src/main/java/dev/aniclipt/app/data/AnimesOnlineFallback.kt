package dev.aniclipt.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

internal fun fallbackTitleMatches(requested: String, candidate: String): Boolean {
    fun key(value: String): String = matchingTitle(value.replace(Regex("\\s+(?:19|20)\\d{2}$"), ""), null)
    return key(requested) == key(candidate)
}

private val fireSeasonSuffix = Regex("(?i)\\s*·\\s*temporada\\s*(\\d+)\\s*$")

/** AnimeFire splits a continuous show into seasons; AnimesOnline may keep them on one page. */
internal fun fallbackSearchTitle(anime: Anime): String {
    val slugSeason = Regex("#s(\\d+)$", RegexOption.IGNORE_CASE).find(anime.slug)
        ?.groupValues?.get(1)?.toIntOrNull()
    val titleSeason = fireSeasonSuffix.find(anime.title)?.groupValues?.get(1)?.toIntOrNull()
    return if (slugSeason != null && slugSeason == titleSeason)
        anime.title.replace(fireSeasonSuffix, "").trim()
    else anime.title
}

internal fun fallbackEpisodeUrl(details: Document, episode: Episode, season: Int?): String? {
    val rows = details.select("#episodes li").mapNotNull { row ->
        val link = row.selectFirst("a[href]") ?: return@mapNotNull null
        val url = link.absUrl("href")
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.scheme != "https" || uri.host != "animesonline.cloud" || !uri.path.startsWith("/episodio/")) return@mapNotNull null
        val numbering = Regex("^\\s*(\\d+)\\s*-\\s*(\\d+)\\s*$").matchEntire(row.selectFirst(".numerando")?.text().orEmpty())
        Triple(numbering?.groupValues?.get(1)?.toIntOrNull(), numbering?.groupValues?.get(2)?.toIntOrNull(), url)
    }
    if (season != null) {
        val index = episode.number.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val seasonRows = rows.filter { it.first == season && it.second != null }
        val firstGlobal = seasonRows.minOfOrNull { it.second!! } ?: return null
        return seasonRows.firstOrNull { it.second == firstGlobal + index - 1 }?.third
    }
    return rows.firstOrNull { (_, global, url) ->
        val number = global?.toString() ?: Regex("(?i)epis[oó]dio[-\\s]*([0-9]+(?:[.,][0-9]+)?)")
            .find(url)?.groupValues?.get(1)
        number != null && sameEpisodeNumber(number.replace(',', '.'), episode.number)
    }?.third
}

/** Playback fallback. Metadata lookup is isolated in AnimesOnlineMetadata. */
class AnimesOnlineFallback {
    suspend fun resolve(anime: Anime, episode: Episode, dubbed: Boolean): PlaybackSource = withContext(Dispatchers.IO) {
        val searchTitle = fallbackSearchTitle(anime)
        val query = URLEncoder.encode(searchTitle, Charsets.UTF_8.name())
        val search = Jsoup.parse(fetch("$BASE/?s=$query"), BASE)
        val candidates = search.select("div.result-item article, div.result-item").mapNotNull { card ->
            val link = card.selectFirst("div.title a[href]") ?: return@mapNotNull null
            val title = link.text()
            val url = link.absUrl("href")
            if (!fallbackTitleMatches(searchTitle, title) || !isSiteUrl(url, "/anime/")) null else title to url
        }.distinctBy { it.second }.sortedByDescending { (_, url) -> url.contains("dublado", true) == dubbed }.take(4)
        for ((title, url) in candidates) {
            val season = Regex("#s(\\d+)$", RegexOption.IGNORE_CASE).find(anime.slug)
                ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { number ->
                    !fallbackTitleMatches(anime.title, title) || number > 1 && explicitSeason(anime.title) == null
                }
            val source = runCatching { resolvePage(url, anime, episode, dubbed, season) }.getOrNull()
            if (source != null) return@withContext source
        }
        error("Nenhum vídeo compatível encontrado na fonte de fallback.")
    }

    private fun resolvePage(url: String, anime: Anime, episode: Episode, dubbed: Boolean, season: Int?): PlaybackSource? {
        val details = Jsoup.parse(fetch(url), url)
        val episodeUrl = fallbackEpisodeUrl(details, episode, season) ?: return null
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
        val available = HttpTransport.execute(source, mapOf("Referer" to "$BASE/", "User-Agent" to USER_AGENT),
            method = "HEAD", connectTimeoutMillis = 6_000, readTimeoutMillis = 6_000).use { it.isSuccessful }
        if (!available) return null
        return PlaybackSource(source, null, "$BASE/", anime.title, episode.number,
            if (dubbed) "Dublado PT-BR" else "Áudio original", if (dubbed) "Áudio dublado" else "Legenda da fonte", providerSlug = PROVIDER_SLUG)
    }

    private fun isSiteUrl(url: String, pathPrefix: String): Boolean = runCatching {
        URI(url).let { it.scheme == "https" && it.host == "animesonline.cloud" && it.path.startsWith(pathPrefix) }
    }.getOrDefault(false)

    private fun fetch(url: String, referer: String = "$BASE/"): String {
        return HttpTransport.text(url, mapOf("User-Agent" to USER_AGENT, "Referer" to referer),
            connectTimeoutMillis = 8_000, readTimeoutMillis = 12_000)
    }

    companion object {
        const val PROVIDER_SLUG = "fallback:animesonline.cloud"
        private const val BASE = "https://animesonline.cloud"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/126 Safari/537.36"
    }
}
