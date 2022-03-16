package dev.forst.ktor.ratelimiting

import kotlinx.coroutines.sync.Mutex
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicLong


/**
 * Linear implementation of the rate limiting. If [limit] is depleted during the [window]
 * from the first request, it denies access.
 */
class LinearRateLimiter(
    /**
     * How many request can the user perform in the [window].
     */
    private val limit: Long,
    /**
     * Duration after which the limiter resets.
     */
    private val window: Duration,
    /**
     * How many records can be stored before the limiter purges the cache.
     */
    private val purgeHitSize: Int = DEFAULT_PURGE_HIT_SIZE,
    /**
     * How often should limiter perform purge operation.
     */
    private val purgeHitDuration: Duration = DEFAULT_PURGE_HIT_DURATION,
    /**
     * Function that computes current time, by default [Instant.now], but we want to
     * make it testable.
     */
    private val nowProvider: () -> Instant = { Instant.now() },
    /**
     * Initial size of the cache.
     */
    initialSize: Int = DEFAULT_INITIAL_CACHE_SIZE
) {
    private val records: ConcurrentMap<String, RateLimit> = ConcurrentHashMap(initialSize)

    private val purgeMutex = Mutex()
    private var lastPurge = nowProvider()

    private data class RateLimit(val resetsAt: Instant, val remainingRequests: AtomicLong)

    /**
     * Logs request attempt from the [remoteHost].
     *
     * Returns [Long] - the amount of seconds when the next request will be possible -
     * when the [remoteHost] has zero requests left and should be filtered.
     *
     *  Otherwise returns null.
     */
    fun processRequest(remoteHost: String): Long? {
        val now = nowProvider()
        val rate = records.compute(remoteHost) { _, maybeRate -> rateLimitOrDefault(maybeRate, now) }
        // if necessary, cleanup the records
        purgeIfNecessary(now)
        // return current rate limit decision
        return rate?.let {
            // log request and check if there are any requests left
            // if no requests are left, return the seconds that the host needs to wait for another request
            if (it.remainingRequests.decrementAndGet() <= 0) it.resetsAt.epochSecond - now.epochSecond
            else null
        }
    }

    /**
     * Clear [records] to keep small memory footprint.
     * This operation is thread/coroutine safe as it uses [purgeMutex] while executing the purge.
     */
    private fun purgeIfNecessary(now: Instant) {
        if (records.size > purgeHitSize && Duration.between(lastPurge, now) > purgeHitDuration) {
            // try to lock the mutex, if this fails, it is not necessary to start the purge
            // as another coroutine already acquired the lock and executed purge
            val locked = purgeMutex.tryLock()
            // if the lock was success, execute purge and unlock the lock at the end of the purge
            if (locked) {
                try {
                    purge(now)
                    lastPurge = now
                } finally {
                    purgeMutex.unlock()
                }
            }
        }
    }

    /**
     * Find and delete all records, that can be deleted because they expired.
     *
     * Note that this method is tread safe as it manipulates only with [records] that is concurrent map.
     */
    private fun purge(now: Instant) = records
        .mapNotNull { (key, rateLimit) -> if (rateLimit.resetsAt <= now) key else null } // copy the keys to new collection
        .forEach { records.remove(it) } // try to remove all records that should be purged

    /**
     * Build new [RateLimit] instance from the class parameters.
     */
    private fun defaultRate(now: Instant) = RateLimit(now.plus(window), AtomicLong(limit))

    /**
     * Check if limit [maybeRateLimit] exists or should be reset. If so, create new [RateLimit] instance,
     * otherwise return [maybeRateLimit].
     */
    private fun rateLimitOrDefault(maybeRateLimit: RateLimit?, now: Instant) =
        if (maybeRateLimit == null || maybeRateLimit.resetsAt <= now) defaultRate(now)
        else maybeRateLimit
}
