package dev.aniclipt.app.data

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

enum class Availability { UNKNOWN, LISTED, EMPTY }

data class Anime(
    val slug: String,
    val title: String,
    val poster: String = "",
    val banner: String = "",
    val synopsis: String = "",
    val metadata: List<String> = emptyList(),
    val alternatives: List<Anime> = emptyList(),
    val anilistId: Int? = null,
    val isAdult: Boolean? = null,
    val availability: Availability = Availability.UNKNOWN,
)

data class Episode(val id: String, val number: String, val title: String, val originalTitle: String = title, val thumbnail: String = "")
data class PlaybackSource(val videoUrl: String, val subtitleUrl: String?, val referer: String, val title: String, val episode: String, val audioLabel: String, val subtitleLabel: String, val availableVideoHeights: List<Int> = emptyList(), val providerSlug: String = "")

class DubSuggestion(val dubbed: PlaybackSource, val english: PlaybackSource?) : Exception("Dublagem PT-BR disponível")

class AnimeRepository {
    private val hi = HiAnimeRepository()
    private val airingCache = TimedRequestCache<String, List<Anime>>(maxEntries = 1, ttlMillis = 10 * 60_000L, shouldCache = { it.isNotEmpty() })
    private val episodeCache = TimedRequestCache<String, List<Episode>>(maxEntries = 24, ttlMillis = 15 * 60_000L, shouldCache = { it.isNotEmpty() })
    private val seasonsCache = TimedRequestCache<String, List<Anime>>(maxEntries = 24, ttlMillis = 30 * 60_000L, shouldCache = { it.isNotEmpty() })
    private val fireDetailsCache = TimedRequestCache<String, String>(maxEntries = 128, ttlMillis = 5 * 60_000L)
    private val seasonPosterCache = TimedRequestCache<String, String>(maxEntries = 128, ttlMillis = 30 * 60_000L, shouldCache = { it.isNotBlank() })
    private val searchCache = TimedRequestCache<String, List<Anime>>(maxEntries = 32, ttlMillis = 2 * 60_000L, shouldCache = { it.isNotEmpty() })
    private data class ArtworkFields(val poster: String, val banner: String, val synopsis: String, val isAdult: Boolean?, val availability: Availability)
    private val artworkCache = TimedRequestCache<String, ArtworkFields>(maxEntries = 128, ttlMillis = 30 * 60_000L, shouldCache = { it.synopsis.isNotBlank() })
    suspend fun airing(): List<Anime> = airingCache.getOrLoad("airing") {
        withContext(Dispatchers.IO) { fireCatalog("$FIRE_BASE/animes?v=3") }
    }

    suspend fun search(query: String): List<Anime> = searchCache.getOrLoad(query.trim().lowercase(java.util.Locale.ROOT)) { searchUncached(query) }

