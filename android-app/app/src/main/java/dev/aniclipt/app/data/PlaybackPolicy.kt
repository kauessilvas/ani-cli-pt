package dev.aniclipt.app.data

/** A verified primary stream can start without searching every fallback provider. */
internal fun canPlayPrimaryImmediately(source: PlaybackSource, dubbed: Boolean): Boolean {
    if (dubbed) return source.audioLabel.contains("Dublado", true) && !source.audioLabel.contains("indisponível", true)
    if (source.audioLabel.contains("Dublado", true)) return false
    val subtitle = source.subtitleLabel
    return subtitle.contains("PT-BR", true) && !subtitle.contains("Sem PT-BR", true) ||
        subtitle == "Legenda da fonte" && (source.providerSlug.contains("animesdigital.org") || source.providerSlug.contains("#s"))
}

/** Selects a source without knowing how providers perform their requests. */
internal suspend fun choosePlaybackSource(
    dubbed: Boolean,
    useExternalSubtitles: Boolean,
    originalSources: suspend () -> List<PlaybackSource>,
    externalSources: suspend () -> List<PlaybackSource>,
    dubbedSources: suspend () -> List<PlaybackSource>,
): PlaybackSource {
    fun score(value: PlaybackSource) = value.availableVideoHeights.maxOrNull() ?: -1
    fun isDubbed(value: PlaybackSource) = value.audioLabel.contains("Dublado", true) && !value.audioLabel.contains("indisponível", true)
    if (dubbed) {
        val options = dubbedSources()
        return options.filter(::isDubbed).maxByOrNull(::score)
            ?: options.firstOrNull() ?: throw IllegalStateException("Episódio indisponível nas fontes encontradas.")
    }
    val original = originalSources()
    val portuguese = original.filter { item ->
        !item.audioLabel.contains("Dublado", true) &&
            (item.subtitleLabel.contains("PT-BR", true) && !item.subtitleLabel.contains("Sem PT-BR", true) ||
                (item.subtitleLabel == "Legenda da fonte" && (item.providerSlug.contains("animesdigital.org") || item.providerSlug.contains("#s"))))
    }
    portuguese.maxByOrNull(::score)?.let { return it }
    if (useExternalSubtitles) {
        externalSources().filter { it.subtitleLabel.contains("OpenSubtitles", true) }.maxByOrNull(::score)?.let { return it }
    }
    val english = original.filter { it.subtitleLabel.contains("inglês", true) }.maxByOrNull(::score)
    val dubbedBest = dubbedSources().filter(::isDubbed).maxByOrNull(::score)
    if (dubbedBest != null) throw DubSuggestion(dubbedBest, english)
    return english ?: throw IllegalStateException("Nenhuma fonte compatível com este episódio e as legendas disponíveis foi encontrada.")
}
