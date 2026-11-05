package dev.aniclipt.app.data

import org.junit.Assert.*
import org.junit.Test

class TitleMatchingTest {
    @Test fun removesAccentsAndAudioSuffix() {
        assertEquals(matchingTitle("Ação Total - Dublado", null), matchingTitle("Acao Total Legendado", null))
    }
    @Test fun anilistIdentityUnifiesDifferentNames() {
        assertEquals(matchingTitle("Título português", 123), matchingTitle("English title", 123))
    }
    @Test fun differentIdentitiesDoNotMerge() {
        assertNotEquals(matchingTitle("Anime", 123), matchingTitle("Anime", 124))
    }
    @Test fun formatMismatchPreventsMovieAndSeriesAssociation() {
        assertNull(identityScore("Anime Exemplo Filme", "TV", listOf("Anime Exemplo")))
        assertNull(identityScore("Anime Exemplo Temporada 2", "MOVIE", listOf("Anime Exemplo")))
        assertNotNull(identityScore("Anime Exemplo Filme", "MOVIE", listOf("Anime Exemplo")))
    }
    @Test fun singleWordRequiresExactAlias() {
        assertEquals(1.0, identityScore("Bleach", "TV", listOf("Bleach"))!!, 0.0)
        assertNull(identityScore("Bleach", "TV", listOf("Bleach: Another Story")))
    }
    @Test fun similarSequelsDoNotMatchByWordSubset() {
        assertNull(identityScore("Example Story Season 2", "TV", listOf("Example Story Season 1")))
        assertNull(identityScore("Example Story", "TV", listOf("Example Story Next Generation")))
    }
    @Test fun fallbackRejectsDifferentOrUnknownLaterSeasons() {
        val second = Anime("fire#s2", "Example Story · Temporada 2")
        assertFalse(canUseAsAlternative(second, Anime("hi:one", "Example Story Season 1"), 3, 3))
        assertFalse(canUseAsAlternative(second, Anime("hi:unknown", "Example Story"), 3, 3))
        assertTrue(canUseAsAlternative(second, Anime("hi:two", "Example Story Season 2"), 3, 3))
    }
    @Test fun preventsSameTitleWithDifferentIdentitiesFromMerging() {
        val one = Anime("a", "Nome igual", anilistId = 10)
        val other = Anime("b", "Nome igual", anilistId = 11)
        assertFalse(canUseAsAlternative(one, other, one.anilistId, other.anilistId))
    }
    @Test fun seasonParsingRespectsTitleAndMultipleDigits() {
        assertEquals(2, explicitSeason("Example Story Temporada 2", "source#s1"))
        assertEquals(10, explicitSeason("Example Story Season 10"))
        assertEquals(12, explicitSeason("Example Story", "source#s12"))
    }
    @Test fun fallbackRejectsMovieAndDifferentPartsEvenWhenIdentityMatches() {
        val show = Anime("hi:show", "Example Story", metadata = listOf("TV"))
        val movie = Anime("movie#s1", "Example Story Filme", metadata = listOf("Filme"))
        assertFalse(canUseAsAlternative(show, movie, 1, 1))
        assertFalse(canUseAsAlternative(Anime("a", "Example Story Part 1"), Anime("b", "Example Story Part 2"), 1, 1))
    }
    @Test fun episodeComparisonHandlesDecimalFormatsAndRejectsMalformedNumbers() {
        assertTrue(sameEpisodeNumber("01", "1.0"))
        assertFalse(sameEpisodeNumber("1", "2"))
        assertFalse(sameEpisodeNumber("NaN", "NaN"))
        assertFalse(sameEpisodeNumber("-1", "-1"))
    }
    @Test fun matchingIdentityStillRequiresCompatibleMetadata() {
        assertTrue(canUseAsAlternative(Anime("a", "Nome brasileiro"), Anime("b", "English title"), 10, 10))
        assertFalse(canUseAsAlternative(Anime("a", "Nome brasileiro"), Anime("b", "English title"), 10, 11))
    }
    @Test fun filtersUnrelatedProviderResultsWhenMatchesExist() {
        val relevant = Anime("a", "Bleach")
        val unrelated = Anime("b", "Peach Girl")
        assertEquals(listOf(relevant), filterRelevantResults(listOf(relevant, unrelated), "bleach", emptySet()))
    }
    @Test fun searchRetainsAssociatedAliasesAndDoesNotDiscardUncertainResults() {
        val alias = Anime("a", "Nanatsu no Taizai", anilistId = 123)
        val unrelated = Anime("b", "Outra história")
        assertEquals(listOf(alias), filterRelevantResults(listOf(alias, unrelated), "Os sete pecados capitais", setOf(123)))
        assertEquals(listOf(alias, unrelated), filterRelevantResults(listOf(alias, unrelated), "nome desconhecido", emptySet()))
    }
    @Test fun moviesAndSeasonsRemainDistinctWithoutIdentity() {
        assertNotEquals(matchingTitle("Anime Filme", null), matchingTitle("Anime Temporada 2", null))
    }
}
