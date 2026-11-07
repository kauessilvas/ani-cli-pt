package dev.aniclipt.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AniSkipRepositoryTest {
    private val opening = SkipInterval("op", 57_397, 145_737)
    private val ending = SkipInterval("ed", 1_357_923, 1_480_000)

    @Test fun showsSkipOnlyInsideItsInterval() {
        assertNull(activeSkipInterval(listOf(opening), 57_396, 1_490_000))
        assertEquals(opening, activeSkipInterval(listOf(opening), 57_397, 1_490_000))
        assertNull(activeSkipInterval(listOf(opening), 145_737, 1_490_000))
    }

    @Test fun offersEndingWhenItFitsTheEpisode() {
        assertEquals(ending, activeSkipInterval(listOf(opening, ending), 1_400_000, 1_490_000))
        assertNull(activeSkipInterval(listOf(ending), 1_400_000, 1_200_000))
    }
}
