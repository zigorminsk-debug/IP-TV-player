package com.iptvplayer.app.parental

import com.iptvplayer.app.data.repo.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Parental control: channels/categories marked as locked require a PIN.
 * A successful unlock grants access for a limited in-memory session.
 */
class ParentalManager(private val settings: SettingsRepository) {

    @Volatile
    private var unlockedUntilMs: Long = 0

    val isUnlocked: Boolean
        get() = System.currentTimeMillis() < unlockedUntilMs

    fun markUnlocked(durationMs: Long = DEFAULT_UNLOCK_MS) {
        unlockedUntilMs = System.currentTimeMillis() + durationMs
    }

    fun lock() {
        unlockedUntilMs = 0
    }

    suspend fun isEnabled(): Boolean =
        settings.settings.first().parentalEnabled

    /** Verifies the PIN; on success marks the session as unlocked. */
    suspend fun verifyAndUnlock(pin: String): Boolean {
        val ok = settings.checkPin(pin)
        if (ok) markUnlocked()
        return ok
    }

    suspend fun hasPin(): Boolean = settings.hasPin()

    companion object {
        const val DEFAULT_UNLOCK_MS: Long = 10 * 60_000L
    }
}
