package io.resultguesser.cache

import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal thread-safe TTL cache. Values are recomputed lazily when expired.
 * Used to avoid hammering the scraper and the stats API on every request.
 */
class TtlCache<K : Any, V : Any>(private val ttlMillis: Long) {

    private data class Entry<V>(val value: V, val expiresAt: Long)

    private val map = ConcurrentHashMap<K, Entry<V>>()

    /** Returns the cached value if fresh, otherwise computes, stores and returns it. */
    inline fun getOrPut(key: K, compute: () -> V): V {
        get(key)?.let { return it }
        val value = compute()
        put(key, value)
        return value
    }

    fun get(key: K): V? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() > entry.expiresAt) {
            map.remove(key)
            return null
        }
        return entry.value
    }

    fun put(key: K, value: V) {
        map[key] = Entry(value, System.currentTimeMillis() + ttlMillis)
    }
}