    private suspend fun searchUncached(query: String): List<Anime> = withContext(Dispatchers.IO) {
        suspend fun fetchSource(name: String): List<Anime> = when (name) {
            "AnimeFire" -> fireCatalog("$FIRE_BASE/animes/pesquisar?v=3&q=${URLEncoder.encode(query, Charsets.UTF_8.name())}")
            "AnimesDigital" -> digitalSearch(query)
            "HiAnime" -> hi.search(query).map { it.copy(slug = "hi:" + it.slug, metadata = it.metadata + "HiAnime") }
            "AnimeDrive" -> externalSearch(query, "https://animesdrive.cloud", false)
            "Goyabu" -> externalSearch(query, "https://goyabu.io", true)
            else -> emptyList()
        }
        val results = coroutineScope {
            listOf("AnimeFire", "AnimesDigital", "AnimeDrive", "Goyabu", "HiAnime").map { name -> async { runCatching { fetchSource(name) }.getOrDefault(emptyList()) } }.awaitAll().flatten()
        }.toMutableList()
        val identities = runCatching { aniListSearch(query) }.getOrDefault(emptyList())
        val normalizedQuery = normalizeMatchText(query)
        val knownAliases = hi.searchAliases(query)
        val relatedQueries = if (!needsRelatedTitleSearch(query, results)) emptyList() else identities.take(3)
            .mapNotNull { it.romaji.takeIf(String::isNotBlank) }
            .distinct()
            .filterNot { normalizeMatchText(it) == normalizedQuery }
            .ifEmpty { knownAliases }
            .take(2)
        results += coroutineScope { relatedQueries.map { alias -> async { runCatching { digitalSearch(alias) }.getOrDefault(emptyList()) } }.awaitAll().flatten() }
        val identified = results.distinctBy { it.slug }.map { item ->
            val matched = identities.mapNotNull { identity -> identityScore(item.title, identity.format, identity.titles, explicitFormat(item.title, item.metadata))?.let { identity to it } }.sortedByDescending { it.second }
            val id = matched.firstOrNull()?.takeIf { matched.size == 1 || matched[0].second - matched[1].second >= .12 }?.first?.id
            item.copy(anilistId = id, isAdult = matched.firstOrNull()?.takeIf { it.first.id == id }?.first?.isAdult ?: item.isAdult)
        }
        val groups = mutableListOf<MutableList<Anime>>()
        identified.forEach { item ->
            val group = groups.firstOrNull { entries -> entries.all { other ->
                canUseAsAlternative(item, other, item.anilistId, other.anilistId)
            } }
            if (group == null) groups.add(mutableListOf(item)) else group.add(item)
        }
        val grouped = groups.map { group ->
            val preferred = group.firstOrNull { !it.title.contains("dublado", true) } ?: group.first()
            preferred.copy(alternatives = group.filterNot { it.slug == preferred.slug }, isAdult = aggregateAdultFlags(group))
        }
        val queryIds = identities.filter { identityScore(query, it.format, it.titles) != null }.map { it.id }.toSet()
        filterRelevantResults(grouped, query, queryIds, relatedQueries)
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

    suspend fun episodes(anime: Anime): List<Episode> = episodeCache.getOrLoad(anime.slug) { loadEpisodes(anime) }

    suspend fun localizeEpisodeTitle(anime: Anime, episode: Episode): Episode =
        if (anime.slug.startsWith("hi:")) hi.localizeEpisodeTitle(episode) else episode

    private suspend fun loadEpisodes(anime: Anime): List<Episode> = withContext(Dispatchers.IO) {
        if (anime.slug.startsWith("https://animesdrive.cloud/") || anime.slug.startsWith("https://goyabu.io/")) return@withContext externalEpisodes(anime)
        if (anime.slug.startsWith("hi:")) return@withContext hi.episodes(anime.copy(slug = anime.slug.removePrefix("hi:")))
        if (anime.slug.startsWith("https://animesdigital.org/")) return@withContext digitalEpisodes(anime)
        val data = fireAnime(anime)
        val season = anime.slug.substringAfter("#s", "1").toIntOrNull() ?: 1
        val array = data.optJSONArray("episodes") ?: return@withContext emptyList()
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { item ->
            val id = item.optString("id"); val number = item.optInt("number", 0)
            if (id.isBlank() || number <= 0 || item.optInt("season", 1) != season) null
            else Episode(id, number.toString(), item.optString("title").ifBlank { "Episódio $number" }, thumbnail = item.optString("still_src").takeUnless { it == "null" }.orEmpty())
        } }

    }

    suspend fun seasons(anime: Anime): List<Anime> = seasonsCache.getOrLoad(anime.slug) { loadSeasons(anime) }

    private suspend fun loadSeasons(anime: Anime): List<Anime> = withContext(Dispatchers.IO) {
        if (anime.slug.startsWith("https://animesdrive.cloud/") || anime.slug.startsWith("https://goyabu.io/")) return@withContext listOf(anime)
        if (anime.slug.startsWith("hi:")) return@withContext hi.seasons(anime.copy(slug = anime.slug.removePrefix("hi:"))).map { it.copy(slug = "hi:" + it.slug) }
        if (anime.slug.startsWith("https://animesdigital.org/")) return@withContext listOf(anime)
        val data = fireAnime(anime)
        val seasons = data.optJSONArray("seasons") ?: return@withContext listOf(anime)
        (0 until seasons.length()).mapNotNull { i -> seasons.optJSONObject(i)?.optInt("number", 0)?.takeIf { it > 0 }?.let { n -> anime.copy(slug = anime.slug.substringBefore("#s") + "#s$n", title = anime.title.substringBefore(" · Temporada") + " · Temporada $n") } }.ifEmpty { listOf(anime) }

    }

