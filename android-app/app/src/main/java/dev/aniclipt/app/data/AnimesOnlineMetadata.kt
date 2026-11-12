package dev.aniclipt.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URL
import java.net.URLEncoder

internal data class OnlineEpisodeMetadata(val number: Int, val title: String, val still: String)
internal data class OnlineAnimeMetadata(val title: String, val poster: String, val synopsis: String, val episodes: List<OnlineEpisodeMetadata>)

private val onlineSuffix = Regex("(?i)\\s*[-–—]?\\s*(?:todos os epis[oó]dios|dublado|legendado)\\s*$")
private val wordpressSize = Regex("-\\d{2,4}x\\d{2,4}(?=\\.[a-zA-Z]{3,5}(?:$|\\?))")

internal fun onlineTitleKey(title: String): String {
    var value = title.trim()
    while (onlineSuffix.containsMatchIn(value)) value = value.replace(onlineSuffix, "").trim()
    return normalizeMatchText(value)
        .replace(Regex("\\b(\\d+)(?:st|nd|rd|th) season\\b"), "$1")
        .replace(Regex("\\b(?:season|temporada) (\\d+)\\b"), "$1")
        .trim()
}

internal fun parseOnlineMetadata(doc: Document): OnlineAnimeMetadata? {
    val title = doc.selectFirst(".sheader .data h1")?.text()?.trim().orEmpty()
    if (title.isBlank()) return null
    fun image(url: String): String {
        val clean = url.trim()
        val uri = runCatching { URI(clean) }.getOrNull() ?: return ""
        if (uri.scheme != "https" || uri.host != "animesonline.cloud" || !uri.path.startsWith("/wp-content/uploads/")) return ""
        return clean.replace(wordpressSize, "")
    }
    val poster = image(doc.selectFirst(".sheader .poster img")?.absUrl("src").orEmpty())
    val synopsis = doc.select(".wp-content p").firstOrNull { it.text().trim().startsWith("Sinopse:", true) }
        ?.text()?.substringAfter(':')?.trim().orEmpty()
    val episodes = doc.select("#episodes li").mapNotNull { row ->
        val link = row.selectFirst(".episodiotitle a[href]") ?: return@mapNotNull null
        val number = Regex("^\\s*\\d+\\s*-\\s*(\\d+)\\s*$").find(row.selectFirst(".numerando")?.text().orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        if (number < 1) return@mapNotNull null
        val text = link.text().trim().replace(Regex("(?i)^epis[oó]dio\\s*0*\\d+\\s*[-–—:]\\s*"), "").trim()
        val label = if (text.matches(Regex("(?i)^epis[oó]dio\\s*(?:0*\\d+|x)$"))) "" else text
        OnlineEpisodeMetadata(number, label, image(row.selectFirst(".imagen img")?.absUrl("src").orEmpty()))
    }.groupBy { it.number }.values.map { duplicates -> duplicates.maxBy { (if (it.title.isNotBlank()) 100 else 0) + (if (it.still.isNotBlank()) 10 else 0) + it.title.length } }
        .sortedBy { it.number }
    return OnlineAnimeMetadata(title, poster, synopsis, episodes)
}

/** Enrichment for an already selected anime; this provider never contributes catalog entries. */
internal class AnimesOnlineMetadata {
    private val searchCache = TimedRequestCache<String, List<Pair<String, String>>>(48, 30 * 60_000L, shouldCache = { it.isNotEmpty() })
    private val detailCache = TimedRequestCache<String, OnlineAnimeMetadata?>(48, 30 * 60_000L, shouldCache = { it != null })

    suspend fun find(anime: Anime, aliases: List<String>): OnlineAnimeMetadata? = withContext(Dispatchers.IO) {
        val accepted = (listOf(anime.title) + aliases).map(::onlineTitleKey).filter(String::isNotBlank).toSet()
        if (accepted.isEmpty()) return@withContext null
        for (query in (listOf(anime.title) + aliases).map(String::trim).filter(String::isNotBlank).distinctBy(::onlineTitleKey).take(4)) {
            val matches = searchCache.getOrLoad(query.lowercase()) { search(query) }
                .filter { (title, _) -> onlineTitleKey(title) in accepted }
                .distinctBy { it.second }
            // Dubbed and subtitled copies may share metadata. Any other ambiguity is unsafe.
            val groups = matches.groupBy { onlineTitleKey(it.first) }
            if (groups.size != 1) continue
            val variants = matches.sortedBy { it.first.contains("dublado", true) }.take(2).mapNotNull { (_, url) ->
                runCatching { detailCache.getOrLoad(url) { parseOnlineMetadata(Jsoup.parse(fetch(url), url)) } }.getOrNull()
            }.filter { onlineTitleKey(it.title) in accepted }
            val data = variants.maxByOrNull { variant -> variant.episodes.count { it.title.isNotBlank() && it.still.isNotBlank() } * 2 + variant.episodes.count { it.title.isNotBlank() || it.still.isNotBlank() } }
            if (data != null) return@withContext data
        }
        null
    }

    private fun search(query: String): List<Pair<String, String>> {
        val url = "$BASE/?s=${URLEncoder.encode(query, Charsets.UTF_8.name())}"
        return Jsoup.parse(fetch(url), BASE).select("div.result-item").mapNotNull { card ->
            val link = card.selectFirst("div.title a[href]") ?: return@mapNotNull null
            val href = link.absUrl("href")
            val uri = runCatching { URI(href) }.getOrNull()
            if (uri?.scheme != "https" || uri.host != "animesonline.cloud" || !uri.path.startsWith("/anime/")) null
            else link.text().trim() to href
        }.distinctBy { it.second }.take(24)
    }

    private fun fetch(url: String): String {
        return HttpTransport.text(url,
            headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/126 Safari/537.36"),
            connectTimeoutMillis = 7_000, readTimeoutMillis = 10_000)
    }

    companion object { private const val BASE = "https://animesonline.cloud" }
}
