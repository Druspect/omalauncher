package dev.omalauncher

import android.content.Context
import android.content.Intent
import android.net.LocalServerSocket
import java.io.IOException

/**
 * Which omalauncher has Linux online. The store app and test builds next to it (.adreno, .mod) each have their own
 * Linux; only one runs at a time. The online one holds an abstract Unix socket name, and a second one with its
 * package after it. The kernel frees both when its process ends, even in a crash, so a stale claim can't outlive it.
 * Names, not connections: any app can tell whether a name is taken, whoever's signature the builds carry.
 */
class Presence(
    private val context: Context,
) {
    private var held: List<LocalServerSocket>? = null

    val isOnline get() = held != null

    /** Takes the claim; false if another build holds it. */
    fun claim(): Boolean {
        if (held != null) return true
        // Its own name first, so a build that finds the lock taken can always tell whose it is. A probe from
        // [holder] can hold the name for a moment, hence the retries.
        val mine = retry { bind("$LOCK.${context.packageName}") }
        val lock = bind(LOCK)
        if (lock == null) {
            mine?.close()
            return false
        }
        held = listOfNotNull(lock, mine)
        return true
    }

    fun release() {
        held?.forEach { runCatching { it.close() } }
        held = null
    }

    /** The build whose Linux is online, if it is one that can hand it over; asked a few times, in case it's starting. */
    fun holder(): String? {
        val builds = builds()
        return retry { builds.firstOrNull { pkg -> bind("$LOCK.$pkg")?.close() == null } }
    }

    /** [attempt] until it gives something, a few times over a fifth of a second. Rare: only when two builds meet. */
    private fun <T> retry(attempt: () -> T?): T? {
        repeat(5) {
            attempt()?.let { return it }
            Thread.sleep(40)
        }
        return null
    }

    /** Other installed builds: the ones that answer [ACTION_HAND_OVER]. */
    fun builds(): List<String> =
        context.packageManager
            .queryIntentActivities(Intent(ACTION_HAND_OVER), 0)
            .map { it.activityInfo.packageName }
            .filter { it != context.packageName }
            .distinct()

    private fun bind(name: String): LocalServerSocket? =
        try {
            LocalServerSocket(name)
        } catch (_: IOException) {
            null
        }

    companion object {
        private const val LOCK = "omalauncher.linux"

        /** Asks the build that has Linux online to stop it, for the caller (see HandOverActivity). Not per package. */
        const val ACTION_HAND_OVER = "dev.omalauncher.action.HAND_OVER_LINUX"
    }
}
