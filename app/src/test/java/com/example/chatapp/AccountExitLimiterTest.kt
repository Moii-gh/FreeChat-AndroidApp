package com.example.chatapp

import com.example.chatapp.data.AccountExitLimitStorage
import com.example.chatapp.data.AccountExitLimiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class AccountExitLimiterTest {

    @Test
    fun allowsTwoLogoutsThenBlocksUntilNextDay() {
        val storage = FakeAccountExitLimitStorage()
        val limiter = limiterAt(storage, "2026-05-15T10:00:00Z")

        assertTrue(limiter.canLogout().canLogout)
        limiter.registerLogout()
        assertTrue(limiter.canLogout().canLogout)
        limiter.registerLogout()

        val blocked = limiter.canLogout()
        assertFalse(blocked.canLogout)
        assertEquals(14L, blocked.remainingHours)
    }

    @Test
    fun resetsCounterWhenDateChanges() {
        val storage = FakeAccountExitLimitStorage()
        limiterAt(storage, "2026-05-15T10:00:00Z").apply {
            registerLogout()
            registerLogout()
        }

        val nextDayLimiter = limiterAt(storage, "2026-05-16T00:01:00Z")

        assertTrue(nextDayLimiter.canLogout().canLogout)
        assertEquals(0, storage.logoutCount)
    }

    @Test
    fun corruptedStoredDataIsTreatedAsUnusedLimit() {
        val storage = FakeAccountExitLimitStorage(
            logoutCount = 99,
            lastResetDate = "not-a-date",
            blockEndsAtMillis = -1L
        )
        val limiter = limiterAt(storage, "2026-05-15T10:00:00Z")

        assertTrue(limiter.canLogout().canLogout)
        assertEquals(0, storage.logoutCount)
        assertEquals("2026-05-15", storage.lastResetDate)
        assertEquals(0L, storage.blockEndsAtMillis)
    }

    @Test
    fun remainingHoursCanBeLessThanOneNearReset() {
        val storage = FakeAccountExitLimitStorage()
        val limiter = limiterAt(storage, "2026-05-15T23:30:00Z")
        limiter.registerLogout()
        limiter.registerLogout()

        val blocked = limiter.canLogout()

        assertFalse(blocked.canLogout)
        assertEquals(0L, blocked.remainingHours)
    }

    private fun limiterAt(
        storage: FakeAccountExitLimitStorage,
        instant: String
    ): AccountExitLimiter {
        val zone = ZoneId.of("UTC")
        return AccountExitLimiter(
            storage = storage,
            clock = Clock.fixed(Instant.parse(instant), zone)
        )
    }
}

private class FakeAccountExitLimitStorage(
    var logoutCount: Int? = null,
    var lastResetDate: String? = null,
    var blockEndsAtMillis: Long? = null
) : AccountExitLimitStorage {
    override fun readLogoutCount(): Int? = logoutCount

    override fun readLastResetDate(): String? = lastResetDate

    override fun readBlockEndsAtMillis(): Long? = blockEndsAtMillis

    override fun writeState(
        logoutCount: Int,
        lastResetDate: String,
        blockEndsAtMillis: Long
    ) {
        this.logoutCount = logoutCount
        this.lastResetDate = lastResetDate
        this.blockEndsAtMillis = blockEndsAtMillis
    }
}

