package dev.aniclipt.app.data

import org.junit.Assert.*
import org.junit.Test

class EpisodeNavigationTest {
    private val episodes = listOf(Episode("a", "1", "Um"), Episode("b", "2", "Dois"), Episode("c", "3", "Três"))
    @Test fun movesWithinCurrentSeasonAndStopsAtEnds() {
        assertNull(adjacentEpisode(episodes, episodes.first(), -1))
        assertEquals(episodes[1], adjacentEpisode(episodes, episodes[0], 1))
        assertEquals(episodes[1], adjacentEpisode(episodes, episodes[2], -1))
        assertNull(adjacentEpisode(episodes, episodes.last(), 1))
    }
    @Test fun findsSavedEpisodeByNumberWhenIdChanged() {
        assertEquals(episodes[2], adjacentEpisode(episodes, Episode("old", "2.0", "Dois"), 1))
        assertNull(adjacentEpisode(episodes, Episode("unknown", "99", "Outro"), 1))
        assertNull(adjacentEpisode(episodes, null, 1))
    }
}
