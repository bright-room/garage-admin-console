package net.brightroom.garage.shared.session

import kotlin.test.Test
import kotlin.test.assertEquals

class IdleTrackerTest {

    private val minute = 60_000L
    private val timeout = 30 * minute
    private val warning = 1 * minute

    private fun tracker(startedAt: Long = 0) =
        IdleTracker(startedAtMillis = startedAt, timeoutMillis = timeout, warningMillis = warning)

    @Test
    fun isActiveImmediatelyAfterStart() {
        assertEquals(IdleState.ACTIVE, tracker().state(0))
    }

    @Test
    fun staysActiveBeforeWarningThreshold() {
        assertEquals(IdleState.ACTIVE, tracker().state(28 * minute))
    }

    @Test
    fun warnsOneMinuteBeforeTimeout() {
        assertEquals(IdleState.WARNING, tracker().state(29 * minute))
        assertEquals(IdleState.WARNING, tracker().state(29 * minute + 30_000))
    }

    @Test
    fun expiresAtTimeout() {
        assertEquals(IdleState.EXPIRED, tracker().state(30 * minute))
        assertEquals(IdleState.EXPIRED, tracker().state(31 * minute))
    }

    @Test
    fun activityResetsTheClock() {
        val tracker = tracker()

        tracker.recordActivity(29 * minute)

        assertEquals(IdleState.ACTIVE, tracker.state(29 * minute))
        assertEquals(IdleState.ACTIVE, tracker.state(50 * minute))
        assertEquals(IdleState.WARNING, tracker.state(58 * minute))
        assertEquals(IdleState.EXPIRED, tracker.state(59 * minute))
    }

    @Test
    fun reportsRemainingTime() {
        val tracker = tracker()

        assertEquals(30 * minute, tracker.remainingMillis(0))
        assertEquals(10 * minute, tracker.remainingMillis(20 * minute))
        assertEquals(0, tracker.remainingMillis(45 * minute))
    }
}
