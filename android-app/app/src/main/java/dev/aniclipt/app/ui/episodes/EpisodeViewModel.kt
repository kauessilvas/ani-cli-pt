package dev.aniclipt.app.ui.episodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aniclipt.app.data.Anime
import dev.aniclipt.app.data.AnimeRepository
import dev.aniclipt.app.data.Episode
import dev.aniclipt.app.data.reuseEpisodeMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class EpisodeUiState(
    val anime: Anime? = null,
    val season: Anime? = null,
    val seasons: List<Anime> = emptyList(),
    val episodes: List<Episode> = emptyList(),
    val preparing: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

sealed interface EpisodeEvent { data class Play(val anime: Anime, val episode: Episode) : EpisodeEvent }

/** Owns detail loading and season changes. Each new request cancels its predecessor. */
class EpisodeViewModel(private val repository: AnimeRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(EpisodeUiState())
    val uiState: StateFlow<EpisodeUiState> = mutableState.asStateFlow()
    private val mutableEvents = MutableSharedFlow<EpisodeEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<EpisodeEvent> = mutableEvents.asSharedFlow()
    private var detailsJob: Job? = null

    fun open(anime: Anime) {
        detailsJob?.cancel()
        mutableState.value = EpisodeUiState(anime = anime, season = anime, seasons = listOf(anime),
            preparing = true, busy = true)
        detailsJob = viewModelScope.launch {
            launch {
                val seasons = runCatching { repository.seasons(anime) }.getOrNull().orEmpty()
                if (mutableState.value.anime?.slug == anime.slug) mutableState.update { it.copy(
                    seasons = seasons.ifEmpty { listOf(anime) }) }
            }
            loadSeason(anime)
        }
    }

    fun selectSeason(season: Anime) {
        if (mutableState.value.season?.slug == season.slug) return
        detailsJob?.cancel()
        mutableState.update { it.copy(season = season, episodes = emptyList(), preparing = true, busy = true, error = null) }
        detailsJob = viewModelScope.launch { loadSeason(season) }
    }

    private suspend fun loadSeason(season: Anime) {
        try {
            val cachedArtwork = repository.cachedArtwork(season)
            val cachedEpisodes = repository.cachedEpisodes(season)
            if (mutableState.value.season?.slug != season.slug) return
            mutableState.update { it.copy(season = cachedArtwork, episodes = cachedEpisodes,
                preparing = !repository.hasReadyDisplay(season), busy = cachedEpisodes.isEmpty()) }
            val (artwork, fresh, enriched) = coroutineScope {
                val artworkTask = async { optionalRequest { repository.artwork(season) } }
                val fresh = optionalRequest { repository.episodes(season) }
                val enriched = fresh?.takeIf { it.isNotEmpty() }?.let { episodes ->
                    optionalRequest { repository.enrichEpisodes(season, episodes) }
                }
                Triple(artworkTask.await(), fresh, enriched)
            }
            if (mutableState.value.season?.slug != season.slug) return
            mutableState.update { it.copy(
                season = artwork ?: it.season,
                episodes = when { enriched != null -> enriched; fresh != null -> reuseEpisodeMetadata(fresh, cachedEpisodes); else -> cachedEpisodes },
                preparing = false, busy = false,
                error = if (fresh == null && cachedEpisodes.isEmpty()) "Não foi possível carregar os episódios."
                    else if (fresh?.isEmpty() == true) "Nenhum episódio disponível para este título." else null,
            ) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (mutableState.value.season?.slug == season.slug) mutableState.update { it.copy(
            preparing = false, busy = false, error = failure.message ?: "Não foi possível carregar os episódios.") } }
    }

    private suspend fun <T> optionalRequest(block: suspend () -> T): T? = try {
        withTimeoutOrNull(25_000) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    fun onEpisodeClick(episode: Episode) {
        mutableState.value.season?.let { mutableEvents.tryEmit(EpisodeEvent.Play(it, episode)) }
    }

    fun onEpisodeLocalized(episode: Episode) {
        mutableState.update { state -> state.copy(episodes = state.episodes.map { current ->
            if (current.id == episode.id && (current.title == current.originalTitle || current.title == "Episódio ${current.number}")) episode else current
        }) }
    }

    fun close() {
        detailsJob?.cancel()
        detailsJob = null
        mutableState.value = EpisodeUiState()
    }
}
