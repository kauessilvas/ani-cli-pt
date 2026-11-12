package dev.aniclipt.app.data

import dev.aniclipt.app.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
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
    val confirmedAudio: Set<String> = emptySet(),
    val workId: String = "",
    val releaseId: String = "",
)

data class Episode(val id: String, val number: String, val title: String, val originalTitle: String = title, val thumbnail: String = "", val episodeId: String = "")
data class PlaybackSource(val videoUrl: String, val subtitleUrl: String?, val referer: String, val title: String, val episode: String, val audioLabel: String, val subtitleLabel: String, val availableVideoHeights: List<Int> = emptyList(), val providerSlug: String = "")
data class HomeSection(val title: String, val items: List<Anime>)

class DubSuggestion(val dubbed: PlaybackSource, val english: PlaybackSource?) : Exception("Dublagem PT-BR disponível")

class AnimeRepository(private val identityRegistry: IdentityRegistry = IdentityRegistry(), metadataDatabase: AppDatabase? = null) {
    private val displayCache = metadataDatabase?.let(::DisplayMetadataCache)
    private val hi = HiAnimeRepository()
    private val onlineMetadata = AnimesOnlineMetadata()
    private val onlineMetadataCache = TimedRequestCache<String, OnlineAnimeMetadata?>(maxEntries = 64, ttlMillis = 30 * 60_000L)
    private val identityResolver = IdentityResolver(identityRegistry)
    private val fireAdapter = object : AnimeSourceAdapter {
        override val id = "AnimeFire"
        override fun accepts(slug: String) = !slug.startsWith("hi:") && !slug.startsWith("https://")
        override suspend fun search(query: String) = fireCatalog("$FIRE_BASE/animes/pesquisar?v=3&q=${URLEncoder.encode(query, Charsets.UTF_8.name())}")
        override suspend fun episodes(anime: Anime) = fireEpisodes(anime)
        override suspend fun seasons(anime: Anime) = fireSeasons(anime)
        override suspend fun artwork(anime: Anime) = fireArtwork(anime)
        override suspend fun resolve(anime: Anime, episode: Episode, dubbed: Boolean, externalSubtitles: Boolean, apiKey: String) = firePlayback(anime, episode, dubbed)
    }
    private val hiAdapter = object : AnimeSourceAdapter {
        override val id = "HiAnime"
        override val supportsExternalSubtitles = true
        override fun accepts(slug: String) = slug.startsWith("hi:")
        override suspend fun search(query: String) = hi.search(query).map { it.copy(slug = "hi:${it.slug}", metadata = it.metadata + id) }
        override suspend fun episodes(anime: Anime) = hi.episodes(anime.copy(slug = anime.slug.removePrefix("hi:")))
        override suspend fun enrichEpisodes(anime: Anime, episodes: List<Episode>) = hi.enrichEpisodes(anime, episodes)
        override suspend fun localizeEpisodeTitle(episode: Episode) = hi.localizeEpisodeTitle(episode)
        override suspend fun seasons(anime: Anime) = hi.seasons(anime.copy(slug = anime.slug.removePrefix("hi:"))).map { it.copy(slug = "hi:${it.slug}", releaseId = "") }
        override suspend fun artwork(anime: Anime) = hi.artwork(anime.copy(slug = anime.slug.removePrefix("hi:"))).copy(slug = anime.slug)
        override suspend fun resolve(anime: Anime, episode: Episode, dubbed: Boolean, externalSubtitles: Boolean, apiKey: String) = hi.resolve(anime.copy(slug = anime.slug.removePrefix("hi:")), episode, dubbed, externalSubtitles, apiKey)
    }
    private val digitalAdapter = object : AnimeSourceAdapter {
        override val id = "AnimesDigital"
        override fun accepts(slug: String) = slug.startsWith("https://animesdigital.org/")
        override suspend fun search(query: String) = digitalSearch(query)
        override suspend fun episodes(anime: Anime) = digitalEpisodes(anime)
        override suspend fun resolve(anime: Anime, episode: Episode, dubbed: Boolean, externalSubtitles: Boolean, apiKey: String) = digitalPlayback(anime, episode, dubbed)
    }
    private val driveAdapter = object : AnimeSourceAdapter {
        override val id = "AnimeDrive"
        override fun accepts(slug: String) = slug.startsWith("https://animesdrive.cloud/")
        override suspend fun search(query: String) = externalSearch(query, "https://animesdrive.cloud")
        override suspend fun episodes(anime: Anime) = externalEpisodes(anime)
        override suspend fun artwork(anime: Anime) = externalArtwork(anime)
        override suspend fun resolve(anime: Anime, episode: Episode, dubbed: Boolean, externalSubtitles: Boolean, apiKey: String) = externalPlayback(anime, episode)
    }
    private val fallbackAdapter = object : AnimeSourceAdapter {
        override val id = "AnimesOnlineFallback"
        override val searchable = false
        override fun accepts(slug: String) = slug == AnimesOnlineFallback.PROVIDER_SLUG
        override suspend fun resolve(anime: Anime, episode: Episode, dubbed: Boolean, externalSubtitles: Boolean, apiKey: String) =
            AnimesOnlineFallback().resolve(anime, episode, dubbed)
    }
    private val adapters: List<AnimeSourceAdapter> = listOf(fireAdapter, digitalAdapter, driveAdapter, hiAdapter, fallbackAdapter)
    private val catalogAdapters: List<AnimeSourceAdapter> = adapters.filter { it.searchable }
    private fun adapterFor(anime: Anime): AnimeSourceAdapter = adapters.firstOrNull { it.accepts(anime.slug) }
        ?: error("Fonte não reconhecida para ${anime.slug}")
    private val airingCache = TimedRequestCache<String, List<Anime>>(maxEntries = 1, ttlMillis = 10 * 60_000L, shouldCache = { it.isNotEmpty() })
    private val homeSectionCache = TimedRequestCache<String, List<HomeSection>>(maxEntries = 1, ttlMillis = 10 * 60_000L, shouldCache = { it.isNotEmpty() })
    private val episodeCache = TimedRequestCache<String, List<Episode>>(maxEntries = 24, ttlMillis = 15 * 60_000L, shouldCache = { it.isNotEmpty() })
    private val enrichedEpisodeCache = TimedRequestCache<String, List<Episode>>(maxEntries = 24, ttlMillis = 30 * 60_000L, shouldCache = { list -> list.any { it.thumbnail.isNotBlank() } })
    private val seasonsCache = TimedRequestCache<String, List<Anime>>(maxEntries = 24, ttlMillis = 30 * 60_000L, shouldCache = { it.isNotEmpty() })
    private val fireDetailsCache = TimedRequestCache<String, String>(maxEntries = 128, ttlMillis = 5 * 60_000L)
    private val seasonPosterCache = TimedRequestCache<String, String>(maxEntries = 128, ttlMillis = 30 * 60_000L, shouldCache = { it.isNotBlank() })
    private val searchCache = TimedRequestCache<String, List<Anime>>(maxEntries = 32, ttlMillis = 2 * 60_000L, shouldCache = { it.isNotEmpty() })
    private data class ArtworkFields(val poster: String, val banner: String, val synopsis: String, val isAdult: Boolean?, val availability: Availability, val confirmedAudio: Set<String>)
    private val artworkCache = TimedRequestCache<String, ArtworkFields>(maxEntries = 128, ttlMillis = 30 * 60_000L, shouldCache = { it.poster.isNotBlank() || it.banner.isNotBlank() || it.synopsis.isNotBlank() })
    suspend fun airing(): List<Anime> = airingCache.getOrLoad("airing") {
        withContext(Dispatchers.IO) { identityResolver.identifyEach(fireCatalog("$FIRE_BASE/animes?v=3")) }
    }

