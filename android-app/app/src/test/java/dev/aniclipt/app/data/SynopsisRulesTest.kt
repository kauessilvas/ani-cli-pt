package dev.aniclipt.app.data

import org.junit.Assert.*
import org.junit.Test

class SynopsisRulesTest {
    @Test fun ignoresNullLikeValues() {
        assertEquals("", cleanSynopsis(" NULL "))
        assertEquals("", cleanSynopsis("undefined"))
        assertEquals("Texto válido", cleanSynopsis(" Texto válido "))
    }
    @Test fun synopsisSelectionRequiresCloseTitleAndRejectsAmbiguousMatches() {
        val correct = SynopsisCandidate("Sinopse correta", listOf("Example Story"))
        val unrelated = SynopsisCandidate("Outra sinopse", listOf("Example Story Next Generation"))
        assertEquals("Sinopse correta", chooseLocalizedSynopsis("Example Story", "TV", listOf(correct, unrelated)))
        assertNull(chooseLocalizedSynopsis("Example Story", "TV", listOf(correct, SynopsisCandidate("Sinopse conflitante", listOf("Example Story")))))
        assertNull(chooseLocalizedSynopsis("Example Story", "TV", listOf(SynopsisCandidate("null", listOf("Example Story")))))
    }
    @Test fun keepsKnownPortugueseButWaitsForLocalizationOfEnglishSource() {
        assertEquals("Uma história", initialSynopsis(Anime("source#s1", "Anime", synopsis = "Uma história")))
        assertEquals("", initialSynopsis(Anime("hi:source", "Anime", synopsis = "An English story")))
    }
}
