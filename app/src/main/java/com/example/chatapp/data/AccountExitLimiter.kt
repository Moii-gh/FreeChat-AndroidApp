package com.example.chatapp.data

import android.content.Context
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

data class AccountExitLimitResult(
    val canLogout: Boolean,
    val remainingHours: Long,
    val remainingMillis: Long
)

class AccountExitLimiter internal constructor(
    private val storage: AccountExitLimitStorage,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val maxLogoutsPerDay: Int = MAX_LOGOUTS_PER_DAY
) {
    constructor(context: Context) : this(
        storage = SharedPreferencesAccountExitLimitStorage(
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        )
    )

    @Synchronized
    fun canLogout(): AccountExitLimitResult {
        val state = normalizedState()
        if (state.logoutCount < maxLogoutsPerDay) {
            return AccountExitLimitResult(
                canLogout = true,
                remainingHours = 0L,
                remainingMillis = 0L
            )
        }

        val blockEndsAtMillis = state.blockEndsAtMillis
            .takeIf { it > nowMillis() }
            ?: nextResetMillis().also { resetMillis ->
                storage.writeState(
                    logoutCount = state.logoutCount,
                    lastResetDate = state.lastResetDate,
                    blockEndsAtMillis = resetMillis
                )
            }
        return blockedResult(blockEndsAtMillis)
    }

    @Synchronized
    fun registerLogout() {
        val state = normalizedState()
        val nextCount = (state.logoutCount + 1).coerceAtMost(maxLogoutsPerDay)
        val blockEndsAtMillis = if (nextCount >= maxLogoutsPerDay) {
            nextResetMillis()
        } else {
            0L
        }
        storage.writeState(
            logoutCount = nextCount,
            lastResetDate = todayKey(),
            blockEndsAtMillis = blockEndsAtMillis
        )
    }

    private fun normalizedState(): AccountExitLimitState {
        val storedState = readStoredState()
        val today = todayKey()
        if (storedState == null || storedState.lastResetDate != today) {
            return resetState(today)
        }
        return storedState
    }

    private fun readStoredState(): AccountExitLimitState? {
        val logoutCount = storage.readLogoutCount() ?: return null
        val lastResetDate = storage.readLastResetDate() ?: return null
        val blockEndsAtMillis = storage.readBlockEndsAtMillis() ?: return null

        if (logoutCount !in 0..maxLogoutsPerDay || blockEndsAtMillis < 0L) {
            return null
        }

        try {
            LocalDate.parse(lastResetDate)
        } catch (_: DateTimeParseException) {
            return null
        }

        return AccountExitLimitState(
            logoutCount = logoutCount,
            lastResetDate = lastResetDate,
            blockEndsAtMillis = blockEndsAtMillis
        )
    }

    private fun resetState(today: String): AccountExitLimitState {
        storage.writeState(
            logoutCount = 0,
            lastResetDate = today,
            blockEndsAtMillis = 0L
        )
        return AccountExitLimitState(
            logoutCount = 0,
            lastResetDate = today,
            blockEndsAtMillis = 0L
        )
    }

    private fun blockedResult(blockEndsAtMillis: Long): AccountExitLimitResult {
        val remainingMillis = (blockEndsAtMillis - nowMillis()).coerceAtLeast(0L)
        return AccountExitLimitResult(
            canLogout = false,
            remainingHours = TimeUnit.MILLISECONDS.toHours(remainingMillis),
            remainingMillis = remainingMillis
        )
    }

    private fun nowMillis(): Long = clock.millis()

    private fun todayKey(): String = LocalDate.now(clock).toString()

    private fun nextResetMillis(): Long =
        LocalDate.now(clock)
            .plusDays(1)
            .atStartOfDay(clock.zone)
            .toInstant()
            .toEpochMilli()

    private data class AccountExitLimitState(
        val logoutCount: Int,
        val lastResetDate: String,
        val blockEndsAtMillis: Long
    )

    private companion object {
        const val PREFS_NAME = "account_exit_limiter_prefs"
        const val MAX_LOGOUTS_PER_DAY = 2
    }
}

internal interface AccountExitLimitStorage {
    fun readLogoutCount(): Int?
    fun readLastResetDate(): String?
    fun readBlockEndsAtMillis(): Long?
    fun writeState(logoutCount: Int, lastResetDate: String, blockEndsAtMillis: Long)
}