    suspend fun artwork(anime: Anime): Anime {
        val fields = artworkCache.getOrLoad(anime.slug) {
            val loaded = loadArtwork(anime)
            ArtworkFields(loaded.poster, loaded.banner, loaded.synopsis, loaded.isAdult, loaded.availability)
        }
        return anime.copy(poster = fields.poster.ifBlank { anime.poster }, banner = fields.banner.ifBlank { anime.banner }, synopsis = fields.synopsis, isAdult = fields.isAdult ?: anime.isAdult, availability = fields.availability)
    }

    private suspend fun loadArtwork(anime: Anime): Anime = withContext(Dispatchers.IO) {
        val details = runCatching { when {
            anime.slug.startsWith("https://animesdrive.cloud/") || anime.slug.startsWith("https://goyabu.io/") -> externalArtwork(anime)
            anime.slug.startsWith("hi:") -> hi.artwork(anime.copy(slug = anime.slug.removePrefix("hi:"))).copy(slug = anime.slug)
            anime.slug.startsWith("https://animesdigital.org/") -> anime
            else -> {
                val data = fireAnime(anime)
                val hero = data.optJSONObject("hero")
                val episodes = data.optJSONArray("episodes")
                val availability = availabilityFromEpisodeCount(episodes?.length(), anime.alternatives.isNotEmpty())
                val genres = hero?.optJSONArray("genres")
                val adult = if (genres != null && (0 until genres.length()).any { genres.optString(it).equals("hentai", true) }) true else anime.isAdult
                if (hero == null) anime.copy(availability = availability) else anime.copy(availability = availability, isAdult = adult,
                    banner = hero.optString("backdrop_src").takeUnless { it == "null" }.orEmpty().ifBlank { anime.banner },
                    poster = hero.optString("poster_src").takeUnless { it == "null" }.orEmpty().ifBlank { anime.poster },
                    synopsis = cleanSynopsis(hero.optString("synopsis")).ifBlank { cleanSynopsis(anime.synopsis) },
                )
            }
        } }.getOrDefault(anime)
        val synopsis = cleanSynopsis(details.synopsis).ifBlank {
            if (anime.slug.startsWith("hi:")) "" else runCatching { cleanSynopsis(hi.findTmdbSynopsis(anime).orEmpty()) }.getOrDefault("")
        }.ifBlank {
            val candidates = mutableListOf<String>()
            for (alternate in anime.alternatives.take(3)) {
                val synopsis = runCatching {
                    val candidate = when {
                        alternate.slug.startsWith("hi:") -> hi.artwork(alternate.copy(slug = alternate.slug.removePrefix("hi:")))
                        alternate.slug.startsWith("https://animesdrive.cloud/") || alternate.slug.startsWith("https://goyabu.io/") -> externalArtwork(alternate)
                        !alternate.slug.startsWith("https://") -> {
                            val hero = fireAnime(alternate).optJSONObject("hero")
                            alternate.copy(synopsis = hero?.optString("synopsis").orEmpty())
                        }
                        else -> alternate
                    }
                    cleanSynopsis(candidate.synopsis).takeIf { it.isNotBlank() }
                }.getOrNull()
                if (!synopsis.isNullOrBlank()) { candidates.add(synopsis); break }
            }
            candidates.firstOrNull().orEmpty()
        }
        val seasonNumber = Regex("#s(\\d+)$", RegexOption.IGNORE_CASE).find(anime.slug)?.groupValues?.get(1)?.toIntOrNull()
        val seasonPoster = if (seasonNumber != null && seasonNumber > 1 && !anime.slug.startsWith("hi:") && !anime.slug.startsWith("https://")) {
            runCatching { seasonPosterCache.getOrLoad(anime.slug) { hi.findSeasonPoster(anime, seasonNumber).orEmpty() } }.getOrDefault("")
        } else ""
        details.copy(synopsis = synopsis, poster = seasonPoster.ifBlank { details.poster }, banner = seasonPoster.ifBlank { details.banner })
    }