    suspend fun homeSections(): List<HomeSection> = homeSectionCache.getOrLoad("home") {
        coroutineScope {
            val popular = async { runCatching { hi.popular() }.getOrDefault(emptyList()) }
            val trending = async { runCatching { hi.airing() }.getOrDefault(emptyList()) }
            listOf("Populares" to popular.await(), "Em alta agora" to trending.await()).mapNotNull { (title, items) ->
                val normalized = identityResolver.identifyEach(items.map { it.copy(slug = "hi:${it.slug}", metadata = it.metadata + "HiAnime") })
                normalized.takeIf { it.isNotEmpty() }?.let { HomeSection(title, it) }
            }
        }
    }

    suspend fun search(query: String): List<Anime> = searchCache.getOrLoad(query.trim().lowercase(java.util.Locale.ROOT)) { searchUncached(query) }

    private suspend fun searchUncached(query: String): List<Anime> = withContext(Dispatchers.IO) {
        val results = coroutineScope {
            catalogAdapters.map { adapter -> async { runCatching { adapter.search(query) }.getOrDefault(emptyList()) } }.awaitAll().flatten()
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
        val identified = identityResolver.matchExternalIds(results.distinctBy { it.slug }, identities)
        val grouped = identityResolver.group(identified)
        val queryIds = identities.filter { identityScore(query, it.format, it.titles) != null }.map { it.id }.toSet()
        filterRelevantResults(grouped, query, queryIds, relatedQueries)
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

    suspend fun episodes(anime: Anime): List<Episode> = episodeCache.getOrLoad(anime.slug) {
        identityResolver.identifyEpisodes(anime, loadEpisodes(anime))
    }

    suspend fun cachedArtwork(anime: Anime): Anime = displayCache?.artwork(anime) ?: anime

    suspend fun cachedEpisodes(anime: Anime): List<Episode> = displayCache?.episodes(anime).orEmpty()

    suspend fun hasReadyDisplay(anime: Anime): Boolean {
        val artwork = displayCache?.artwork(anime) ?: return false
        val episodes = displayCache.episodes(anime).orEmpty()
        return artwork.poster.isNotBlank() && hasUsableEpisodeMetadata(anime, episodes)
    }

    suspend fun prepareDisplay(anime: Anime) = coroutineScope {
        if (displayCache?.episodes(anime) == null) launch {
            runCatching { enrichEpisodes(anime, episodes(anime)) }
        }
        if (displayCache?.artwork(anime) == null) launch {
            runCatching { artwork(anime) }
        }
    }

    suspend fun enrichEpisodes(anime: Anime, episodes: List<Episode>): List<Episode> {
        val episodeFingerprint = episodes.joinToString("|") { "${it.id}:${it.number}" }.hashCode()
        val enriched = enrichedEpisodeCache.getOrLoad("${anime.slug}:$episodeFingerprint") {
            val fromSource = adapterFor(anime).enrichEpisodes(anime, episodes)
            val needsMore = fromSource.any { it.thumbnail.isBlank() || it.title.isBlank() || it.title.matches(Regex("(?i)^(episode|epis[oó]dio)\\s*\\d+$")) }
            val enriched = if (!anime.slug.startsWith("hi:") && needsMore) hi.enrichEpisodes(anime, fromSource) else fromSource
            if (enriched.any { it.thumbnail.isBlank() || it.title.isBlank() || it.title.matches(Regex("(?i)^(episode|epis[oó]dio)\\s*\\d+$")) || anime.slug.startsWith("hi:") && it.title == it.originalTitle }) {
                val secondary = runCatching { findOnlineMetadata(anime) }.getOrNull()
                if (secondary != null) mergeOnlineEpisodeMetadata(enriched, secondary.episodes, !anime.metadata.contains("AnimeFire")) else enriched
            } else enriched
        }
        displayCache?.saveEpisodes(anime, enriched)
        return enriched
    }

    suspend fun enhanceImages(anime: Anime): Anime = withContext(Dispatchers.IO) {
        val images = runCatching { hi.findTmdbArtwork(anime) }.getOrNull()
        val improved = anime.copy(poster = images?.poster.orEmpty().ifBlank { anime.poster }, banner = images?.banner.orEmpty().ifBlank { anime.banner })
        val needsPoster = improved.poster.isBlank() || anime.slug.startsWith("hi:") && images?.poster.isNullOrBlank() ||
            Regex("-\\d{2,4}x\\d{2,4}\\.").containsMatchIn(improved.poster)
        if (!needsPoster) return@withContext improved
        val secondary = runCatching { findOnlineMetadata(anime) }.getOrNull()
        improved.copy(poster = secondary?.poster.orEmpty().ifBlank { improved.poster })
    }

    private suspend fun findOnlineMetadata(anime: Anime): OnlineAnimeMetadata? = onlineMetadataCache.getOrLoad(anime.slug) {
        val segments = anime.title.split(':').map(String::trim).filter { it.split(' ').size >= 2 }
        val basicAliases = (segments + hi.searchAliases(anime.title)).distinctBy(::onlineTitleKey)
        runCatching { onlineMetadata.find(anime, basicAliases) }.getOrNull() ?: run {
            val identities = runCatching { withContext(Dispatchers.IO) { aniListSearch(anime.title) } }.getOrDefault(emptyList())
            val matches = identities.mapNotNull { identity ->
                if (anime.anilistId != null && identity.id == anime.anilistId) identity to 1.0
                else identityScore(anime.title, identity.format, identity.titles, explicitFormat(anime.title, anime.metadata))?.let { identity to it }
            }.sortedByDescending { it.second }
            val best = matches.firstOrNull()?.takeIf { matches.size == 1 || matches[0].second - matches[1].second >= .12 }?.first
            best?.let { runCatching { onlineMetadata.find(anime, basicAliases + it.titles) }.getOrNull() }
        }
    }

    suspend fun localizeEpisodeTitle(anime: Anime, episode: Episode): Episode =
        adapterFor(anime).localizeEpisodeTitle(episode)

    private suspend fun loadEpisodes(anime: Anime): List<Episode> = withContext(Dispatchers.IO) {
        adapterFor(anime).episodes(anime)
    }

    private suspend fun fireEpisodes(anime: Anime): List<Episode> = withContext(Dispatchers.IO) {
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
        identityResolver.group(adapterFor(anime).seasons(anime))
    }

    private suspend fun fireSeasons(anime: Anime): List<Anime> = withContext(Dispatchers.IO) {
        val data = fireAnime(anime)
        val seasons = data.optJSONArray("seasons") ?: return@withContext listOf(anime)
        (0 until seasons.length()).mapNotNull { i -> seasons.optJSONObject(i)?.optInt("number", 0)?.takeIf { it > 0 }?.let { n -> anime.copy(slug = anime.slug.substringBefore("#s") + "#s$n", title = anime.title.substringBefore(" · Temporada") + " · Temporada $n", releaseId = "") } }.ifEmpty { listOf(anime) }

    }

    suspend fun artwork(anime: Anime): Anime {
        val fields = artworkCache.getOrLoad(anime.slug) {
            val loaded = loadArtwork(anime)
            ArtworkFields(loaded.poster, loaded.banner, loaded.synopsis, loaded.isAdult, loaded.availability, loaded.confirmedAudio)
        }
        return anime.copy(poster = fields.poster.ifBlank { anime.poster }, banner = fields.banner.ifBlank { anime.banner }, synopsis = fields.synopsis, isAdult = fields.isAdult ?: anime.isAdult, availability = fields.availability, confirmedAudio = fields.confirmedAudio)
            .also { displayCache?.saveArtwork(it) }
    }

    private suspend fun fireArtwork(anime: Anime): Anime {
        val data = fireAnime(anime)
        val hero = data.optJSONObject("hero")
        val episodes = data.optJSONArray("episodes")
        val availability = availabilityFromEpisodeCount(episodes?.length(), anime.alternatives.isNotEmpty())
        val genres = hero?.optJSONArray("genres")
        val labels = (0 until (genres?.length() ?: 0)).map { genres!!.optString(it) }
        val adult = if (hasAdultContentLabels(labels)) true else anime.isAdult
        val confirmedAudio = verifyFireAudio(episodes)
        return if (hero == null) anime.copy(availability = availability, confirmedAudio = confirmedAudio) else anime.copy(availability = availability, isAdult = adult, confirmedAudio = confirmedAudio,
            banner = hero.optString("backdrop_src").takeUnless { it == "null" }.orEmpty().ifBlank { anime.banner },
            poster = hero.optString("poster_src").takeUnless { it == "null" }.orEmpty().ifBlank { anime.poster },
            synopsis = cleanSynopsis(hero.optString("synopsis")).ifBlank { cleanSynopsis(anime.synopsis) },
        )
    }

    private suspend fun loadArtwork(anime: Anime): Anime = withContext(Dispatchers.IO) {
        val fromSource = runCatching { adapterFor(anime).artwork(anime) }.getOrDefault(anime)
        val details = enhanceImages(fromSource)
        val synopsis = cleanSynopsis(details.synopsis).ifBlank {
            if (anime.slug.startsWith("hi:")) "" else runCatching { cleanSynopsis(hi.findTmdbSynopsis(anime).orEmpty()) }.getOrDefault("")
        }.ifBlank {
            val candidates = mutableListOf<String>()
            for (alternate in anime.alternatives.take(3)) {
                val synopsis = runCatching {
                    val candidate = adapterFor(alternate).artwork(alternate)
                    cleanSynopsis(candidate.synopsis).takeIf { it.isNotBlank() }
                }.getOrNull()
                if (!synopsis.isNullOrBlank()) { candidates.add(synopsis); break }
            }
            candidates.firstOrNull().orEmpty()
        }.ifBlank {
            runCatching { cleanSynopsis(findOnlineMetadata(anime)?.synopsis.orEmpty()) }.getOrDefault("")
        }
        val seasonNumber = Regex("#s(\\d+)$", RegexOption.IGNORE_CASE).find(anime.slug)?.groupValues?.get(1)?.toIntOrNull()
        val seasonPoster = if (seasonNumber != null && seasonNumber > 1 && !anime.slug.startsWith("hi:") && !anime.slug.startsWith("https://")) {
            runCatching { seasonPosterCache.getOrLoad(anime.slug) { hi.findSeasonPoster(anime, seasonNumber).orEmpty() } }.getOrDefault("")
        } else ""
        details.copy(synopsis = synopsis, poster = seasonPoster.ifBlank { details.poster }, banner = seasonPoster.ifBlank { details.banner })
    }

    suspend fun resolve(anime: Anime, episode: Episode, dubbed: Boolean, openSubtitlesEnabled: Boolean, openSubtitlesApiKey: String, excludedSources: Set<String> = emptySet()): PlaybackSource = withContext(Dispatchers.IO) {
        val primary = if (anime.slug in excludedSources) null else try {
            resolveSingle(anime, episode, dubbed, openSubtitlesEnabled, openSubtitlesApiKey).copy(providerSlug = anime.slug)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        if (primary != null && canPlayPrimaryImmediately(primary, dubbed)) return@withContext primary
        val seasonAmbiguous = (explicitSeason(anime.title, anime.slug) ?: 1) > 1
        val discovered = if (seasonAmbiguous) emptyList() else runCatching { search(anime.title).flatMap { listOf(it) + it.alternatives } }.getOrDefault(emptyList())
        val candidates = (listOf(anime) + anime.alternatives + discovered).filter { candidate ->
            candidate.slug !in excludedSources && (candidate.slug == anime.slug || identityResolver.canUseAsAlternative(anime, candidate))
        }.distinctBy { it.slug }.take(10)
        val matched = coroutineScope { candidates.map { candidate -> async {
            val ep = if (candidate.slug == anime.slug) episode else runCatching { episodes(candidate).firstOrNull {
                if (it.episodeId.isNotBlank() && episode.episodeId.isNotBlank()) it.episodeId == episode.episodeId
                else sameEpisodeNumber(it.number, episode.number)
            } }.getOrNull()
            ep?.let { candidate to it }
        } }.awaitAll().filterNotNull() }
        suspend fun attempt(candidate: Anime, ep: Episode, audioDub: Boolean, allowOpen: Boolean): PlaybackSource? =
            if (candidate.slug == anime.slug && audioDub == dubbed && primary != null) primary
            else runCatching { resolveSingle(candidate, ep, audioDub, allowOpen, openSubtitlesApiKey).copy(providerSlug = candidate.slug) }.getOrNull()
        suspend fun gather(options: List<Pair<Anime, Episode>>, audioDub: Boolean, allowOpen: Boolean): List<PlaybackSource> = coroutineScope {
            val permits = Semaphore(3)
            options.map { (candidate, ep) -> async { permits.withPermit { attempt(candidate, ep, audioDub, allowOpen) } } }
                .awaitAll().filterNotNull()
        }
        try {
            choosePlaybackSource(
                dubbed = dubbed,
                useExternalSubtitles = openSubtitlesEnabled && openSubtitlesApiKey.isNotBlank(),
                originalSources = { gather(matched, false, false) },
                externalSources = { gather(matched.filter { adapterFor(it.first).supportsExternalSubtitles }, false, true) },
                dubbedSources = { gather(matched, true, if (dubbed) openSubtitlesEnabled else false) },
            )
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (failure !is IllegalStateException && failure !is DubSuggestion || AnimesOnlineFallback.PROVIDER_SLUG in excludedSources) throw failure
            try { fallbackAdapter.resolve(anime, episode, dubbed, false, "") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw failure }
        }
    }

    private val aniCache = java.util.concurrent.ConcurrentHashMap<String, List<CatalogIdentity>>()

    private fun aniListSearch(query: String): List<CatalogIdentity> {
        val cacheKey = normalizeMatchText(query)
        aniCache[cacheKey]?.let { return it }
        val payload = org.json.JSONObject().put("query", "query (\$search: String) { Page(perPage: 8) { media(search: \$search, type: ANIME) { id format isAdult genres tags { name rank isMediaSpoiler } title { romaji english native } synonyms } } }").put("variables", org.json.JSONObject().put("search", query))
        return HttpTransport.execute("https://graphql.anilist.co", mapOf("Accept" to "application/json"),
            method = "POST", body = payload.toString(), connectTimeoutMillis = 8_000, readTimeoutMillis = 10_000).use { response ->
            if (!response.isSuccessful) return emptyList()
            val rows = org.json.JSONObject(response.body?.string().orEmpty()).optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: return emptyList()
            val identities = (0 until rows.length()).mapNotNull { i -> rows.optJSONObject(i)?.let { row ->
                val title = row.optJSONObject("title") ?: return@let null
                val aliases = mutableListOf(title.optString("romaji"), title.optString("english"), title.optString("native"))
                val synonyms = row.optJSONArray("synonyms")
                if (synonyms != null) for (j in 0 until synonyms.length()) aliases.add(synonyms.optString(j))
                val genres = row.optJSONArray("genres")?.let { values -> (0 until values.length()).map { values.optString(it) } }.orEmpty()
                val tags = row.optJSONArray("tags")?.let { values -> (0 until values.length()).mapNotNull { index -> values.optJSONObject(index)?.takeUnless { it.optBoolean("isMediaSpoiler") }?.let { it.optString("name") to it.optInt("rank") } } }.orEmpty()
                val adult = if (hasAdultContentLabels(genres, tags) || row.optBoolean("isAdult")) true else if (row.has("isAdult") && !row.isNull("isAdult")) false else null
                CatalogIdentity(row.optInt("id"), row.optString("format"), title.optString("romaji"), aliases.filter { it.isNotBlank() && it != "null" }, adult)
            } }
            identities.also { aniCache[cacheKey] = it }
        }
    }

    private suspend fun resolveSingle(anime: Anime, episode: Episode, dubbed: Boolean, openSubtitlesEnabled: Boolean, openSubtitlesApiKey: String): PlaybackSource = withContext(Dispatchers.IO) {
        adapterFor(anime).resolve(anime, episode, dubbed, openSubtitlesEnabled, openSubtitlesApiKey)
    }

    private suspend fun firePlayback(anime: Anime, episode: Episode, dubbed: Boolean): PlaybackSource = withContext(Dispatchers.IO) {
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

    private fun externalSearch(query: String, base: String): List<Anime> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val doc = Jsoup.parse(fetch("$base/?s=$encoded", "$base/"), base)
        return doc.select("div.result-item article, article.w_item_a, article.item").mapNotNull { card ->
            val link = card.selectFirst("div.title a[href], div.details .title a[href], a[href]") ?: return@mapNotNull null
            val url = link.absUrl("href"); val uri = runCatching { URI(url) }.getOrNull()
            if (uri?.host != URI(base).host || uri.scheme != "https" || !uri.path.contains("/anime/")) return@mapNotNull null
            val title = card.selectFirst("div.title a, div.details .title a")?.text().orEmpty().ifBlank { card.selectFirst("h3,h2")?.text().orEmpty() }.ifBlank { card.selectFirst("img")?.attr("alt").orEmpty() }
            if (title.isBlank()) null else Anime(url, title, card.selectFirst("img")?.absUrl("src").orEmpty(), metadata = listOf("AnimeDrive"))
        }.distinctBy { it.slug }.take(30)
    }

    private fun externalArtwork(anime: Anime): Anime {
        val doc = Jsoup.parse(fetch(anime.slug), anime.slug)
        val synopsis = doc.selectFirst(".wp-content p, .description p, .sinopse, .description")?.text().orEmpty()
        val image = doc.selectFirst(".poster img, .sheader .poster img, img.wp-post-image")?.absUrl("src").orEmpty()
        return anime.copy(poster = image.ifBlank { anime.poster }, synopsis = synopsis.ifBlank { anime.synopsis })
    }

    private fun externalEpisodes(anime: Anime): List<Episode> {
        val doc = Jsoup.parse(fetch(anime.slug), anime.slug)
        val links = doc.select(".episode-info .episode-title a[href], .episode-card a[href], #seasons .episodios li a[href], .episodios li a[href], .se-a a[href], #episodes a[href], .episodelist a[href]")
        return links.mapNotNull { link -> val url = link.absUrl("href"); val uri = runCatching { URI(url) }.getOrNull(); if (uri?.host != URI(anime.slug).host || uri.scheme != "https" || !url.contains("episodio", true)) return@mapNotNull null; val n = Regex("(?i)episodios?[-_]?([0-9]+)").find(url)?.groupValues?.get(1) ?: Regex("[0-9]+").find(link.text())?.value ?: return@mapNotNull null; Episode(url, n, link.text().ifBlank { "Episódio $n" }) }.distinctBy { it.id }.sortedBy { it.number.toIntOrNull() ?: Int.MAX_VALUE }
    }

    private fun externalPlayback(anime: Anime, episode: Episode): PlaybackSource {
        val doc = Jsoup.parse(fetch(episode.id), episode.id)
        val sources = doc.select(".animeq-player__source video source[src], .animeq-player__source video[src], video source[src], video[data-video-src]")
        val urls = sources.map { it.absUrl("src").ifBlank { it.absUrl("data-video-src") } }.filter { it.startsWith("https://") }
        val url = urls.firstOrNull { candidate -> runCatching {
            HttpTransport.execute(candidate, mapOf("Referer" to "https://animesdrive.cloud/"), method = "HEAD",
                connectTimeoutMillis = 4_000, readTimeoutMillis = 4_000).use { it.code in 200..399 }
        }.getOrDefault(false) } ?: urls.firstOrNull() ?: error("Nenhum vídeo direto compatível nesta fonte.")
        return PlaybackSource(url, null, URI(anime.slug).let { "${it.scheme}://${it.host}/" }, anime.title, episode.number, "Áudio da fonte", "Legenda da fonte", emptyList())
    }

    private suspend fun fireAnime(anime: Anime): org.json.JSONObject {
        val id = anime.slug.substringBefore("#s")
        val json = fireDetailsCache.getOrLoad(id) {
            fetch("$FIRE_BASE/anime/$id?v=3", "https://animefire.one/").also { org.json.JSONObject(it).getJSONObject("data") }
        }
        return org.json.JSONObject(json).getJSONObject("data")
    }

    private fun verifyFireAudio(episodes: org.json.JSONArray?): Set<String> {
        if (episodes == null) return emptySet()
        val ids = (episodes.length() - 1 downTo 0).mapNotNull { episodes.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }.distinct().take(2)
        val confirmed = mutableSetOf<String>()
        for (id in ids) {
            val streams = runCatching { org.json.JSONObject(fetch("$FIRE_BASE/episode/$id?v=3", "https://animefire.one/")).optJSONObject("data")?.optJSONArray("streams") }.getOrNull() ?: continue
            for (index in 0 until streams.length()) {
                val stream = streams.optJSONObject(index) ?: continue
                if (!stream.optBoolean("is_offline") && stream.optString("url").startsWith("https://")) {
                    val audio = stream.optString("audio").lowercase(java.util.Locale.ROOT)
                    if (audio == "dublado" || audio == "legendado") confirmed.add(audio)
                }
            }
            if (confirmed.size == 2) break
        }
        return confirmed
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
        return HttpTransport.text(url, mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Accept" to "text/html,application/json,*/*",
            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.7",
        ))
    }

    companion object {
        private const val FIRE_BASE = "https://api.animefire.one"
        const val BASE = "https://hianime.at"
        private const val OPEN_SUBTITLES_BASE = "https://api.opensubtitles.com/api/v1"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private const val OPEN_SUBTITLES_USER_AGENT = "ani-cli-pt v0.1.7"
    }
}
