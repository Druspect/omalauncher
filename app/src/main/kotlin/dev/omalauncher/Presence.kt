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
        val lock = bind(LOCK) ?: return false
        held = listOfNotNull(lock, bind("$LOCK.${context.packageName}"))
        return true
    }

    fun release() {
        held?.forEach { runCatching { it.close() } }
        held = null
    }

    /** The build whose Linux is online, if it is one that can hand it over. */
    fun holder(): String? = builds().firstOrNull { pkg -> bind("$LOCK.$pkg")?.close() == null }

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