    suspend fun resolve(anime: Anime, episode: Episode, dubbed: Boolean, openSubtitlesEnabled: Boolean, openSubtitlesApiKey: String, excludedSources: Set<String> = emptySet()): PlaybackSource = withContext(Dispatchers.IO) {
        val seasonAmbiguous = (explicitSeason(anime.title, anime.slug) ?: 1) > 1
        val discovered = if (seasonAmbiguous) emptyList() else runCatching { search(anime.title).flatMap { listOf(it) + it.alternatives } }.getOrDefault(emptyList())
        val candidates = (listOf(anime) + anime.alternatives + discovered).filter { candidate ->
            candidate.slug !in excludedSources && (candidate.slug == anime.slug || canUseAsAlternative(
                anime, candidate, anime.anilistId, candidate.anilistId
            ))
        }.distinctBy { it.slug }.take(10)
        val matched = candidates.mapNotNull { candidate ->
            val ep = if (candidate.slug == anime.slug) episode else runCatching { episodes(candidate).firstOrNull { sameEpisodeNumber(it.number, episode.number) } }.getOrNull()
            ep?.let { candidate to it }
        }
        suspend fun attempt(candidate: Anime, ep: Episode, audioDub: Boolean, allowOpen: Boolean): PlaybackSource? =
            runCatching { resolveSingle(candidate, ep, audioDub, allowOpen, openSubtitlesApiKey).copy(providerSlug = candidate.slug) }.getOrNull()
        choosePlaybackSource(
            dubbed = dubbed,
            useExternalSubtitles = openSubtitlesEnabled && openSubtitlesApiKey.isNotBlank(),
            originalSources = { matched.mapNotNull { (candidate, ep) -> attempt(candidate, ep, false, false) } },
            externalSources = { matched.filter { it.first.slug.startsWith("hi:") }.mapNotNull { (candidate, ep) -> attempt(candidate, ep, false, true) } },
            dubbedSources = { matched.mapNotNull { (candidate, ep) -> attempt(candidate, ep, true, if (dubbed) openSubtitlesEnabled else false) } },
        )
    }

    private data class AniIdentity(val id: Int, val format: String, val romaji: String, val titles: List<String>, val isAdult: Boolean?)
    private val aniCache = java.util.concurrent.ConcurrentHashMap<String, List<AniIdentity>>()

    private fun aniListSearch(query: String): List<AniIdentity> {
        val cacheKey = normalizeMatchText(query)
        aniCache[cacheKey]?.let { return it }
        val request = URL("https://graphql.anilist.co").openConnection() as HttpURLConnection
        request.requestMethod = "POST"; request.doOutput = true; request.connectTimeout = 8000; request.readTimeout = 10000
        request.setRequestProperty("Content-Type", "application/json"); request.setRequestProperty("Accept", "application/json")
        val payload = org.json.JSONObject().put("query", "query (\$search: String) { Page(perPage: 8) { media(search: \$search, type: ANIME) { id format isAdult title { romaji english native } synonyms } } }").put("variables", org.json.JSONObject().put("search", query))
        return try {
            request.outputStream.bufferedWriter().use { it.write(payload.toString()) }
            if (request.responseCode !in 200..299) return emptyList()
            val rows = org.json.JSONObject(request.inputStream.bufferedReader().use { it.readText() }).optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: return emptyList()
            val identities = (0 until rows.length()).mapNotNull { i -> rows.optJSONObject(i)?.let { row ->
                val title = row.optJSONObject("title") ?: return@let null
                val aliases = mutableListOf(title.optString("romaji"), title.optString("english"), title.optString("native"))
                val synonyms = row.optJSONArray("synonyms")
                if (synonyms != null) for (j in 0 until synonyms.length()) aliases.add(synonyms.optString(j))
                AniIdentity(row.optInt("id"), row.optString("format"), title.optString("romaji"), aliases.filter { it.isNotBlank() && it != "null" }, if (row.has("isAdult") && !row.isNull("isAdult")) row.optBoolean("isAdult") else null)
            } }
            identities.also { aniCache[cacheKey] = it }
        } finally { request.disconnect() }
    }

