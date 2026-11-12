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
import java.net.URI
import java.net.URL
import java.net.URLEncoder

internal data class VerifiedArtwork(val poster: String, val banner: String)

class HiAnimeRepository {
    private val titleTranslationSemaphore = Semaphore(3)
    private val detailPageCache = TimedRequestCache<String, String>(maxEntries = 32, ttlMillis = 10 * 60_000L, shouldCache = String::isNotBlank)
    private data class TmdbMatch(val id: Int, val artwork: VerifiedArtwork, val overview: String)
    private val tmdbMatchCache = TimedRequestCache<String, TmdbMatch?>(maxEntries = 128, ttlMillis = 60 * 60_000L, shouldCache = { it != null })
    private val tmdbSeasonCache = TimedRequestCache<String, List<EpisodeMetadata>>(maxEntries = 64, ttlMillis = 60 * 60_000L, shouldCache = { it.isNotEmpty() })
    private val tmdbPosterCache = TimedRequestCache<String, String>(maxEntries = 64, ttlMillis = 60 * 60_000L, shouldCache = String::isNotBlank)

    private suspend fun detailPage(slug: String): String = detailPageCache.getOrLoad(slug) { fetch("$BASE/$slug") }

    fun searchAliases(query: String): List<String> = titleSearchAliases[normalizeMatchText(query)].orEmpty()

    suspend fun airing(): List<Anime> = withContext(Dispatchers.IO) {
        parseCatalog(fetch("$BASE/top-airing"))
    }

    suspend fun popular(): List<Anime> = withContext(Dispatchers.IO) {
        parseCatalog(fetch("$BASE/most-popular"))
    }

    suspend fun search(query: String): List<Anime> = withContext(Dispatchers.IO) {
        val aliases = titleSearchAliases[normalizeMatchText(query)].orEmpty()
        coroutineScope {
            val catalogs = (aliases + query).distinctBy(::normalizeMatchText).take(3).map { searchTerm ->
                async {
                    val encoded = URLEncoder.encode(searchTerm, Charsets.UTF_8.name())
                    parseCatalog(fetch("$BASE/search?keyword=$encoded"))
                }
            }.awaitAll().flatten().distinctBy { it.slug }.take(36)
            catalogs
        }
    }

