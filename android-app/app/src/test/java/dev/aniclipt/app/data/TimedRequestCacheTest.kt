package dev.aniclipt.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TimedRequestCacheTest {
    @Test fun concurrentRequestsShareOneLoad() = runBlocking {
        val count = AtomicInteger()
        val cache = TimedRequestCache<String, String>(10, 1000)
        val results = (1..8).map { async { cache.getOrLoad("same") { count.incrementAndGet(); delay(40); "ok" } } }.awaitAll()
        assertEquals(List(8) { "ok" }, results)
        assertEquals(1, count.get())
    }
    @Test fun entriesExpireAndLeastRecentlyUsedIsEvicted() = runBlocking {
        var now = 0L
        var loads = 0
        val cache = TimedRequestCache<String, String>(2, 100, nowMillis = { now })
        suspend fun get(key: String) = cache.getOrLoad(key) { loads++; key }
        get("a"); get("b"); get("a"); get("c"); get("b")
        assertEquals(4, loads)
        now = 101
        get("b")
        assertEquals(5, loads)
    }
    @Test fun cancelingOneCallerDoesNotCancelSharedRequest() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val count = AtomicInteger()
        val cache = TimedRequestCache<String, String>(2, 1000)
        val first = async(start = CoroutineStart.UNDISPATCHED) { cache.getOrLoad("a") { count.incrementAndGet(); started.complete(Unit); release.await(); "ok" } }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { cache.getOrLoad("a") { error("Não deveria iniciar outra requisição") } }
        first.cancelAndJoin()
        release.complete(Unit)
        assertEquals("ok", second.await())
        assertEquals(1, count.get())
    }
    @Test fun canceledOrphanDoesNotPreventRetry() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cache = TimedRequestCache<String, String>(2, 1000)
        val first = async(start = CoroutineStart.UNDISPATCHED) { cache.getOrLoad("a") { started.complete(Unit); delay(10_000); "antigo" } }
        started.await()
        first.cancelAndJoin()
        assertEquals("novo", cache.getOrLoad("a") { "novo" })
    }
    @Test fun failuresAndEmptyValuesAreNotCached() = runBlocking {
        var attempts = 0
        val cache = TimedRequestCache<String, String>(2, 1000, shouldCache = { it.isNotBlank() })
        try { cache.getOrLoad("a") { attempts++; error("rede") }; fail("Esperava falha") } catch (_: IllegalStateException) { }
        cache.getOrLoad("a") { attempts++; "" }
        assertEquals("ok", cache.getOrLoad("a") { attempts++; "ok" })
        assertEquals(3, attempts)
    }
}
