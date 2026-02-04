package com.example.ctrl.server

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

class RateLimiter(
    private val capacity: Double,
    private val refillTokensPerSecond: Double,
) {
    private data class Bucket(var tokens: Double, var lastRefillMs: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun tryAcquire(key: String, tokens: Double = 1.0): Boolean {
        val now = System.currentTimeMillis()
        val bucket = buckets.computeIfAbsent(key) { Bucket(capacity, now) }
        synchronized(bucket) {
            val elapsedMs = (now - bucket.lastRefillMs).coerceAtLeast(0)
            val refill = elapsedMs / 1000.0 * refillTokensPerSecond
            bucket.tokens = min(capacity, bucket.tokens + refill)
            bucket.lastRefillMs = now

            if (bucket.tokens >= tokens) {
                bucket.tokens -= tokens
                return true
            }
            return false
        }
    }
}