    private suspend fun resolveSingle(anime: Anime, episode: Episode, dubbed: Boolean, openSubtitlesEnabled: Boolean, openSubtitlesApiKey: String): PlaybackSource = withContext(Dispatchers.IO) {
        if (anime.slug.startsWith("https://animesdrive.cloud/") || anime.slug.startsWith("https://goyabu.io/")) return@withContext externalPlayback(anime, episode)
        if (anime.slug.startsWith("hi:")) return@withContext hi.resolve(anime.copy(slug = anime.slug.removePrefix("hi:")), episode, dubbed, openSubtitlesEnabled, openSubtitlesApiKey)
        if (anime.slug.startsWith("https://animesdigital.org/")) return@withContext digitalPlayback(anime, episode, dubbed)
        val data = org.json.JSONObject(fetch("$FIRE_BASE/episode/${episode.id}?v=3", "https://animefire.one/")).getJSONObject("data")
        val streams = data.optJSONArray("streams") ?: error("Nenhuma fonte disponível neste episódio.")
        val preferred = if (dubbed) "dublado" else "legendado"
        val available = (0 until streams.length()).mapNotNull { streams.optJSONObject(it) }
        val stream = available.firstOrNull { it.optString("audio") == preferred && !it.optBoolean("is_offline") && it.optString("url").startsWith("https://") }
            ?: (if (dubbed) available.firstOrNull { it.optString("audio") == "legendado" && !it.optBoolean("is_offline") && it.optString("url").startsWith("https://") } else null)
            ?: error("Áudio solicitado indisponível neste episódio.")
        val url = stream.optString("url")
        val playlist = runCatching { fetch(url, "https://animefire.one/") }.getOrElse { error("AnimeFire: vídeo indisponível. Pesquise o mesmo título em outra fonte.") }
        if (!playlist.startsWith("#EXTM3U")) error("A fonte não retornou um vídeo compatível.")
        val heights = Regex("RESOLUTION=\\d+x(\\d+)").findAll(playlist).mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().sortedDescending().toList()
        PlaybackSource(url, null, "https://animefire.one/", anime.title, episode.number, if (stream.optString("audio") == "dublado") "Dublado PT-BR" else "Áudio original", if (stream.optString("audio") == "legendado") "Legenda da fonte" else "Áudio dublado", heights)

    }

    // Adapted provider code under the MIT license; see THIRD_PARTY_LICENSES.
    private fun digitalSearch(query: String): List<Anime> {
        val doc = Jsoup.parse(fetch("https://animesdigital.org/?s=${URLEncoder.encode(query, Charsets.UTF_8.name())}", "https://animesdigital.org/"))
        return doc.select("a[href*=/anime/]").mapNotNull { link ->
            val href = link.absUrl("href"); val uri = runCatching { URI(href) }.getOrNull()
            if (uri?.host != "animesdigital.org" || uri.scheme != "https") return@mapNotNull null
            val title = link.selectFirst(".title_anime")?.text().orEmpty().ifBlank { link.attr("title").removePrefix("Assistir ").substringBefore(" Online").trim() }
            if (title.isBlank()) null else Anime(href, title, link.selectFirst("img")?.absUrl("src").orEmpty(), metadata = listOf("AnimesDigital"))
        }.distinctBy { it.slug }.take(30)
    }

