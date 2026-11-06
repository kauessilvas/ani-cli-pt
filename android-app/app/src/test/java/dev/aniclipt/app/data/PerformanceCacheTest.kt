package dev.aniclipt.app.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureNanoTime

class PerformanceCacheTest {
    @Test fun warmEpisodeListCacheAvoidsNetworkWorkAndReducesLatency() = runBlocking {
        val loads = AtomicInteger()
        val episodePayload = List(500) { index -> Episode("ep-$index", (index + 1).toString(), "Episódio ${index + 1}") }
        val cache = TimedRequestCache<String, List<Episode>>(maxEntries = 8, ttlMillis = 60_000)

        val coldReadNanos = measureNanoTime {
            cache.getOrLoad("one-piece-season-1") {
                loads.incrementAndGet()
                delay(80)
                episodePayload
            }
        }
        var warmResult = emptyList<Episode>()
        val warmReadsNanos = measureNanoTime {
            repeat(500) {
                warmResult = cache.getOrLoad("one-piece-season-1") {
                    loads.incrementAndGet()
                    error("Um cache aquecido não deve repetir a requisição")
                }
            }
        }

        assertEquals(500, warmResult.size)
        assertEquals(1, loads.get())
        assertTrue("500 leituras aquecidas devem custar menos que uma carga fria", warmReadsNanos < coldReadNanos)
    }
}
