package dev.aniclipt.app.data

private val searchableProviders = setOf("AnimeFire", "AnimesDigital", "AnimeDrive", "HiAnime")

internal fun needsRelatedTitleSearch(query: String, directResults: List<Anime>): Boolean {
    val normalizedQuery = normalizeMatchText(query)
    if (normalizedQuery.isBlank()) return true
    val directProviderCoverage = directResults.asSequence()
        .filter { normalizeMatchText(it.title).contains(normalizedQuery) }
        .flatMap { it.metadata.asSequence() }
        .filter { it in searchableProviders }
        .toSet()
        .size
    return directProviderCoverage < 2
}