    private fun digitalEpisodes(anime: Anime): List<Episode> {
        val first = Jsoup.parse(fetch(anime.slug, "https://animesdigital.org/"), anime.slug)
        val maxPage = first.select("a[href*=/page/]").mapNotNull { Regex("/page/(\\d+)/?").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull()?.coerceAtMost(50) ?: 1
        val docs = listOf(first) + (2..maxPage).map { page -> Jsoup.parse(fetch(anime.slug.trimEnd('/') + "/page/$page/", "https://animesdigital.org/"), anime.slug) }
        return docs.flatMap { doc -> doc.select("a[href*=/video/]").mapNotNull { link ->
            val href = link.absUrl("href"); val uri = runCatching { URI(href) }.getOrNull()
            val number = Regex("(?i)(?:epis[oó]dio|ep\\.?)\\D*(\\d+(?:\\.\\d+)?)").find(link.text())?.groupValues?.get(1)
            if (uri?.host != "animesdigital.org" || uri.scheme != "https" || number == null) null else Episode(href, number, "Episódio $number")
        } }.distinctBy { it.id }.sortedBy { it.number.toDoubleOrNull() ?: Double.MAX_VALUE }
    }

    private fun digitalPlayback(anime: Anime, episode: Episode, dubbed: Boolean): PlaybackSource {
        val isDubbed = anime.title.contains("dublado", true) || anime.slug.contains("dublado", true)
        if (dubbed != isDubbed && isDubbed) error("Esta página é dublada. Escolha o resultado legendado do AnimesDigital.")
        val doc = Jsoup.parse(fetch(episode.id, "https://animesdigital.org/"), episode.id)
        val iframe = doc.select("iframe.metaframe.rptss, iframe.rptss.metaframe").mapNotNull { runCatching { URI(it.absUrl("src")) }.getOrNull() }.firstOrNull { it.host == "api.anivideo.net" && it.path.contains("videohls.php") } ?: error("Player compatível indisponível neste episódio.")
        val url = android.net.Uri.parse(iframe.toString()).getQueryParameter("d").orEmpty()
        if (!url.startsWith("https://")) error("Endereço de vídeo inválido.")
        return PlaybackSource(url, null, "https://animesdigital.org/", anime.title, episode.number, if (isDubbed) "Dublado" else "Áudio original", if (isDubbed) "Dublado" else "Legenda da fonte")
    }

    private fun externalSearch(query: String, base: String, goyabu: Boolean): List<Anime> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        if (goyabu) {
            val homepage = fetch("$base/", "$base/")
            val nonce = Regex("\"nonce\"\\s*:\\s*\"([a-f0-9]+)\"").find(homepage)?.groupValues?.get(1)
            if (nonce != null) {
                val json = runCatching { org.json.JSONObject(fetch("$base/wp-json/animeonline/search/?keyword=$encoded&nonce=$nonce", "$base/")) }.getOrNull()
                if (json != null) {
                    val items = json.keys().asSequence().mapNotNull { json.optJSONObject(it) }.mapNotNull { item ->
                        val url = runCatching { URI(base).resolve(item.optString("url")) }.getOrNull(); val title = item.optString("title")
                        if (url?.host != URI(base).host || url.scheme != "https" || title.isBlank()) null else Anime(url.toString(), title, item.optString("img"), metadata = listOf("Goyabu"))
                    }.toList()
                    if (items.isNotEmpty()) return items
                }
            }
        }
        val doc = Jsoup.parse(fetch("$base/?s=$encoded", "$base/"), base)
        val selector = if (goyabu) "article a[href], .anime-item a[href], .post a[href]" else "div.result-item article, article.w_item_a, article.item"
        return doc.select(selector).mapNotNull { card ->
            val link = if (goyabu) card else card.selectFirst("div.title a[href], div.details .title a[href], a[href]") ?: return@mapNotNull null
            val url = link.absUrl("href"); val uri = runCatching { URI(url) }.getOrNull()
            if (uri?.host != URI(base).host || uri.scheme != "https" || !uri.path.contains("/anime/")) return@mapNotNull null
            val title = if (goyabu) card.selectFirst("h3, h2")?.text().orEmpty().ifBlank { card.selectFirst("img")?.attr("alt").orEmpty() } else card.selectFirst("div.title a, div.details .title a")?.text().orEmpty().ifBlank { card.selectFirst("h3,h2")?.text().orEmpty() }.ifBlank { card.selectFirst("img")?.attr("alt").orEmpty() }
            if (title.isBlank()) null else Anime(url, title, card.selectFirst("img")?.absUrl("src").orEmpty(), metadata = listOf(if (goyabu) "Goyabu" else "AnimeDrive"))
        }.distinctBy { it.slug }.take(30)
    }

    private fun externalArtwork(anime: Anime): Anime {
        val doc = Jsoup.parse(fetch(anime.slug), anime.slug)
        val synopsis = doc.selectFirst(".wp-content p, .description p, .sinopse, .description")?.text().orEmpty()
        val image = doc.selectFirst(".poster img, .sheader .poster img, img.wp-post-image")?.absUrl("src").orEmpty()
        return anime.copy(poster = image.ifBlank { anime.poster }, synopsis = synopsis.ifBlank { anime.synopsis })
    }

