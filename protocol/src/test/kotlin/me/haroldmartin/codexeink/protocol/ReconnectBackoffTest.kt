package me.haroldmartin.codexeink.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReconnectBackoffTest {
    @Test
    fun `grows exponentially and caps`() {
        val backoff = ReconnectBackoff(
            policy = ReconnectPolicy(
                initialDelayMillis = 100,
                maxDelayMillis = 1_000,
                multiplier = 2.0,
                jitterRatio = 0.0,
            ),
        )

        assertEquals(listOf(100L, 200L, 400L, 800L, 1_000L, 1_000L), List(6) { backoff.nextDelayMillis() })
    }

    @Test
    fun `applies bounded symmetric jitter`() {
        val low = ReconnectBackoff(
            ReconnectPolicy(1_000, 10_000, jitterRatio = 0.2),
            randomUnit = { 0.0 },
        )
        val high = ReconnectBackoff(
            ReconnectPolicy(1_000, 10_000, jitterRatio = 0.2),
            randomUnit = { 1.0 },
        )

        assertEquals(800L, low.nextDelayMillis())
        assertEquals(1_200L, high.nextDelayMillis())
    }

    @Test
    fun `honors max attempts and reset`() {
        val backoff = ReconnectBackoff(
            ReconnectPolicy(initialDelayMillis = 5, maxDelayMillis = 20, jitterRatio = 0.0, maxAttempts = 2),
        )
        assertEquals(5L, backoff.nextDelayMillis())
        assertEquals(10L, backoff.nextDelayMillis())
        assertNull(backoff.nextDelayMillis())
        backoff.reset()
        assertEquals(5L, backoff.nextDelayMillis())
    }

    @Test
    fun `large attempts do not overflow`() {
        val backoff = ReconnectBackoff(
            ReconnectPolicy(initialDelayMillis = 1, maxDelayMillis = 60_000, jitterRatio = 0.0),
        )
        assertEquals(60_000L, backoff.delayForAttempt(1_000))
    }
}
