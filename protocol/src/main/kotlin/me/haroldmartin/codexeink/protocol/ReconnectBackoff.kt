package me.haroldmartin.codexeink.protocol

import kotlin.math.pow
import kotlin.math.roundToLong

data class ReconnectPolicy(
    val initialDelayMillis: Long = 500,
    val maxDelayMillis: Long = 30_000,
    val multiplier: Double = 2.0,
    val jitterRatio: Double = 0.2,
    val maxAttempts: Int? = null,
) {
    init {
        require(initialDelayMillis >= 0)
        require(maxDelayMillis >= initialDelayMillis)
        require(multiplier >= 1.0)
        require(jitterRatio in 0.0..1.0)
        require(maxAttempts == null || maxAttempts >= 0)
    }
}

class ReconnectBackoff(
    val policy: ReconnectPolicy = ReconnectPolicy(),
    private val randomUnit: () -> Double = Math::random,
) {
    var attempt: Int = 0
        private set

    fun nextDelayMillis(): Long? {
        if (policy.maxAttempts != null && attempt >= policy.maxAttempts) return null
        val delay = delayForAttempt(attempt)
        attempt++
        return delay
    }

    fun delayForAttempt(attempt: Int): Long {
        require(attempt >= 0)
        val exponential = if (attempt > 62) {
            policy.maxDelayMillis.toDouble()
        } else {
            policy.initialDelayMillis * policy.multiplier.pow(attempt.toDouble())
        }
        val capped = exponential.coerceAtMost(policy.maxDelayMillis.toDouble())
        if (capped == 0.0 || policy.jitterRatio == 0.0) return capped.roundToLong()
        val unit = randomUnit().coerceIn(0.0, 1.0)
        val jitter = (unit * 2.0 - 1.0) * policy.jitterRatio
        return (capped * (1.0 + jitter)).roundToLong().coerceIn(0, policy.maxDelayMillis)
    }

    fun reset() {
        attempt = 0
    }
}
