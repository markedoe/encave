package app.encave

import android.os.Looper
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * Runs the main looper until [condition] holds, for work that hops to Dispatchers.IO and back.
 * Fails after [timeoutMs] on a monotonic clock, rather than going on as if it had held.
 */
fun idleMainLooperUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
    val looper = shadowOf(Looper.getMainLooper())
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (true) {
        looper.idle()
        if (condition()) return
        if (System.nanoTime() - deadline > 0) throw AssertionError("Not reached within $timeoutMs ms")
        // The background thread has not posted back yet.
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5))
    }
}
