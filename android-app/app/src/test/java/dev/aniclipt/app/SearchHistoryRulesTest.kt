package dev.aniclipt.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHistoryRulesTest {
    @Test fun confirmedSearchMovesToFrontWithoutDuplicate() {
        assertEquals(listOf("Black Clover", "Bleach"), addSearchHistory(listOf("Bleach", "black clover"), "  Black   Clover "))
    }

    @Test fun boundedHistoryAndRemoval() {
        val history = (1..8).map { "Anime $it" }
        assertEquals(8, addSearchHistory(history, "Novo anime").size)
        assertEquals(emptyList<String>(), removeSearchHistory(listOf("BLEACH", "Bleach"), "bleach"))
    }
}
