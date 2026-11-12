package dev.aniclipt.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aniclipt.app.data.Anime
import dev.aniclipt.app.data.AnimeRepository
import dev.aniclipt.app.data.HomeSection
import dev.aniclipt.app.data.IdentityRegistry
import dev.aniclipt.app.data.initialSynopsis
import dev.aniclipt.app.data.local.FavoriteEntity
import dev.aniclipt.app.data.local.LocalAnimeStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class HomeUiState(
    val airing: List<Anime> = emptyList(),
    val results: List<Anime> = emptyList(),
    val sections: List<HomeSection> = emptyList(),
    val featured: Anime? = null,
    val favorites: List<FavoriteEntity> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    val searching: Boolean = false,
    val error: String? = null,
)

sealed interface HomeEvent { data class OpenAnime(val anime: Anime) : HomeEvent }

/** Owns catalog requests and favorite writes; composables only observe state and send events. */
class HomeViewModel(
    private val repository: AnimeRepository,
    private val identityRegistry: IdentityRegistry,
    private val localStore: LocalAnimeStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = mutableState.asStateFlow()
    private val mutableEvents = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<HomeEvent> = mutableEvents.asSharedFlow()
    private var searchJob: Job? = null
    private var enrichmentJob: Job? = null
    private val enrichmentPermits = Semaphore(4)
    private val prefetchPermits = Semaphore(1)
    private val prefetched = mutableSetOf<String>()
    private val sectionEnhanced = mutableSetOf<String>()
    private var started = false

    init {
        viewModelScope.launch { localStore.favorites.collect { favorites -> mutableState.update { it.copy(favorites = favorites) } } }
    }

    fun start() {
        if (started) return
        started = true
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            val sectionsTask = async { runCatching { repository.homeSections() }.getOrDefault(emptyList()) }
            try {
                val airing = repository.airing().map { it.copy(synopsis = initialSynopsis(it)) }
                mutableState.update { old -> old.copy(
                    airing = airing,
                    results = if (old.query.isBlank()) airing else old.results,
                    featured = airing.firstOrNull(),
                    loading = false,
                ) }
                sectionsTask.await().let { sections -> mutableState.update { it.copy(sections = sections) } }
                airing.firstOrNull()?.let { first -> viewModelScope.launch {
                    val artwork = runCatching { repository.artwork(first) }.getOrNull() ?: return@launch
                    mutableState.update { old -> old.copy(
                        featured = artwork.takeIf { old.featured?.slug == it.slug } ?: old.featured,
                        airing = old.airing.map { if (it.slug == artwork.slug) artwork else it },
                        results = if (old.query.isBlank()) old.results.map { if (it.slug == artwork.slug) artwork else it } else old.results,
                    ) }
                } }
                enrich(airing.filterNot { it.slug == airing.firstOrNull()?.slug })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.update { it.copy(loading = false, error = failure.message ?: "Falha ao carregar o catálogo.") } }
        }
    }

    fun onSearch(query: String, debounce: Boolean = true) {
        searchJob?.cancel()
        enrichmentJob?.cancel()
        val normalized = query.trim()
        mutableState.update { it.copy(query = query, searching = normalized.isNotEmpty(),
            results = if (normalized.isEmpty()) it.airing else emptyList(), error = null) }
        if (normalized.isEmpty()) return
        searchJob = viewModelScope.launch {
            if (debounce) delay(350)
            try {
                val results = repository.search(normalized).map { it.copy(synopsis = initialSynopsis(it)) }
                if (mutableState.value.query.trim() != normalized) return@launch
                mutableState.update { it.copy(results = results, searching = false,
                    error = if (results.isEmpty()) "Nenhum resultado encontrado." else null) }
                enrich(results)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.update { it.copy(searching = false,
                error = "Falha ao buscar o catálogo: ${failure.message ?: "erro de rede"}") } }
        }
    }

    private fun enrich(items: List<Anime>) {
        enrichmentJob?.cancel()
        enrichmentJob = viewModelScope.launch {
            items.forEach { anime -> launch {
                val updated = enrichmentPermits.withPermit { runCatching { repository.catalogSynopsis(anime) }.getOrNull() } ?: return@launch
                mutableState.update { old -> old.copy(
                    airing = old.airing.map { if (it.slug == updated.slug) updated else it },
                    results = old.results.map { if (it.slug == updated.slug) updated else it },
                    featured = updated.takeIf { it.slug == old.featured?.slug } ?: old.featured,
                ) }
            } }
        }
    }

    fun onAnimeClick(anime: Anime) { mutableEvents.tryEmit(HomeEvent.OpenAnime(anime)) }

    fun onAnimeVisible(anime: Anime) {
        if (!prefetched.add(anime.slug)) return
        viewModelScope.launch {
            delay(400)
            prefetchPermits.withPermit { runCatching { repository.prepareDisplay(anime) } }
        }
    }

    fun onSectionAnimeVisible(anime: Anime) {
        if (!sectionEnhanced.add(anime.slug)) return
        viewModelScope.launch {
            val enhanced = runCatching { repository.enhanceImages(anime) }.getOrNull()
            if (enhanced == null) { sectionEnhanced.remove(anime.slug); return@launch }
            mutableState.update { state -> state.copy(sections = state.sections.map { section ->
                section.copy(items = section.items.map { if (it.slug == anime.slug) it.copy(
                    poster = enhanced.poster.ifBlank { it.poster }, banner = enhanced.banner.ifBlank { it.banner }) else it })
            }) }
        }
    }

    fun onFavoriteToggle(anime: Anime) {
        viewModelScope.launch {
            val identified = identityRegistry.assign(listOf(anime)).first()
            val releaseId = identityRegistry.canonicalReleaseId(identified.releaseId)
            identityRegistry.awaitPersisted()
            localStore.toggleFavorite(FavoriteEntity(releaseId, identified.workId, identified.slug,
                identified.title, identified.poster, identified.synopsis, identified.anilistId, identified.isAdult), identified.metadata)
        }
    }
}
