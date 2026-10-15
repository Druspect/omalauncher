package dev.omalauncher.linux

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.system.Os
import android.util.Log
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import java.io.File
import kotlin.concurrent.thread

private const val TAG = "open"

/**
 * `xdg-open` for the guest: URLs are written to a FIFO in the shared runtime
 * dir and opened with Android's browser. That's what makes agent logins work:
 * the OAuth page opens on the phone and redirects back to the agent's
 * localhost listener, since the guest shares the phone's network.
 */
class OpenBridge(
    private val context: Context,
    private val proot: Proot,
    private val onOpenFiles: (String) -> Unit,
    /** A guest path to edit, and the host path of the FIFO that gets the reply once it's closed. */
    private val onEdit: (String, String) -> Unit,
    /** `omawatch-reply`: text for the watch's open request. */
    private val onWatchReply: (String) -> Unit = {},
    /** `omawatch-say`: text to speak. */
    private val onSay: (String) -> Unit = {},
) {
    private val fifo = File(proot.runtimeDir, "open.fifo")
    private val guestFifo = "${Proot.GUEST_RUNTIME_DIR}/${fifo.name}"

    private var started = false

    fun start() {
        if (started) return
        started = true
        fifo.delete()
        Os.mkfifo(fifo.path, 0x180) // 0600
        thread(name = "open-bridge", isDaemon = true) {
            while (true) {
                // Each writer closes the FIFO after one URL; reopen for the next.
                try {
                    fifo.bufferedReader().useLines { lines -> lines.forEach(::open) }
                } catch (e: Exception) {
                    Log.e(TAG, "bridge stopped", e)
                    return@thread
                }
            }
        }
    }

    private fun open(target: String) {
        val url = target.trim()
        // Written by the omarchy-files shim: open the Files app at a guest path.
        if (url.startsWith(FILES_PREFIX)) return onOpenFiles(url.removePrefix(FILES_PREFIX))
        // Written by the omarchy-edit shim: its reply FIFO (no spaces, made by mktemp) and the file.
        if (url.startsWith(EDIT_PREFIX)) {
            val (reply, path) = url.removePrefix(EDIT_PREFIX).split(' ', limit = 2).takeIf { it.size == 2 } ?: return
            return onEdit(path, proot.hostFile(reply).path)
        }
        if (url.startsWith(WATCH_REPLY_PREFIX)) return onWatchReply(url.removePrefix(WATCH_REPLY_PREFIX))
        if (url.startsWith(SAY_PREFIX)) return onSay(url.removePrefix(SAY_PREFIX))
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Log.i(TAG, "ignoring non-URL $url")
            return
        }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no browser for $url")
        }
    }

    /** The shims; /usr/local/bin precedes /usr/bin, so xdg-open wins over xdg-utils if that's installed. */
    fun managed(): List<Managed> {
        val bin = File(proot.rootfs, "usr/local/bin")
        return listOf(
            Managed.text(File(bin, "xdg-open"), Policy.Owned, executable = true) {
                """
                #!/bin/sh
                # omalauncher: opens URLs in the Android browser (see OpenBridge.kt)
                timeout 2 sh -c 'printf "%s\n" "${'$'}1" > $guestFifo' sh "${'$'}1"
                """.trimIndent() + "\n"
            },
            Managed.text(File(bin, FILES_COMMAND), Policy.Owned, executable = true) {
                """
                #!/bin/sh
                # omalauncher: opens the launcher's Files app, at a folder if given (see OpenBridge.kt)
                dir=${'$'}(realpath -- "${'$'}{1:-.}" 2>/dev/null) || dir=${'$'}PWD
                [ -d "${'$'}dir" ] || dir=${'$'}(dirname -- "${'$'}dir")
                timeout 2 sh -c 'printf "%s\n" "${'$'}1" > $guestFifo' sh "$FILES_PREFIX${'$'}dir"
                """.trimIndent() + "\n"
            },
            Managed.text(File(bin, EDIT_COMMAND), Policy.Owned, executable = true) {
                """
                #!/bin/sh
                # omalauncher: $EDIT_COMMAND, Linux's ${'$'}EDITOR: edits a file in the launcher's editor and waits
                # until it's closed. micro gets anything else (options, several files, a folder), files the
                # editor can't take (big, binary), and everything while the launcher doesn't answer (see OpenBridge.kt).
                [ ${'$'}# -eq 1 ] && [ ! -d "${'$'}1" ] || exec micro "${'$'}@"
                case ${'$'}1 in -* | +*) exec micro "${'$'}@" ;; esac
                file=${'$'}(realpath -m -- "${'$'}1") || exec micro "${'$'}@"
                reply=${'$'}(mktemp -u ${Proot.GUEST_RUNTIME_DIR}/edit.XXXXXX)
                mkfifo -m 600 "${'$'}reply" || exec micro "${'$'}@"
                trap 'rm -f "${'$'}reply"; exit 130' INT TERM HUP
                if timeout 2 sh -c 'printf "%s\n" "${'$'}1" > $guestFifo' sh "$EDIT_PREFIX${'$'}reply ${'$'}file"; then
                    read -r answer < "${'$'}reply"
                    rm -f "${'$'}reply"
                    [ "${'$'}answer" = micro ] && exec micro "${'$'}@"
                    exit 0
                fi
                rm -f "${'$'}reply"
                exec micro "${'$'}@"
                """.trimIndent() + "\n"
            },
            watchShim(WATCH_REPLY_COMMAND, WATCH_REPLY_PREFIX, "shows text on the watch, as the reply to its open request"),
            watchShim(SAY_COMMAND, SAY_PREFIX, "speaks text, in Bluetooth headphones if connected, else on the phone"),
            // What an agent reads before every request from the watch; the owner's to edit.
            Managed.text(File(proot.home, WATCH_PROFILE), Policy.Seeded) { WATCH_PROFILE_TEXT },
        )
    }

    /** One line, whatever the arguments or stdin held: the FIFO takes a line per message. */
    private fun watchShim(
        command: String,
        prefix: String,
        what: String,
    ) = Managed.text(File(proot.rootfs, "usr/local/bin/$command"), Policy.Owned, executable = true) {
        """
        #!/bin/sh
        # omalauncher: $command "<text>" $what (see OpenBridge.kt)
        if [ ${'$'}# -gt 0 ]; then text="${'$'}*"; else text=${'$'}(cat); fi
        text=${'$'}(printf '%s' "${'$'}text" | tr '\n\r\t' '   ' | cut -c1-500)
        [ -n "${'$'}text" ] || { echo "usage: $command <text>" >&2; exit 2; }
        timeout 2 sh -c 'printf "%s\n" "${'$'}1" > $guestFifo' sh "$prefix${'$'}text"
        """.trimIndent() + "\n"
    }

    companion object {
        private const val FILES_PREFIX = "omarchy-files:"
        private const val EDIT_PREFIX = "omarchy-edit:"
        private const val WATCH_REPLY_PREFIX = "omawatch-reply:"
        private const val SAY_PREFIX = "omawatch-say:"
        const val WATCH_REPLY_COMMAND = "omawatch-reply"
        const val SAY_COMMAND = "omawatch-say"

        /** In the guest's home. */
        const val WATCH_PROFILE = ".config/omalauncher/omawatch/profile.md"

        val WATCH_PROFILE_TEXT =
            """
            # omawatch profile

            This request came from the owner's watch (OmaWatch): spoken while holding a button, transcribed on the phone
            by Whisper, so expect misheard words. The owner is away from the screen and reads a 1.4" display.

            Talk like a duty officer on a radio net: terse, brevity codes, no pleasantries, no markdown, no lists.
            Lead with the result. One transmission is at most 120 characters.

            Deliver every answer with one of these, never only in this terminal:
            - `$WATCH_REPLY_COMMAND "<text>"` puts it on the watch. The default.
            - `$SAY_COMMAND "<text>"` speaks it: in the headphones when connected, else on the phone's speaker.
              Use it when asked to say, read or tell something, or when the answer is longer than one transmission.

            Procedure: acknowledge (`$WATCH_REPLY_COMMAND "ACK. <task>"`), report only at milestones, close with
            `$WATCH_REPLY_COMMAND "DONE. <result>"` or `"FAIL. <reason>"`. Before anything destructive or irreversible,
            ask by reply and stop; the owner answers by holding the button again.
            """.trimIndent() + "\n"
    }
}
