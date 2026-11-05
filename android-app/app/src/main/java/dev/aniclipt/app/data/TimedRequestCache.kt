package dev.aniclipt.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounded in-memory cache; concurrent callers share work and failures are never cached. */
internal class TimedRequestCache<K, V>(
    private val maxEntries: Int,
    private val ttlMillis: Long,
    private val shouldCache: (V) -> Boolean = { true },
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    init { require(maxEntries > 0 && ttlMillis > 0) }
    private data class Entry<V>(val value: V, val expiresAt: Long)
    private class Flight<V>(val task: Deferred<V>, var consumers: Int = 0)
    private val lock = Any()
    private val values = LinkedHashMap<K, Entry<V>>(16, .75f, true)
    private val pending = mutableMapOf<K, Flight<V>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun getOrLoad(key: K, loader: suspend () -> V): V {
        val flight = synchronized(lock) {
            val entry = values[key]
            if (entry != null && nowMillis() < entry.expiresAt) return entry.value
            if (entry != null) values.remove(key)
            val shared = pending[key] ?: run {
                lateinit var created: Flight<V>
                val task = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        val value = loader()
                        currentCoroutineContext().ensureActive()
                        if (shouldCache(value)) synchronized(lock) {
                            values[key] = Entry(value, nowMillis() + ttlMillis)
                            while (values.size > maxEntries) values.remove(values.keys.first())
                        }
                        value
                    } finally {
                        synchronized(lock) { if (pending[key] === created) pending.remove(key) }
                    }
                }
                Flight(task).also { created = it; pending[key] = it }
            }
            shared.consumers++
            shared
        }
        flight.task.start()
        try {
            return flight.task.await()
        } finally {
            synchronized(lock) {
                flight.consumers--
                if (flight.consumers == 0 && !flight.task.isCompleted) {
                    if (pending[key] === flight) pending.remove(key)
                    flight.task.cancel()
                }
            }
        }
    }
}
