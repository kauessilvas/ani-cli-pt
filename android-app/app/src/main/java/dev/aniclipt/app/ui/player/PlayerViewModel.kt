package dev.aniclipt.app.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aniclipt.app.data.Anime
import dev.aniclipt.app.data.AnimeRepository
import dev.aniclipt.app.data.DubSuggestion
import dev.aniclipt.app.data.Episode
import dev.aniclipt.app.data.PlaybackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class PlayerUiState(
    val anime: Anime? = null,
    val episode: Episode? = null,
    val source: PlaybackSource? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val dubSuggestion: DubSuggestion? = null,
    val resumePositionMs: Long = 0,
)

/** Resolves playback sources. The Media3 player remains owned by the lifecycle-aware UI. */
class PlayerViewModel(private val repository: AnimeRepository) : ViewModel() {
    private data class ResumeRequest(val anime: Anime, val episodeId: String, val episodeUid: String,
        val episodeNumber: String, val episodeTitle: String, val positionMs: Long)
    private val mutableState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = mutableState.asStateFlow()
    private var resolveJob: Job? = null
    private var lastDubbed = false
    private var lastExternalSubtitles = false
    private var lastApiKey = ""
    private var excludedSources = emptySet<String>()
    private var pendingResume: ResumeRequest? = null

    fun play(anime: Anime, episode: Episode, dubbed: Boolean, externalSubtitles: Boolean,
        apiKey: String, resumePositionMs: Long = 0, excludedSources: Set<String> = emptySet()) {
        resolveJob?.cancel()
        pendingResume = null
        lastDubbed = dubbed
        lastExternalSubtitles = externalSubtitles
        lastApiKey = apiKey
        this.excludedSources = excludedSources
        mutableState.value = PlayerUiState(anime = anime, episode = episode, loading = true,
            resumePositionMs = resumePositionMs.coerceAtLeast(0))
        resolveJob = viewModelScope.launch {
            try {
                val source = withTimeout(60_000) { repository.resolve(anime, episode, dubbed,
                    externalSubtitles, apiKey.trim(), excludedSources) }
                mutableState.update { it.copy(source = source, loading = false) }
            } catch (cancelled: CancellationException) {
                if (cancelled is TimeoutCancellationException) mutableState.update { it.copy(
                    loading = false, error = "O carregamento demorou demais. Tente novamente.") }
                else throw cancelled
            } catch (suggestion: DubSuggestion) {
                mutableState.update { it.copy(loading = false, dubSuggestion = suggestion) }
            } catch (failure: Exception) {
                mutableState.update { it.copy(loading = false,
                    error = failure.message ?: "Não foi possível preparar a reprodução.") }
            }
        }
    }

    fun resume(anime: Anime, episodeId: String, episodeUid: String, episodeNumber: String,
        episodeTitle: String, resumePositionMs: Long, dubbed: Boolean, externalSubtitles: Boolean,
        apiKey: String) {
        resolveJob?.cancel()
        pendingResume = ResumeRequest(anime, episodeId, episodeUid, episodeNumber, episodeTitle, resumePositionMs)
        lastDubbed = dubbed
        lastExternalSubtitles = externalSubtitles
        lastApiKey = apiKey
        val pending = Episode(episodeId, episodeNumber, episodeTitle, episodeId = episodeUid)
        mutableState.value = PlayerUiState(anime = anime, episode = pending, loading = true,
            resumePositionMs = resumePositionMs)
        resolveJob = viewModelScope.launch {
            try {
                val episodes = withTimeout(60_000) { repository.episodes(anime) }
                val matched = episodes.firstOrNull { episodeUid.isNotBlank() && it.episodeId == episodeUid }
                    ?: episodes.firstOrNull { it.id == episodeId }
                    ?: episodes.firstOrNull { it.number == episodeNumber }
                if (matched == null) mutableState.update { it.copy(loading = false,
                    error = "Não foi possível localizar o episódio salvo.") }
                else {
                    resolveJob = null
                    play(anime, matched, dubbed, externalSubtitles, apiKey, resumePositionMs)
                }
            } catch (cancelled: CancellationException) {
                if (cancelled is TimeoutCancellationException) mutableState.update { it.copy(loading = false,
                    error = "O carregamento demorou demais. Tente novamente.") }
                else throw cancelled
            } catch (failure: Exception) { mutableState.update { it.copy(loading = false,
                error = failure.message ?: "Não foi possível localizar o episódio salvo.") } }
        }
    }

    fun retry() {
        val current = mutableState.value
        val resume = pendingResume
        if (resume != null) {
            resume(resume.anime, resume.episodeId, resume.episodeUid, resume.episodeNumber,
                resume.episodeTitle, resume.positionMs, lastDubbed, lastExternalSubtitles, lastApiKey)
        } else if (current.anime != null && current.episode != null) play(current.anime, current.episode,
            lastDubbed, lastExternalSubtitles, lastApiKey, current.resumePositionMs, excludedSources)
    }

    fun onSourceFailure(positionMs: Long) {
        val current = mutableState.value
        val anime = current.anime ?: return
        val episode = current.episode ?: return
        val failed = current.source?.providerSlug?.ifBlank { anime.slug } ?: anime.slug
        play(anime, episode, lastDubbed, lastExternalSubtitles, lastApiKey, positionMs,
            excludedSources + failed)
    }

    fun chooseDubbed() {
        val suggestion = mutableState.value.dubSuggestion ?: return
        mutableState.update { it.copy(source = suggestion.dubbed, dubSuggestion = null) }
    }

    fun chooseEnglish() {
        val english = mutableState.value.dubSuggestion?.english ?: return
        mutableState.update { it.copy(source = english, dubSuggestion = null) }
    }

    fun close() {
        resolveJob?.cancel()
        resolveJob = null
        excludedSources = emptySet()
        pendingResume = null
        mutableState.value = PlayerUiState()
    }
}
