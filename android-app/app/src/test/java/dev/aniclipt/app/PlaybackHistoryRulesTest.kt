package dev.aniclipt.app

import org.junit.Assert.*
import org.junit.Test

class PlaybackHistoryRulesTest {
    @Test fun storesEpisodeOnce() {
        assertEquals("anime:1,anime:2", addWatchedEpisode("anime:1", "anime", "2"))
        assertEquals("anime:1", addWatchedEpisode("anime:1", "anime", "1"))
    }
    @Test fun limitsHistoryToMostRecent500Episodes() {
        val old = (1..500).joinToString(",") { "anime:$it" }
        val result = addWatchedEpisode(old, "anime", "501").split(',')
        assertEquals(500, result.size)
        assertEquals("anime:2", result.first())
        assertEquals("anime:501", result.last())
    }
    @Test fun resumePositionClampsNegativeAndResetsWhenCompleted() {
        assertEquals(14000L, savedResumePosition(14000, false))
        assertEquals(0L, savedResumePosition(-1, false))
        assertEquals(0L, savedResumePosition(14000, true))
    }
}
