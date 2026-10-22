package app.encave.core.sync

import java.time.Duration
import java.time.Instant

/**
 * Whether sync needs the user, for a sign outside Settings › Devices (the Settings button,
 * the Devices row). Worked out on the device from what sync knows. Mirrors CaveKit's `SyncHealth`.
 */
sealed class SyncHealth {
    data object Ok : SyncHealth()

    /** Sync waits for the user: [reason] says what to do in Devices. */
    data class NeedsAction(val reason: Reason) : SyncHealth()

    /** Sync keeps failing: no success for an hour, or a problem with the storage away from home. */
    data object Failing : SyncHealth()

    enum class Reason {
        /** Another device removed this one. */
        Removed,

        /** The WebDAV folder has no login on this device. */
        SignInNeeded,

        /** This device's key could not be read. */
        KeyUnavailable,

        /** Local network access is off for the app. */
        LocalNetworkOff,
    }

    companion object {
        /** How long failures may last before sync counts as failing. */
        val FAILING_AFTER: Duration = Duration.ofHours(1)

        /**
         * [localFailingSince], [remoteFailingSince]: the first failure since the last success.
         * [remoteProblem]: a problem that is not a failed round (files refused, changes that
         * cannot be read).
         */
        fun evaluate(
            isShared: Boolean,
            removed: Boolean,
            keyUnavailable: Boolean,
            signInNeeded: Boolean,
            localNetworkOff: Boolean,
            localFailingSince: Instant?,
            remoteFailingSince: Instant?,
            remoteProblem: Boolean,
            now: Instant = Instant.now(),
        ): SyncHealth {
            if (removed) return NeedsAction(Reason.Removed)
            if (!isShared) return Ok
            if (keyUnavailable) return NeedsAction(Reason.KeyUnavailable)
            if (signInNeeded) return NeedsAction(Reason.SignInNeeded)
            if (localNetworkOff) return NeedsAction(Reason.LocalNetworkOff)
            val limit = now.minus(FAILING_AFTER)
            if (remoteProblem || listOfNotNull(localFailingSince, remoteFailingSince).any { !it.isAfter(limit) }) return Failing
            return Ok
        }
    }
}

/**
 * When to try a device on the network again after a session with it failed: after 15
 * seconds, then 1 minute, then 5 minutes; no more until it syncs, leaves the network and
 * comes back, or the user taps Sync now. Mirrors CaveKit's `SyncRetry`.
 */
object SyncRetry {
    val DELAYS_MS = listOf(15_000L, 60_000L, 300_000L)

    /** The wait after [failures] failed sessions in a row, null when there is no more retry. */
    fun delayMs(afterFailures: Int): Long? = DELAYS_MS.getOrNull(afterFailures - 1)
}
