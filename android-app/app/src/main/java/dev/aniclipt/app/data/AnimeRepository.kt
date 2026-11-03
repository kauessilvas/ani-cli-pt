package dev.aniclipt.app.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

data class Episode(val id: String, val number: String, val title: String, val originalTitle: String = title)
data class PlaybackSource(val videoUrl: String, val subtitleUrl: String?, val referer: String, val title: String, val episode: String, val audioLabel: String, val subtitleLabel: String, val availableVideoHeights: List<Int> = emptyList())

class AnimeRepository {
    suspend fun airing(): List<Anime> = withContext(Dispatchers.IO) { parseCatalog(fetch("$BASE/top-airing")) }

    suspend fun search(query: String): List<Anime> = withContext(Dispatchers.IO) {
        val aliases = titleSearchAliases[normalizeMatchText(query)].orEmpty()
        coroutineScope {
            (aliases + query).distinctBy(::normalizeMatchText).take(3).map { searchTerm ->
                async { searchHiAnime(searchTerm) }
            }.awaitAll().flatten().distinctBy { it.slug }.take(36)
        }
    }

    suspend fun validateOpenSubtitlesApiKey(apiKey: String): Boolean? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext false
        val request = runCatching { URL("$OPEN_SUBTITLES_BASE/infos/formats").openConnection() as HttpURLConnection }.getOrNull()
            ?: return@withContext null
        request.connectTimeout = 10_000
        request.readTimeout = 10_000
        request.setRequestProperty("Api-Key", apiKey)
        request.setRequestProperty("Accept", "application/json")
        request.setRequestProperty("User-Agent", OPEN_SUBTITLES_USER_AGENT)
        try {
            when (request.responseCode) {
                in 200..299 -> true
                401, 403 -> false
                else -> null
            }
        } catch (_: Exception) {
            null
        } finally {
            request.disconnect()
        }
    }

    private suspend fun searchHiAnime(query: String): List<Anime> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        return parseCatalog(fetch("$BASE/search?keyword=$encoded"))
    }

    suspend fun episodes(anime: Anime): List<Episode> = withContext(Dispatchers.IO) {
        val id = anime.slug.substringAfterLast('-')
        val json = org.json.JSONObject(fetch("$BASE/api/theme/episode/list/$id"))
        val listed = Jsoup.parseBodyFragment(json.optString("html")).select("a.ep-item[data-number]").mapNotNull { element ->
            val episodeId = element.attr("data-id")
            val number = element.attr("data-number")
            val title = element.attr("title").ifBlank { "Episódio $number" }
            if (episodeId.isBlank() || number.isBlank()) null else Episode(episodeId, number, title, title)
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

    suspend fun resolve(anime: Anime, episode: Episode, dubbed: Boolean, openSubtitlesEnabled: Boolean, openSubtitlesApiKey: String): PlaybackSource = withContext(Dispatchers.IO) {
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
        val playlist = fetch(playlistUrl, referer)
        val sourceFrameRate = Regex("FRAME-RATE=([0-9.]+)").find(playlist)?.groupValues?.get(1)?.toDoubleOrNull()
        val sourceTracks = json.optJSONArray("subtitles")
        val sourcePtBr = choosePtBrSubtitle(sourceTracks)
        val openSubtitle = if (sourcePtBr == null && openSubtitlesEnabled && openSubtitlesApiKey.isNotBlank()) {
            runCatching { findOpenSubtitlesPtBr(anime, episode, openSubtitlesApiKey, sourceFrameRate) }.getOrNull()
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
        val availableHeights = Regex("(?m)^#EXT-X-STREAM-INF:([^\\n]*)")
            .findAll(playlist)
            .mapNotNull { Regex("RESOLUTION=\\d+x(\\d+)").find(it.groupValues[1])?.groupValues?.get(1)?.toIntOrNull() }
            .distinct()
            .sortedDescending()
            .toList()
        PlaybackSource(playlistUrl, subtitle, referer, anime.title, episode.number, audioLabel, subtitleLabel, availableHeights)
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

    private data class ExternalSubtitle(val url: String, val durationSeconds: Double? = null)
    private data class OpenSubtitleCandidate(
        val fileId: Int,
        val titleScore: Double,
        val seriesScore: Double,
        val frameRateDifference: Double,
        val downloadCount: Int,
    )
    private class OpenSubtitlesApiException(val statusCode: Int) : Exception("OpenSubtitles HTTP $statusCode")

    private fun findOpenSubtitlesPtBr(anime: Anime, episode: Episode, apiKey: String, sourceFrameRate: Double?): ExternalSubtitle? =
        runCatching { findOpenSubtitlesPtBrOnce(anime, episode, apiKey, sourceFrameRate) }.getOrNull()

    private fun findOpenSubtitlesPtBrOnce(anime: Anime, episode: Episode, apiKey: String, sourceFrameRate: Double?): ExternalSubtitle? {
        val episodeNumber = episode.number.toDoubleOrNull()?.toInt() ?: return null
        val cleanTitle = anime.title.replace(Regex("(?i)\\b(?:season\\s*\\d+|\\d+(?:st|nd|rd|th)\\s+season|part\\s*\\d+)\\b"), " ").trim()
        val query = URLEncoder.encode(cleanTitle, Charsets.UTF_8.name())
        val season = Regex("(?i)(?:season\\s*(\\d+)|(\\d+)(?:st|nd|rd|th)\\s+season|\\bs\\s*(\\d+)\\b)")
            .find(anime.title)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.toIntOrNull()
        val seasonParam = season?.let { "&season_number=$it" }.orEmpty()
        val searchUrl = "$OPEN_SUBTITLES_BASE/subtitles?episode_number=$episodeNumber&languages=pt-br&order_by=download_count&order_direction=desc&query=$query&type=episode$seasonParam"
        val search = openSubtitlesRequest(searchUrl, apiKey)
        val rows = search.optJSONArray("data") ?: return null
        val episodeTitle = usefulEpisodeTitle(episode.originalTitle)
        val candidates = (0 until rows.length()).mapNotNull { index ->
            val attributes = rows.optJSONObject(index)?.optJSONObject("attributes") ?: return@mapNotNull null
            if (!attributes.optString("language").equals("pt-br", true)) return@mapNotNull null
            val details = attributes.optJSONObject("feature_details")
            val featureType = details?.optString("feature_type").orEmpty()
            if (featureType.isNotBlank() && !featureType.equals("episode", true)) return@mapNotNull null

            val release = attributes.optString("release")
            val fileArray = attributes.optJSONArray("files") ?: return@mapNotNull null
            val fileId = (0 until fileArray.length()).mapNotNull { fileArray.optJSONObject(it) }
                .firstOrNull()?.optInt("file_id", 0)?.takeIf { it > 0 } ?: return@mapNotNull null
            val fileName = (0 until fileArray.length()).mapNotNull { fileArray.optJSONObject(it) }
                .firstOrNull()?.optString("file_name").orEmpty()
            val releaseEpisode = parseSeasonEpisode("$release $fileName")
            val resultEpisode = details?.optInt("episode_number", 0)?.takeIf { it > 0 } ?: releaseEpisode?.second
            if (resultEpisode != null && resultEpisode != episodeNumber) return@mapNotNull null
            val resultSeason = details?.optInt("season_number", 0)?.takeIf { it > 0 } ?: releaseEpisode?.first
            if (season != null && resultSeason != null && resultSeason != season) return@mapNotNull null

            val seriesTitles = listOf(
                details?.optString("parent_title").orEmpty(),
                details?.optString("movie_name").orEmpty().replace(Regex("(?i)\\s*[-:]?\\s*S\\d{1,2}E\\d{1,3}.*$"), ""),
                release,
                fileName,
            ).filter { it.isNotBlank() }
            val seriesScore = seriesTitles.maxOfOrNull { titleSimilarity(anime.title, it) } ?: 0.0
            if (seriesScore < MIN_SERIES_TITLE_MATCH) return@mapNotNull null

            val candidateEpisodeTitle = details?.optString("title").orEmpty()
            val titleScore = if (episodeTitle.isBlank()) 0.0 else episodeTitleSimilarity(episodeTitle, candidateEpisodeTitle)
            val subtitleFps = attributes.optDouble("fps", 0.0).takeIf { it > 0.0 }
            val frameRateDifference = if (sourceFrameRate != null && subtitleFps != null) kotlin.math.abs(sourceFrameRate - subtitleFps) else Double.MAX_VALUE
            OpenSubtitleCandidate(
                fileId = fileId,
                titleScore = titleScore,
                seriesScore = seriesScore,
                frameRateDifference = frameRateDifference,
                downloadCount = attributes.optInt("download_count", 0),
            )
        }.sortedWith(
            compareByDescending<OpenSubtitleCandidate> { it.titleScore }
                .thenByDescending { it.seriesScore }
                .thenBy { it.frameRateDifference }
                .thenByDescending { it.downloadCount },
        )
        val candidate = candidates.firstOrNull() ?: return null
        val subtitle = requestSubtitleDownload(candidate.fileId, apiKey) ?: return null
        val expectedDuration = anime.metadata.mapNotNull(::runtimeSeconds).firstOrNull()
        val subtitleDuration = subtitle.durationSeconds
        if (expectedDuration != null && subtitleDuration != null &&
            subtitleDuration !in expectedDuration * MIN_SUBTITLE_DURATION_RATIO..expectedDuration * MAX_SUBTITLE_DURATION_RATIO
        ) return null
        return subtitle
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
            val link = org.json.JSONObject(body).optString("link").takeIf { it.startsWith("https://") } ?: return null
            ExternalSubtitle(link, fetchSubtitleDuration(link))
        } finally {
            request.disconnect()
        }
    }

    private fun fetchSubtitleDuration(url: String): Double? {
        val request = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull() ?: return null
        request.connectTimeout = 10_000
        request.readTimeout = 12_000
        request.setRequestProperty("Accept", "text/plain, */*")
        request.setRequestProperty("User-Agent", OPEN_SUBTITLES_USER_AGENT)
        val cuePattern = Regex("(\\d{2}:\\d{2}:\\d{2}[,.]\\d{3})\\s*-->\\s*(\\d{2}:\\d{2}:\\d{2}[,.]\\d{3})")
        return try {
            var consumedCharacters = 0
            var lastEnd: Double? = null
            request.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    consumedCharacters += line.length
                    if (consumedCharacters > MAX_SUBTITLE_INSPECTION_CHARACTERS) return@useLines
                    val times = cuePattern.find(line)?.groupValues ?: return@forEach
                    lastEnd = timestampSeconds(times[2])
                }
            }
            lastEnd
        } catch (_: Exception) {
            null
        } finally {
            request.disconnect()
        }
    }

    private fun timestampSeconds(value: String): Double? {
        val pieces = value.replace(',', '.').split(':')
        if (pieces.size != 3) return null
        val seconds = pieces[2].toDoubleOrNull() ?: return null
        return pieces[0].toIntOrNull()?.times(3600)?.plus(pieces[1].toIntOrNull()?.times(60) ?: return null)?.plus(seconds)
    }

    private fun runtimeSeconds(value: String): Double? {
        val match = Regex("(?i)\\b(\\d{1,3})\\s*(?:m|min|mins|minutes?)\\b").find(value) ?: return null
        return match.groupValues[1].toDoubleOrNull()?.times(60)
    }

    private fun usefulEpisodeTitle(value: String): String {
        val normalized = normalizeMatchText(value).replace(Regex("^(?:episode|ep|episodio)\\s*\\d+\\s*"), "").trim()
        return normalized.takeUnless { it.isBlank() || it.matches(Regex("\\d+")) }.orEmpty()
    }

    private fun titleSimilarity(first: String, second: String): Double {
        val firstTokens = normalizeMatchText(first).split(' ').filter { it.length > 1 }.toSet()
        val secondTokens = normalizeMatchText(second).split(' ').filter { it.length > 1 }.toSet()
        if (firstTokens.isEmpty() || secondTokens.isEmpty()) return 0.0
        val common = firstTokens.intersect(secondTokens).size.toDouble()
        return common / minOf(firstTokens.size, secondTokens.size)
    }

    private fun episodeTitleSimilarity(first: String, second: String): Double {
        val firstTokens = normalizeMatchText(first).split(' ').filter { it.length > 1 }.toSet()
        val secondTokens = normalizeMatchText(second).split(' ').filter { it.length > 1 }.toSet()
        if (firstTokens.isEmpty() || secondTokens.isEmpty()) return 0.0
        return firstTokens.intersect(secondTokens).size.toDouble() / maxOf(firstTokens.size, secondTokens.size)
    }

    private fun normalizeMatchText(value: String): String {
        val withoutMarks = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return withoutMarks.lowercase()
            .replace(Regex("(?i)\\b(?:s\\d{1,2}e\\d{1,3}|\\d{1,2}x\\d{1,3})\\b"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    private fun parseSeasonEpisode(value: String): Pair<Int, Int>? {
        val seasonEpisode = Regex("(?i)\\bS(\\d{1,2})E(\\d{1,3})\\b").find(value)
            ?: Regex("(?i)\\b(\\d{1,2})x(\\d{1,3})\\b").find(value)
            ?: return null
        return seasonEpisode.groupValues[1].toIntOrNull()?.let { season ->
            seasonEpisode.groupValues[2].toIntOrNull()?.let { episode -> season to episode }
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

    private suspend fun parseCatalog(html: String): List<Anime> = coroutineScope {
        val parsed = Jsoup.parse(html).select(".flw-item").mapNotNull { card ->
            val link = card.selectFirst("a.dynamic-name") ?: return@mapNotNull null
            val slug = URI(BASE).resolve(link.attr("href")).path.trim('/')
            val title = link.text().ifBlank { link.attr("title") }
            if (slug.isBlank() || title.isBlank()) return@mapNotNull null
            val image = card.selectFirst("img.film-poster-img")
            val poster = image?.attr("data-src").orEmpty().ifBlank { image?.attr("src").orEmpty() }
            Anime(slug, title, poster, synopsis = card.selectFirst(".description")?.text().orEmpty().take(500), metadata = card.select(".fdi-item").map { it.text() }.take(4))
        }.distinctBy { it.slug }.take(36)
        val semaphore = Semaphore(4)
        parsed.map { anime ->
            async { semaphore.withPermit { anime.copy(synopsis = translatePtBr(anime.synopsis)) } }
        }.awaitAll()
    }

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
        private const val OPEN_SUBTITLES_USER_AGENT = "ani-cli-pt v0.1.3"
        private const val MIN_SERIES_TITLE_MATCH = 0.5
        private const val MIN_SUBTITLE_DURATION_RATIO = 0.60
        private const val MAX_SUBTITLE_DURATION_RATIO = 1.15
        private const val MAX_SUBTITLE_INSPECTION_CHARACTERS = 2_000_000
        private val titleSearchAliases = mapOf(
            "os sete pecados capitais" to listOf("Nanatsu no Taizai", "The Seven Deadly Sins"),
            "sete pecados capitais" to listOf("Nanatsu no Taizai", "The Seven Deadly Sins"),
            "nanatsu no taizai" to listOf("The Seven Deadly Sins"),
            "the seven deadly sins" to listOf("Nanatsu no Taizai"),
            "demon slayer" to listOf("Kimetsu no Yaiba"),
            "matador de demonios" to listOf("Kimetsu no Yaiba", "Demon Slayer"),
            "ataque dos titas" to listOf("Shingeki no Kyojin", "Attack on Titan"),
            "ataque dos titans" to listOf("Shingeki no Kyojin", "Attack on Titan"),
            "shingeki no kyojin" to listOf("Attack on Titan"),
            "attack on titan" to listOf("Shingeki no Kyojin"),
            "caderno da morte" to listOf("Death Note"),
            "cacador x cacador" to listOf("Hunter x Hunter"),
            "hunter x hunter" to listOf("Hunter x Hunter"),
            "academia meu heroi" to listOf("Boku no Hero Academia", "My Hero Academia"),
            "meu heroi academia" to listOf("Boku no Hero Academia", "My Hero Academia"),
            "boku no hero academia" to listOf("My Hero Academia"),
            "a viagem de chihiro" to listOf("Sen to Chihiro no Kamikakushi", "Spirited Away"),
            "castelo animado" to listOf("Howl no Ugoku Shiro", "Howl's Moving Castle"),
            "meu vizinho totoro" to listOf("Tonari no Totoro", "My Neighbor Totoro"),
        )
        private val translationCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    }
}
