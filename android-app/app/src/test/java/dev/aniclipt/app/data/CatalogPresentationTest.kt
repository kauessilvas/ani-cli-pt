package dev.aniclipt.app.data

import org.junit.Assert.*
import org.junit.Test

class CatalogPresentationTest {
    @Test fun onlyReportsAudioConfirmedByAnActiveStream() {
        assertEquals(listOf("Dublado", "Legendado"), catalogTags(Anime("a", "Anime", confirmedAudio = setOf("dublado", "legendado"))))
        assertEquals(emptyList<String>(), catalogTags(Anime("a", "Anime", metadata = listOf("HiAnime"))))
        assertEquals(emptyList<String>(), catalogTags(Anime("a", "Anime", metadata = listOf("Dublado & Legendado"))))
    }
    @Test fun doesNotBorrowAudioClaimsFromUnverifiedAlternatives() {
        val item = Anime("a", "Anime", confirmedAudio = setOf("legendado"), alternatives = listOf(Anime("b", "Anime", metadata = listOf("Dublado"))))
        assertEquals(listOf("Legendado"), catalogTags(item))
    }
    @Test fun anyConfirmedAdultSourceProtectsTheGroupedResult() {
        assertEquals(true, aggregateAdultFlags(listOf(Anime("a", "Anime"), Anime("b", "Anime", isAdult = true))))
        assertEquals(false, aggregateAdultFlags(listOf(Anime("a", "Anime", isAdult = false))))
        assertNull(aggregateAdultFlags(listOf(Anime("a", "Anime"))))
    }
    @Test fun emptyListIsNotUsedToHideAGroupWithOtherSources() {
        assertEquals(Availability.UNKNOWN, availabilityFromEpisodeCount(null, false))
        assertEquals(Availability.LISTED, availabilityFromEpisodeCount(2, false))
        assertEquals(Availability.UNKNOWN, availabilityFromEpisodeCount(0, true))
        assertEquals(Availability.EMPTY, availabilityFromEpisodeCount(0, false))
    }
    @Test fun changingSeasonKeepsArtworkUntilNewDetailsArrive() {
        val current = Anime("show#s2", "Temporada 2", poster = "season-2-poster", banner = "season-2-art")
        val next = Anime("show#s3", "Temporada 3", poster = "series-poster", banner = "series-art")
        val pending = seasonWithRetainedArtwork(next, current)
        assertEquals("show#s3", pending.slug)
        assertEquals("Temporada 3", pending.title)
        assertEquals("season-2-art", pending.banner)
        assertEquals("season-2-poster", pending.poster)
        assertEquals(next, seasonWithRetainedArtwork(next, null))
        assertEquals(next, seasonWithRetainedArtwork(next, Anime("empty", "Sem capa")))
    }
    @Test fun keepsPosterWhenPreviousSeasonHasNoBanner() {
        val pending = seasonWithRetainedArtwork(Anime("s2", "T2", banner = "series"), Anime("s1", "T1", poster = "poster"))
        assertEquals("poster", pending.banner)
    }
    @Test fun hidesOnlyConfirmedAdultOrUnavailableContent() {
        assertFalse(showInCatalog(Anime("a", "Anime", isAdult = true), true))
        assertTrue(showInCatalog(Anime("a", "Anime", isAdult = null), true))
        assertTrue(showInCatalog(Anime("a", "Anime", isAdult = true), false))
        assertFalse(showInCatalog(Anime("a", "Anime", availability = Availability.EMPTY), false))
        assertTrue(showInCatalog(Anime("a", "Anime", availability = Availability.UNKNOWN), false))
    }
    @Test fun usesExplicitGenresAndStrongNudityTagsWithoutBlockingBroadGenres() {
        assertTrue(hasAdultContentLabels(listOf("Ecchi")))
        assertTrue(hasAdultContentLabels(listOf("Hentai")))
        assertTrue(hasAdultContentLabels(listOf("Romance"), listOf("Nudity" to 93)))
        assertFalse(hasAdultContentLabels(listOf("Harém", "Romance", "Comédia")))
        assertFalse(hasAdultContentLabels(listOf("Action"), listOf("Nudity" to 35)))
    }
    @Test fun handlesKnownUnlabeledSuggestiveTitleWithoutMatchingSimilarNames() {
        assertFalse(showInCatalog(Anime("a", "Mayoi Neko Overrun!"), true))
        assertFalse(showInCatalog(Anime("a", "Mayoi Neko Overrun"), true))
        assertTrue(showInCatalog(Anime("b", "Mayoi Neko Overrun: Another Story"), true))
        assertTrue(showInCatalog(Anime("c", "Mayoi Neko Overrun!"), false))
    }
}