    suspend fun validateOpenSubtitlesApiKey(apiKey: String): Boolean? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext false
        try {
            HttpTransport.execute("$OPEN_SUBTITLES_BASE/infos/formats", mapOf(
                "Api-Key" to apiKey, "Accept" to "application/json", "User-Agent" to OPEN_SUBTITLES_USER_AGENT,
            ), connectTimeoutMillis = 10_000, readTimeoutMillis = 10_000).use { response -> when (response.code) {
                in 200..299 -> true
                401, 403 -> false
                else -> null
            } }
        } catch (_: Exception) {
            null
        }
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
        listed
    }

    suspend fun localizeEpisodeTitle(episode: Episode): Episode = withContext(Dispatchers.IO) {
        val original = episode.originalTitle.ifBlank { episode.title }
        val genericTitle = Regex("^(episode|epis[oó]dio)\\s*${Regex.escape(episode.number)}$", RegexOption.IGNORE_CASE)
        if (genericTitle.matches(original)) return@withContext episode.copy(title = "Episódio ${episode.number}", originalTitle = original)
        val translated = titleTranslationSemaphore.withPermit { translatePtBr(original) }
        episode.copy(title = translated, originalTitle = original)
    }

    internal suspend fun findTmdbArtwork(anime: Anime): VerifiedArtwork? = findTmdbMatch(anime)?.let { verifiedArtwork(it, anime) }

    private suspend fun verifiedArtwork(match: TmdbMatch, anime: Anime): VerifiedArtwork {
        val season = explicitSeason(anime.title, anime.slug) ?: return match.artwork
        if (season <= 1) return match.artwork
        val poster = runCatching { tmdbPosterCache.getOrLoad("${match.id}:$season") {
            val key = TMDB_KEY_BYTES.map { (it xor 83).toChar() }.joinToString("")
            val apiKey = URLEncoder.encode(key, Charsets.UTF_8.name())
            val row = org.json.JSONObject(fetch("https://api.themoviedb.org/3/tv/${match.id}/season/$season?api_key=$apiKey&language=pt-BR", "https://www.themoviedb.org/"))
            row.optString("poster_path").takeIf { it.startsWith("/") && !it.startsWith("//") }?.let { "https://image.tmdb.org/t/p/w780$it" }.orEmpty()
        } }.getOrDefault("")
        return VerifiedArtwork(poster, match.artwork.banner)
    }

    internal suspend fun enrichEpisodes(anime: Anime, episodes: List<Episode>): List<Episode> = withContext(Dispatchers.IO) {
        if (isFinalSeason(anime.title) || explicitFormat(anime.title, anime.metadata) == "MOVIE") return@withContext episodes
        val match = findTmdbMatch(anime) ?: return@withContext episodes
        val season = explicitSeason(anime.title, anime.slug) ?: 1
        val english = runCatching { tmdbSeasonEpisodes(match.id, season, "en-US") }.getOrDefault(emptyList())
        val portuguese = runCatching { tmdbSeasonEpisodes(match.id, season, "pt-BR") }.getOrDefault(emptyList())
        val enByNumber = english.associateBy { it.number }
        mergeVerifiedEpisodeMetadata(episodes, english, portuguese.map { item ->
            item.copy(still = item.still.ifBlank { enByNumber[item.number]?.still.orEmpty() })
        })
    }

    suspend fun seasons(anime: Anime): List<Anime> = withContext(Dispatchers.IO) {
        val doc = Jsoup.parse(detailPage(anime.slug))
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
        val base = seasonFamilyKey(anime.title)
        (listOf(anime) + related)
            .distinctBy { it.slug }
            .filter { candidate -> seasonFamilyKey(candidate.title) == base }
            .sortedWith(compareBy<Anime> { seasonOrder(it).first }.thenBy { seasonOrder(it).second }.thenBy { seasonOrder(it).third }.thenBy { it.slug })
    }

    suspend fun artwork(anime: Anime): Anime = withContext(Dispatchers.IO) {
        val doc = runCatching { Jsoup.parse(detailPage(anime.slug)) }.getOrElse { Jsoup.parse("") }
        val bannerStyle = doc.selectFirst(".anis-cover")?.attr("style").orEmpty()
        val banner = Regex("url\\(['\\\"]?(.*?)['\\\"]?\\)").find(bannerStyle)?.groupValues?.get(1)?.let { URI("$BASE/").resolve(it).toString() }.orEmpty()
        val poster = doc.selectFirst("img.film-poster-img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }.orEmpty()
        val synopsis = doc.selectFirst(".film-description .text, .film-description, .description")?.text()?.take(500).orEmpty()
        val sourceText = synopsis.ifBlank { anime.synopsis }.takeUnless { it.equals("null", true) || it.equals("undefined", true) }.orEmpty()
        val match = findTmdbMatch(anime)
        val verified = match?.let { verifiedArtwork(it, anime) }
        val localized = match?.overview?.takeIf(String::isNotBlank) ?: findTmdbSynopsis(anime) ?: sourceText.takeIf { it.isNotBlank() }?.let { text ->
            translatePtBr(text).takeUnless { it.equals(text, ignoreCase = true) }
        }.orEmpty()
        anime.copy(banner = verified?.banner.orEmpty().ifBlank { banner }.ifBlank { anime.banner },
            poster = verified?.poster.orEmpty().ifBlank { poster }.ifBlank { anime.poster }, synopsis = localized)
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
        return HttpTransport.execute("$OPEN_SUBTITLES_BASE/download", mapOf(
            "Api-Key" to apiKey, "Accept" to "application/json", "User-Agent" to OPEN_SUBTITLES_USER_AGENT,
        ), method = "POST", body = "{\"file_id\":$fileId,\"sub_format\":\"srt\"}",
            connectTimeoutMillis = 12_000, readTimeoutMillis = 20_000).use { response ->
            val code = response.code
            val body = response.body?.string().orEmpty()
            if (code !in 200..299) throw OpenSubtitlesApiException(code)
            val link = org.json.JSONObject(body).optString("link").takeIf { it.startsWith("https://") } ?: return null
            ExternalSubtitle(link, fetchSubtitleDuration(link))
        }
    }

    private fun fetchSubtitleDuration(url: String): Double? {
        val cuePattern = Regex("(\\d{2}:\\d{2}:\\d{2}[,.]\\d{3})\\s*-->\\s*(\\d{2}:\\d{2}:\\d{2}[,.]\\d{3})")
        return try {
            var consumedCharacters = 0
            var lastEnd: Double? = null
            HttpTransport.execute(url, mapOf("Accept" to "text/plain, */*", "User-Agent" to OPEN_SUBTITLES_USER_AGENT),
                connectTimeoutMillis = 10_000, readTimeoutMillis = 12_000).use { response ->
            if (!response.isSuccessful) return null
            response.body?.charStream()?.useLines { lines ->
                lines.forEach { line ->
                    consumedCharacters += line.length
                    if (consumedCharacters > MAX_SUBTITLE_INSPECTION_CHARACTERS) return@useLines
                    val times = cuePattern.find(line)?.groupValues ?: return@forEach
                    lastEnd = timestampSeconds(times[2])
                }
            } }
            lastEnd
        } catch (_: Exception) {
            null
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
        return HttpTransport.execute(url, mapOf(
            "Api-Key" to apiKey, "Accept" to "application/json", "User-Agent" to OPEN_SUBTITLES_USER_AGENT,
        ), connectTimeoutMillis = 12_000, readTimeoutMillis = 20_000).use { response ->
            val code = response.code
            val body = response.body?.string().orEmpty()
            if (code !in 200..299) throw OpenSubtitlesApiException(code) else org.json.JSONObject(body)
        }
    }

    private fun parseCatalog(html: String): List<Anime> =
        Jsoup.parse(html).select(".flw-item").mapNotNull { card ->
            val link = card.selectFirst("a.dynamic-name") ?: return@mapNotNull null
            val slug = URI(BASE).resolve(link.attr("href")).path.trim('/')
            val title = link.text().ifBlank { link.attr("title") }
            if (slug.isBlank() || title.isBlank()) return@mapNotNull null
            val image = card.selectFirst("img.film-poster-img")
            val poster = image?.attr("data-src").orEmpty().ifBlank { image?.attr("src").orEmpty() }
            Anime(slug, title, poster, synopsis = card.selectFirst(".description")?.text().orEmpty().take(500), metadata = card.select(".fdi-item").map { it.text() }.take(4), isAdult = if (card.text().contains("18+")) true else null)
        }.distinctBy { it.slug }.take(36)

    private suspend fun findTmdbMatch(anime: Anime): TmdbMatch? {
        val format = explicitFormat(anime.title, anime.metadata)
        val cacheKey = "${normalizeMatchText(anime.title)}:${format ?: "?"}"
        return tmdbMatchCache.getOrLoad(cacheKey) {
            val key = TMDB_KEY_BYTES.map { (it xor 83).toChar() }.joinToString("")
            val baseTitle = anime.title.substringBefore(" · Temporada").replace(
                Regex("(?i)\\s*(?:[-:·]\\s*)?(?:season|temporada)\\s*\\d+\\s*$|\\s+\\d+(?:st|nd|rd|th)\\s+season\\s*$"), ""
            ).trim().ifBlank { anime.title }
            val query = URLEncoder.encode(baseTitle, Charsets.UTF_8.name())
            val apiKey = URLEncoder.encode(key, Charsets.UTF_8.name())
            val formats = when (format) { "MOVIE" -> listOf("movie"); "TV" -> listOf("tv"); else -> listOf("tv", "movie") }
            val candidates = formats.flatMap { kind ->
                val rows = runCatching { org.json.JSONObject(fetch("https://api.themoviedb.org/3/search/$kind?api_key=$apiKey&language=pt-BR&include_adult=false&query=$query", "https://www.themoviedb.org/"))
                    .optJSONArray("results") }.getOrNull()
                (0 until (rows?.length() ?: 0)).mapNotNull { index ->
                    val row = rows?.optJSONObject(index) ?: return@mapNotNull null
                    val id = row.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
                    val names = if (kind == "movie") listOf(row.optString("title"), row.optString("original_title"))
                        else listOf(row.optString("name"), row.optString("original_name"))
                    val score = identityScore(baseTitle, if (kind == "movie") "MOVIE" else "TV", names, format) ?: return@mapNotNull null
                    fun imagePath(field: String, size: String): String = row.optString(field)
                        .takeIf { it.startsWith("/") && !it.startsWith("//") }?.let { "https://image.tmdb.org/t/p/$size$it" }.orEmpty()
                    TmdbMatch(id, VerifiedArtwork(imagePath("poster_path", "w780"), imagePath("backdrop_path", "w1280")), cleanSynopsis(row.optString("overview"))) to score
                }
            }.sortedByDescending { it.second }
            candidates.firstOrNull()?.takeIf { candidates.size == 1 || candidates[0].second - candidates[1].second >= .12 }?.first
        }
    }

    private suspend fun tmdbSeasonEpisodes(id: Int, season: Int, locale: String): List<EpisodeMetadata> =
        tmdbSeasonCache.getOrLoad("$id:$season:$locale") {
            val key = TMDB_KEY_BYTES.map { (it xor 83).toChar() }.joinToString("")
            val apiKey = URLEncoder.encode(key, Charsets.UTF_8.name())
            val data = org.json.JSONObject(fetch("https://api.themoviedb.org/3/tv/$id/season/$season?api_key=$apiKey&language=$locale", "https://www.themoviedb.org/"))
            val rows = data.optJSONArray("episodes")
            (0 until (rows?.length() ?: 0)).mapNotNull { index ->
                val row = rows?.optJSONObject(index) ?: return@mapNotNull null
                val number = row.optInt("episode_number").takeIf { it > 0 } ?: return@mapNotNull null
                val still = row.optString("still_path").takeIf { it.startsWith("/") && !it.startsWith("//") }
                    ?.let { "https://image.tmdb.org/t/p/w780$it" }.orEmpty()
                EpisodeMetadata(number, row.optString("name"), still)
            }
        }

    fun findTmdbSynopsis(anime: Anime): String? {
        val key = TMDB_KEY_BYTES.map { (it xor 83).toChar() }.joinToString("")
        val title = anime.title.replace(Regex("(?i)\\b(?:season\\s*\\d+|temporada\\s*\\d+|\\d+(?:st|nd|rd|th)\\s+season|movie|filme|dublado|legendado)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        if (title.isBlank()) return null
        val query = URLEncoder.encode(title, Charsets.UTF_8.name())
        val apiKey = URLEncoder.encode(key, Charsets.UTF_8.name())
        val formats = when (explicitFormat(anime.title, anime.metadata)) {
            "MOVIE" -> listOf("movie")
            "TV" -> listOf("tv")
            else -> listOf("tv", "movie")
        }
        val found = mutableListOf<String>()
        for (format in formats) {
            val synopsis = runCatching {
                val response = org.json.JSONObject(fetch("https://api.themoviedb.org/3/search/$format?api_key=$apiKey&language=pt-BR&query=$query", "https://www.themoviedb.org/"))
                val results = response.optJSONArray("results") ?: return@runCatching null
                val candidates = (0 until results.length()).mapNotNull { results.optJSONObject(it) }.map { row ->
                    val names = if (format == "movie") listOf(row.optString("title"), row.optString("original_title")) else listOf(row.optString("name"), row.optString("original_name"))
                    SynopsisCandidate(row.optString("overview"), names)
                }
                chooseLocalizedSynopsis(title, if (format == "movie") "MOVIE" else "TV", candidates)
            }.getOrNull()
            if (!synopsis.isNullOrBlank()) found.add(synopsis)
        }
        return found.distinct().singleOrNull()
    }

    fun findSeasonPoster(anime: Anime, season: Int): String? {
        if (season < 1) return null
        val key = TMDB_KEY_BYTES.map { (it xor 83).toChar() }.joinToString("")
        val title = anime.title.substringBefore(" · Temporada").trim()
        if (title.isBlank()) return null
        return runCatching {
            val query = URLEncoder.encode(title, Charsets.UTF_8.name())
            val apiKey = URLEncoder.encode(key, Charsets.UTF_8.name())
            val rows = org.json.JSONObject(fetch("https://api.themoviedb.org/3/search/tv?api_key=$apiKey&language=pt-BR&query=$query", "https://www.themoviedb.org/")).optJSONArray("results") ?: return null
            val matches = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.mapNotNull { row ->
                val score = identityScore(title, "TV", listOf(row.optString("name"), row.optString("original_name")), null) ?: return@mapNotNull null
                row to score
            }.sortedByDescending { it.second }
            if (matches.isEmpty() || matches.size > 1 && matches[0].second - matches[1].second < .05) return null
            val id = matches[0].first.optInt("id").takeIf { it > 0 } ?: return null
            val seasonData = org.json.JSONObject(fetch("https://api.themoviedb.org/3/tv/$id/season/$season?api_key=$apiKey&language=pt-BR", "https://www.themoviedb.org/"))
            val path = seasonData.optString("poster_path").takeIf { it.startsWith("/") && !it.startsWith("//") } ?: return null
            "https://image.tmdb.org/t/p/w780$path"
        }.getOrNull()
    }

    private fun translatePtBr(text: String): String {
        if (text.isBlank() || text.length > 500) return text
        translationCache[text]?.let { return it }
        return runCatching {
            val query = URLEncoder.encode(text, Charsets.UTF_8.name())
            val response = org.json.JSONObject(fetch("https://api.mymemory.translated.net/get?q=$query&langpair=en|pt-BR", "https://mymemory.translated.net/"))
            val translated = response.optJSONObject("responseData")?.optString("translatedText").orEmpty()
            if (response.optInt("responseStatus") == 200 && translated.isNotBlank() && !translated.equals("null", true) && translated != text) {
                translationCache[text] = translated
                translated
            } else text
        }.getOrElse { text }
    }

    private fun deobfuscate(blob: String): String {
        val bytes = Base64.decode(blob, Base64.DEFAULT)
        val key = "otaku-embed-v1".encodeToByteArray()
        return String(bytes.mapIndexed { index, byte -> (byte.toInt() xor key[index % key.size].toInt()).toByte() }.toByteArray(), Charsets.UTF_8)
    }

    private fun fetch(url: String, referer: String = "$BASE/"): String {
        return HttpTransport.text(url, mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Accept" to "text/html,application/json,*/*",
            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.7",
        ))
    }

    companion object {
        const val BASE = "https://hianime.at"
        private val TMDB_KEY_BYTES = intArrayOf(48, 50, 98, 53, 99, 96, 48, 49, 107, 103, 99, 98, 48, 48, 99, 54, 100, 106, 55, 50, 48, 55, 103, 98, 96, 98, 53, 107, 98, 49, 107, 99)
        private const val OPEN_SUBTITLES_BASE = "https://api.opensubtitles.com/api/v1"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private const val OPEN_SUBTITLES_USER_AGENT = "ani-cli-pt v0.1.7"
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
        private val translationCache = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, String>(128, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 128
        })
    }
}
