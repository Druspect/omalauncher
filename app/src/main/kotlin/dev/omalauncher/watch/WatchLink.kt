package dev.omalauncher.watch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * The phone end of the OmaWatch Bluetooth socket (omawatch protocol/phone.md, D15): a secure
 * RFCOMM listener on the existing bond, one watch at a time. It only carries records; every
 * envelope on it is checked by [OmaWatchBridge] exactly as one from the Data Layer.
 */
class WatchLink(private val context: Context, private val bridge: OmaWatchBridge) {
    private val writeLock = Any()
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var client: BluetoothSocket? = null
    @Volatile private var out: DataOutputStream? = null

    /** BLUETOOTH_CONNECT exists from API 31; before that the install-time BLUETOOTH covers it. */
    val permitted get() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    val connected get() = client?.isConnected == true

    /** Starts listening, once. Needs BLUETOOTH_CONNECT; without it the Data Layer is all there is. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (server != null || !permitted) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) return
        val listening = runCatching { adapter.listenUsingRfcommWithServiceRecord(NAME, SERVICE) }.getOrNull() ?: return
        server = listening
        Thread({ acceptLoop(listening) }, "omawatch-link").start()
    }

    private fun acceptLoop(listening: BluetoothServerSocket) {
        try {
            while (server === listening) {
                val socket = listening.accept()
                // One watch at a time: a new connection replaces the old one.
                close(client)
                client = socket
                out = DataOutputStream(socket.outputStream.buffered())
                bridge.onLinkOpened()
                Thread({ read(socket) }, "omawatch-link-read").start()
            }
        } catch (_: Exception) {
            // Bluetooth off or stop(); start() listens again later.
        } finally {
            if (server === listening) server = null
        }
    }

    private fun read(socket: BluetoothSocket) {
        var pcm: File? = null
        var pcmOut: OutputStream? = null
        var sha = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        try {
            val input = DataInputStream(socket.inputStream.buffered())
            while (true) {
                val kind = input.readUnsignedByte()
                val length = input.readInt()
                when (kind) {
                    MESSAGE.code -> {
                        require(length in 1..MAX_ENVELOPE)
                        bridge.onEnvelopeBytes(ByteArray(length).also { input.readFully(it) })
                    }
                    AUDIO.code -> {
                        require(length in 1..MAX_CHUNK && bytes + length <= OmaWatchBridge.MAX_AUDIO_BYTES)
                        if (pcm == null) {
                            pcm = File.createTempFile("omawatch-", ".pcm", context.cacheDir)
                            pcmOut = pcm.outputStream().buffered()
                        }
                        val chunk = ByteArray(length).also { input.readFully(it) }
                        pcmOut!!.write(chunk)
                        sha.update(chunk)
                        bytes += length
                    }
                    END.code -> {
                        require(length in 1..MAX_ENVELOPE)
                        val envelope = ByteArray(length).also { input.readFully(it) }
                        pcmOut?.close()
                        val file = pcm
                        val stream = OmaWatchBridge.AudioStream(envelope, bytes, OmaWatchBridge.hex(sha.digest()))
                        pcm = null
                        pcmOut = null
                        sha = MessageDigest.getInstance("SHA-256")
                        bytes = 0L
                        // Transcription is slow; keep reading so status and new prompts still flow.
                        Thread({
                            val audio = file ?: File.createTempFile("omawatch-", ".pcm", context.cacheDir)
                            try {
                                bridge.handleAudio(audio, stream)
                            } finally {
                                audio.delete()
                            }
                        }, "omawatch-audio").start()
                    }
                    else -> return
                }
            }
        } catch (_: Exception) {
            // Closed by either side, or a record broke the rules.
        } finally {
            runCatching { pcmOut?.close() }
            pcm?.delete()
            if (client === socket) close(socket)
        }
    }

    /** Sends one envelope on the socket. False when no watch is connected. */
    fun send(envelope: ByteArray): Boolean = synchronized(writeLock) {
        val stream = out ?: return false
        if (!connected) return false
        try {
            stream.writeByte(MESSAGE.code)
            stream.writeInt(envelope.size)
            stream.write(envelope)
            stream.flush()
            true
        } catch (_: Exception) {
            close(client)
            false
        }
    }

    fun stop() {
        val listening = server
        server = null
        runCatching { listening?.close() }
        close(client)
    }

    private fun close(socket: BluetoothSocket?) {
        if (socket == null) return
        if (client === socket) {
            client = null
            out = null
        }
        runCatching { socket.close() }
    }

    companion object {
        const val NAME = "omawatch"
        val SERVICE: UUID = UUID.fromString("6f6d6177-6174-6368-2d6c-696e6b2d7631")
        private const val MESSAGE = 'M'
        private const val AUDIO = 'A'
        private const val END = 'E'
        private const val MAX_ENVELOPE = 8 * 1024
        private const val MAX_CHUNK = 65536
    }
}