    private fun externalEpisodes(anime: Anime): List<Episode> {
        val html = fetch(anime.slug); val doc = Jsoup.parse(html, anime.slug)
        if (anime.slug.startsWith("https://goyabu.io/")) {
            val raw = Regex("(?:allEpisodes|episodes|episodeList|episodios)\\s*[:=]\\s*(\\[[\\s\\S]*?\\])").find(html)?.groupValues?.get(1)
            val array = raw?.let { runCatching { org.json.JSONArray(it) }.getOrNull() }
            if (array != null) return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { item -> val id = item.optString("id"); val n = item.optString("episodio").ifBlank { "${i + 1}" }; if (id.isBlank()) null else Episode("https://goyabu.io/?p=$id", n, "Episódio $n") } }.sortedBy { it.number.toDoubleOrNull() ?: Double.MAX_VALUE }
        }
        val links = doc.select(".episode-info .episode-title a[href], .episode-card a[href], #seasons .episodios li a[href], .episodios li a[href], .se-a a[href], #episodes a[href], .episodelist a[href]")
        return links.mapNotNull { link -> val url = link.absUrl("href"); val uri = runCatching { URI(url) }.getOrNull(); if (uri?.host != URI(anime.slug).host || uri.scheme != "https" || !url.contains("episodio", true)) return@mapNotNull null; val n = Regex("(?i)episodios?[-_]?([0-9]+)").find(url)?.groupValues?.get(1) ?: Regex("[0-9]+").find(link.text())?.value ?: return@mapNotNull null; Episode(url, n, link.text().ifBlank { "Episódio $n" }) }.distinctBy { it.id }.sortedBy { it.number.toIntOrNull() ?: Int.MAX_VALUE }
    }

    private fun externalPlayback(anime: Anime, episode: Episode): PlaybackSource {
        val doc = Jsoup.parse(fetch(episode.id), episode.id)
        val sources = doc.select(".animeq-player__source video source[src], .animeq-player__source video[src], video source[src], video[data-video-src]")
        val urls = sources.map { it.absUrl("src").ifBlank { it.absUrl("data-video-src") } }.filter { it.startsWith("https://") }
        val url = urls.firstOrNull { candidate -> runCatching { val c = URL(candidate).openConnection() as HttpURLConnection; c.requestMethod = "HEAD"; c.connectTimeout = 4000; c.readTimeout = 4000; c.setRequestProperty("Referer", "https://animesdrive.cloud/"); try { c.responseCode in 200..399 } finally { c.disconnect() } }.getOrDefault(false) } ?: urls.firstOrNull() ?: error("Nenhum vídeo direto compatível nesta fonte.")
        return PlaybackSource(url, null, URI(anime.slug).let { "${it.scheme}://${it.host}/" }, anime.title, episode.number, "Áudio da fonte", "Legenda da fonte", emptyList())
    }

    private suspend fun fireAnime(anime: Anime): org.json.JSONObject {
        val id = anime.slug.substringBefore("#s")
        val json = fireDetailsCache.getOrLoad(id) {
            fetch("$FIRE_BASE/anime/$id?v=3", "https://animefire.one/").also { org.json.JSONObject(it).getJSONObject("data") }
        }
        return org.json.JSONObject(json).getJSONObject("data")
    }

    private fun fireCatalog(url: String): List<Anime> {
        val array = org.json.JSONObject(fetch(url, "https://animefire.one/")).optJSONArray("data") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { item ->
            val id = item.optString("id"); val title = item.optJSONObject("titles")?.optString("BR").orEmpty()
            if (id.isBlank() || title.isBlank()) null else Anime(id + "#s1", title, item.optString("poster_src"), metadata = listOf("AnimeFire", item.optString("audio").takeUnless { it == "null" }.orEmpty()).filter { it.isNotBlank() })
        } }
    }

    suspend fun catalogSynopsis(anime: Anime): Anime = withContext(Dispatchers.IO) {
        runCatching { artwork(anime) }.getOrDefault(anime.copy(synopsis = cleanSynopsis(anime.synopsis)))
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
        private const val FIRE_BASE = "https://api.animefire.one"
        const val BASE = "https://hianime.at"
        private const val OPEN_SUBTITLES_BASE = "https://api.opensubtitles.com/api/v1"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private const val OPEN_SUBTITLES_USER_AGENT = "ani-cli-pt v0.1.7"
    }
}
