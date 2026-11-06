package dev.aniclipt.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchExpansionPolicyTest {
    @Test fun skipsExtraTitleSearchWhenMultipleProvidersAlreadyMatch() {
        val direct = listOf(
            Anime("fire:bleach", "Bleach", metadata = listOf("AnimeFire")),
            Anime("hi:bleach", "Bleach", metadata = listOf("HiAnime")),
        )

        assertFalse(needsRelatedTitleSearch("Bleach", direct))
    }

    @Test fun keepsAliasSearchWhenDirectMatchesAreMissingOrComeFromOneProvider() {
        assertTrue(needsRelatedTitleSearch("Os sete pecados capitais", emptyList()))
        assertTrue(needsRelatedTitleSearch("Os sete pecados capitais", listOf(
            Anime("fire:nanatsu", "Os Sete Pecados Capitais", metadata = listOf("AnimeFire")),
        )))
    }
}
