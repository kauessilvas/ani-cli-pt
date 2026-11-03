package dev.aniclipt.app.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

data class Anime(
    val slug: String,
    val title: String,
    val poster: String = "",
    val banner: String = "",
    val synopsis: String = "",
    val metadata: List<String> = emptyList(),
)

data class Episode(val id: String, val number: String, val title: String)
data class PlaybackSource(val videoUrl: String, val subtitleUrl: String?, val referer: String, val title: String, val episode: String, val audioLabel: String, val subtitleLabel: String)

class AnimeRepository {
    suspend fun airing(): List<Anime> = withContext(Dispatchers.IO) { parseCatalog(fetch("$BASE/top-airing")) }

    suspend fun search(query: String): List<Anime> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        parseCatalog(fetch("$BASE/search?keyword=$encoded"))
    }

    suspend fun episodes(anime: Anime): List<Episode> = withContext(Dispatchers.IO) {
        val id = anime.slug.substringAfterLast('-')
        val json = org.json.JSONObject(fetch("$BASE/api/theme/episode/list/$id"))
        val listed = Jsoup.parseBodyFragment(json.optString("html")).select("a.ep-item[data-number]").mapNotNull { element ->
            val episodeId = element.attr("data-id")
            val number = element.attr("data-number")
            if (episodeId.isBlank() || number.isBlank()) null else Episode(episodeId, number, element.attr("title").ifBlank { "Episódio $number" })
        }
        val semaphore = Semaphore(4)
        listed.map { episode -> async {
            val genericTitle = Regex("^(episode|epis[oó]dio)\\s*${Regex.escape(episode.number)}$", RegexOption.IGNORE_CASE)
            if (genericTitle.matches(episode.title)) episode.copy(title = "Episódio ${episode.number}")
            else semaphore.withPermit { episode.copy(title = translatePtBr(episode.title)) }
        } }.awaitAll()
    }

    suspend fun seasons(anime: Anime): List<Anime> = withContext(Dispatchers.IO) {
        val doc = Jsoup.parse(fetch("$BASE/${anime.slug}"))
        val relatedSection = doc.select("section").firstOrNull { section ->
            section.selectFirst(".cat-heading")?.text()?.contains("Related Anime", ignoreCase = true) == true
        }
        val related = relatedSection?.select("a.dynamic-name").orEmpty().mapNotNull { link ->
            val slug = URI(BASE).resolve(link.attr("href")).path.trim('/')
            val title = link.text().ifBlank { link.attr("title") }
            if (slug.isBlank() || title.isBlank()) return@mapNotNull null
            val detail = link.closest(".film-detail")
            val infoText = detail?.selectFirst(".fd-infor")?.text().orEmpty()
            if (!Regex("\\bTV\\b", RegexOption.IGNORE_CASE).containsMatchIn(infoText)) return@mapNotNull null
            val metadata = detail?.select(".fdi-item")?.map { it.text() }.orEmpty().ifEmpty { listOf("TV") }
            Anime(slug, title, metadata = metadata)
        }
        val base = seasonFamily(anime.title)
        (listOf(anime) + related)
            .distinctBy { it.slug }
            .filter { candidate -> seasonFamily(candidate.title) == base }
            .sortedWith(compareBy<Anime> { seasonNumber(it.title) ?: 1 }.thenBy { it.slug })
    }

    suspend fun artwork(anime: Anime): Anime = withContext(Dispatchers.IO) {
        val doc = Jsoup.parse(fetch("$BASE/${anime.slug}"))
        val bannerStyle = doc.selectFirst(".anis-cover")?.attr("style").orEmpty()
        val banner = Regex("url\\(['\\\"]?(.*?)['\\\"]?\\)").find(bannerStyle)?.groupValues?.get(1)?.let { URI("$BASE/").resolve(it).toString() }.orEmpty()
        val poster = doc.selectFirst("img.film-poster-img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }.orEmpty()
        val synopsis = doc.selectFirst(".film-description .text, .film-description, .description")?.text()?.take(500).orEmpty()
        anime.copy(banner = banner, poster = poster.ifBlank { anime.poster }, synopsis = if (synopsis.isBlank()) anime.synopsis else translatePtBr(synopsis))
    }

    suspend fun resolve(anime: Anime, episode: Episode, dubbed: Boolean, quality: String, openSubtitlesEnabled: Boolean, openSubtitlesApiKey: String): PlaybackSource = withContext(Dispatchers.IO) {
        val serverResponse = fetch("$BASE/api/theme/episode/servers?episodeId=${episode.id}")
        val serverHtml = runCatching { org.json.JSONObject(serverResponse).optString("html") }.getOrDefault("").ifBlank { serverResponse }
        val serversDoc = Jsoup.parse(serverHtml)
        val servers = serversDoc.select(".server-item")
        val ptBrDub = servers.firstOrNull { server ->
            server.attr("data-type").equals("dub", true) && server.attr("data-server-name").equals("ZokoAnime", true) && Regex("pt[-_ ]?br|brazil|brasil", RegexOption.IGNORE_CASE)
                .containsMatchIn(listOf("data-lang", "data-language", "data-label", "title", "data-server-name").joinToString(" ") { server.attr(it) } + " " + server.text())
        }
        val originalServer = servers.firstOrNull { server ->
            server.attr("data-type").equals("sub", true) && server.attr("data-server-name").equals("ZokoAnime", true)
        }
        val server = (if (dubbed) ptBrDub ?: originalServer else originalServer)
            ?: error(if (dubbed) "Não há áudio PT-BR nem áudio original disponível para este episódio." else "A fonte compatível não está disponível para este episódio.")
        val audioLabel = when {
            dubbed && ptBrDub != null -> "Dublado PT-BR"
            dubbed -> "Áudio original · dublagem PT-BR indisponível"
            else -> "Áudio original"
        }
        val embed = Base64.decode(server.attr("data-hash"), Base64.DEFAULT).toString(Charsets.UTF_8)
        val referer = URI(embed).resolve("/").toString()
        val embedHtml = fetch(embed, referer)
        val blob = Regex("window\\.__P=\"([^\"]+)\"").find(embedHtml)?.groupValues?.get(1)
            ?: error("Não foi possível ler a configuração da fonte.")
        val json = org.json.JSONObject(deobfuscate(blob))
        val playlistUrl = json.optString("src").takeIf { it.contains(".m3u8") }
            ?: error("A fonte não forneceu uma playlist HLS.")
        val sourceTracks = json.optJSONArray("subtitles")
        val sourcePtBr = choosePtBrSubtitle(sourceTracks)
        val openSubtitle = if (sourcePtBr == null && openSubtitlesEnabled && openSubtitlesApiKey.isNotBlank()) {
            runCatching { findOpenSubtitlesPtBr(anime, episode, openSubtitlesApiKey) }.getOrNull()
        } else null
        val sourceEnglish = if (sourcePtBr == null && openSubtitle == null) chooseEnglishSubtitle(sourceTracks) else null
        val subtitle = sourcePtBr ?: openSubtitle?.url ?: sourceEnglish
        val subtitleLabel = when {
            sourcePtBr != null -> "Legenda PT-BR · fonte do vídeo"
            openSubtitle != null -> "Legenda PT-BR · OpenSubtitles"
            sourceEnglish != null -> "Legenda em inglês · fallback"
            !openSubtitlesEnabled -> "Busca OpenSubtitles desativada"
            openSubtitlesApiKey.isBlank() -> "Sem PT-BR · configure OpenSubtitles"
            else -> "Sem legenda disponível"
        }
        val playlist = fetch(playlistUrl, referer)
        val variants = Regex("(?m)^#EXT-X-STREAM-INF:.*?(?:RESOLUTION=\\d+x)?(\\d{3,4})?.*\\n([^#\\n][^\\n]*)")
            .findAll(playlist).mapNotNull { match ->
                val line = match.groupValues[2].trim()
                val height = match.groupValues[1].toIntOrNull() ?: Regex("RESOLUTION=\\d+x(\\d+)").find(match.value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (line.isBlank()) null else height to URI(playlistUrl).resolve(line).toString()
            }.toList()
        val selected = when (quality) {
            "best" -> variants.maxByOrNull { it.first }
            "worst" -> variants.minByOrNull { it.first }
            else -> variants.minByOrNull { kotlin.math.abs(it.first - quality.removeSuffix("p").toInt()) }
        } ?: (0 to playlistUrl)
        PlaybackSource(selected.second, subtitle, referer, anime.title, episode.number, audioLabel, subtitleLabel)
    }

    private fun choosePtBrSubtitle(tracks: org.json.JSONArray?): String? {
        if (tracks == null) return null
        val entries = (0 until tracks.length()).mapNotNull { tracks.optJSONObject(it) }
        val ptBr = Regex("pt[-_ ]?br|brazil|brasileir|\\bpob\\b", RegexOption.IGNORE_CASE)
        return entries.firstOrNull { track -> ptBr.containsMatchIn(track.optString("lang") + " " + track.optString("label") + " " + track.optString("src")) }
            ?.optString("src")?.takeIf { it.isNotBlank() }
    }

    private fun chooseEnglishSubtitle(tracks: org.json.JSONArray?): String? {
        if (tracks == null) return null
        val entries = (0 until tracks.length()).mapNotNull { tracks.optJSONObject(it) }
        val english = Regex("english|ingl[eê]s|\\beng\\b|/eng[-_.][^ ]*\\.vtt|eng-[0-9]+\\.vtt", RegexOption.IGNORE_CASE)
        return entries.firstOrNull { track -> english.containsMatchIn(track.optString("lang") + " " + track.optString("label") + " " + track.optString("src")) }
            ?.optString("src")?.takeIf { it.isNotBlank() }
            ?: entries.firstOrNull { it.optBoolean("default") }?.optString("src")?.takeIf { it.isNotBlank() }
    }

    private data class ExternalSubtitle(val url: String)
    private class OpenSubtitlesApiException(val statusCode: Int) : Exception("OpenSubtitles HTTP $statusCode")

    private fun findOpenSubtitlesPtBr(anime: Anime, episode: Episode, apiKey: String): ExternalSubtitle? =
        runCatching { findOpenSubtitlesPtBrOnce(anime, episode, apiKey) }.getOrNull()

    private fun findOpenSubtitlesPtBrOnce(anime: Anime, episode: Episode, apiKey: String): ExternalSubtitle? {
        val episodeNumber = episode.number.toDoubleOrNull()?.toInt() ?: return null
        val cleanTitle = anime.title.replace(Regex("(?i)\\b(?:season\\s*\\d+|\\d+(?:st|nd|rd|th)\\s+season|part\\s*\\d+)\\b"), " ").trim()
        val query = URLEncoder.encode(cleanTitle, Charsets.UTF_8.name())
        val season = Regex("(?i)(?:season\\s*(\\d+)|(\\d+)(?:st|nd|rd|th)\\s+season)")
            .find(anime.title)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.toIntOrNull()
        val seasonParam = season?.let { "&season_number=$it" }.orEmpty()
        val searchUrl = "$OPEN_SUBTITLES_BASE/subtitles?episode_number=$episodeNumber&languages=pt-br&order_by=download_count&order_direction=desc&query=$query&type=episode$seasonParam"
        val search = openSubtitlesRequest(searchUrl, apiKey)
        val rows = search.optJSONArray("data") ?: return null
        val candidate = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            .filter { row -> row.optJSONObject("attributes")?.optString("language")?.equals("pt-br", true) == true }
            .sortedByDescending { it.optJSONObject("attributes")?.optInt("download_count", 0) ?: 0 }
            .firstOrNull() ?: return null
        val files = candidate.optJSONObject("attributes")?.optJSONArray("files") ?: return null
        val fileId = (0 until files.length()).mapNotNull { files.optJSONObject(it) }
            .firstOrNull()?.optInt("file_id", 0)?.takeIf { it > 0 } ?: return null
        return requestSubtitleDownload(fileId, apiKey)
    }

    private fun requestSubtitleDownload(fileId: Int, apiKey: String): ExternalSubtitle? {
        val request = URL("$OPEN_SUBTITLES_BASE/download").openConnection() as HttpURLConnection
        request.requestMethod = "POST"
        request.connectTimeout = 12_000
        request.readTimeout = 20_000
        request.doOutput = true
        request.setRequestProperty("Api-Key", apiKey)
        request.setRequestProperty("Content-Type", "application/json")
        request.setRequestProperty("Accept", "application/json")
        request.setRequestProperty("User-Agent", OPEN_SUBTITLES_USER_AGENT)
        return try {
            request.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write("{\"file_id\":$fileId,\"sub_format\":\"srt\"}") }
            val code = request.responseCode
            val body = (if (code in 200..299) request.inputStream else request.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw OpenSubtitlesApiException(code)
            org.json.JSONObject(body).optString("link").takeIf { it.startsWith("https://") }?.let(::ExternalSubtitle)
        } finally {
            request.disconnect()
        }
    }

    private fun openSubtitlesRequest(url: String, apiKey: String): org.json.JSONObject {
        val request = URL(url).openConnection() as HttpURLConnection
        request.connectTimeout = 12_000
        request.readTimeout = 20_000
        request.setRequestProperty("Api-Key", apiKey)
        request.setRequestProperty("Accept", "application/json")
        request.setRequestProperty("User-Agent", OPEN_SUBTITLES_USER_AGENT)
        return try {
            val code = request.responseCode
            val body = (if (code in 200..299) request.inputStream else request.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw OpenSubtitlesApiException(code) else org.json.JSONObject(body)
        } finally {
            request.disconnect()
        }
    }

    private fun parseCatalog(html: String): List<Anime> = Jsoup.parse(html).select(".flw-item").mapNotNull { card ->
        val link = card.selectFirst("a.dynamic-name") ?: return@mapNotNull null
        val slug = URI(BASE).resolve(link.attr("href")).path.trim('/')
        val title = link.text().ifBlank { link.attr("title") }
        if (slug.isBlank() || title.isBlank()) return@mapNotNull null
        val image = card.selectFirst("img.film-poster-img")
        val poster = image?.attr("data-src").orEmpty().ifBlank { image?.attr("src").orEmpty() }
        Anime(slug, title, poster, synopsis = card.selectFirst(".description")?.text().orEmpty().take(500), metadata = card.select(".fdi-item").map { it.text() }.take(4))
    }.distinctBy { it.slug }.take(36)

    private fun translatePtBr(text: String): String {
        if (text.isBlank() || text.length > 500) return text
        translationCache[text]?.let { return it }
        return runCatching {
            val query = URLEncoder.encode(text, Charsets.UTF_8.name())
            val response = org.json.JSONObject(fetch("https://api.mymemory.translated.net/get?q=$query&langpair=en|pt-BR", "https://mymemory.translated.net/"))
            val translated = response.optJSONObject("responseData")?.optString("translatedText").orEmpty()
            (if (response.optInt("responseStatus") == 200 && translated.isNotBlank()) translated else text).also { translationCache[text] = it }
        }.getOrDefault(text)
    }

    private fun seasonFamily(title: String): String = title.lowercase()
        .replace(Regex("\\bthe\\s+final\\s+season\\b"), " ")
        .replace(Regex("\\b\\d+(?:st|nd|rd|th)\\s+season\\b"), " ")
        .replace(Regex("\\bseason\\s*\\d+\\b"), " ")
        .replace(Regex("\\bpart\\s*\\d+\\b"), " ")
        .replace(Regex("\\bcour\\s*\\d+\\b"), " ")
        .replace(Regex("\\bs\\s*\\d+\\b"), " ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun seasonNumber(title: String): Int? = Regex("(?:season\\s*(\\d+)|(\\d+)(?:st|nd|rd|th)\\s+season|\\bs\\s*(\\d+)\\b)", RegexOption.IGNORE_CASE)
        .find(title)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.toIntOrNull()

    private fun deobfuscate(blob: String): String {
        val bytes = Base64.decode(blob, Base64.DEFAULT)
        val key = "otaku-embed-v1".encodeToByteArray()
        return String(bytes.mapIndexed { index, byte -> (byte.toInt() xor key[index % key.size].toInt()).toByte() }.toByteArray(), Charsets.UTF_8)
    }

    private fun fetch(url: String, referer: String = "$BASE/"): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Referer", referer)
        connection.setRequestProperty("Accept", "text/html,application/json,*/*")
        connection.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.7")
        return try { connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } } finally { connection.disconnect() }
    }

    companion object {
        const val BASE = "https://hianime.at"
        private const val OPEN_SUBTITLES_BASE = "https://api.opensubtitles.com/api/v1"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private const val OPEN_SUBTITLES_USER_AGENT = "ani-cli-pt v0.1.2"
        private val translationCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    }
}
