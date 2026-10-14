package dev.omalauncher.watch

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.LinkedHashMap
import java.util.UUID

/**
 * The phone side of the OmaWatch link, protocol/phone.md in the omawatch repo. It owns no
 * socket and sends only through Play Services. The watch picks nothing: a verified prompt
 * runs the agent this launcher already has selected.
 */
class OmaWatchBridge(private val context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(readState())
    val state: StateFlow<WatchBridgeState> = _state.asStateFlow()

    /** Set by Runtime so a verified watch prompt follows the normal agent path. */
    var onPrompt: ((ref: String, text: String) -> Unit)? = null

    /** Wall clock for prompt expiry. Tests replace it. */
    internal var now: () -> Long = System::currentTimeMillis

    private var phoneKey: PhoneKey? = null
    private var pendingWatchKey: ByteArray? = null
    private var currentTheme: WatchTheme? = readTheme()
    private val seenIds = object : LinkedHashMap<String, Unit>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > 128
    }

    /** Announces the phone key, and republishes the current theme as the data item. */
    fun announce() {
        val key = phoneKey() ?: run {
            updateState { it.copy(line = "phone key unavailable") }
            return
        }
        sendMessage("hello", helloBody(key), sign = false)
        currentTheme?.let(::putTheme)
    }

    fun publishTheme(
        name: String,
        colors: Map<String, String>,
    ) {
        fun pick(vararg keys: String) = keys.firstNotNullOfOrNull { colors[it]?.let(::color) }
        val theme = WatchTheme(
            name = name.take(MAX_THEME_NAME),
            bg = pick("background", "color0") ?: return,
            fg = pick("foreground", "color7") ?: return,
            accent = pick("accent", "color4") ?: return,
            muted = pick("muted", "color8") ?: return,
            seq = prefs.getLong(KEY_THEME_SEQ, 0L) + 1,
        )
        currentTheme = theme
        prefs.edit()
            .putLong(KEY_THEME_SEQ, theme.seq)
            .putString(KEY_THEME_NAME, theme.name)
            .putString(KEY_THEME_BG, theme.bg)
            .putString(KEY_THEME_FG, theme.fg)
            .putString(KEY_THEME_ACCENT, theme.accent)
            .putString(KEY_THEME_MUTED, theme.muted)
            .apply()
        updateState { it.copy(theme = theme.name) }
        putTheme(theme)
    }

    /** Confirms the fingerprint shown by the widget and stores the exact peer key it represents. */
    fun pinPendingWatch(): Boolean {
        val raw = pendingWatchKey ?: return false
        val fingerprint = fingerprint(raw)
        if (fingerprint != _state.value.pendingFingerprint) return false
        prefs.edit()
            .putString(KEY_PINNED_KEY, hex(raw))
            .putString(KEY_PINNED_FINGERPRINT, fingerprint)
            .putBoolean(KEY_PINNED, true)
            .remove(KEY_PROMPT_SEQ)
            .apply()
        pendingWatchKey = null
        updateState { it.copy(line = "watch pinned", fingerprint = fingerprint, pendingFingerprint = null, pinned = true) }
        announce()
        return true
    }

    fun forgetWatch() {
        pendingWatchKey = null
        prefs.edit().remove(KEY_PINNED_KEY).remove(KEY_PINNED_FINGERPRINT).remove(KEY_PROMPT_SEQ).apply()
        updateState { it.copy(line = "watch unpaired", fingerprint = null, pendingFingerprint = null, pinned = false) }
    }

    /** The installed agents, for the widget only. The watch is not sent a roster and does not choose. */
    fun publishAgents(agents: List<WatchAgent>) {
        updateState { it.copy(apps = agents.take(MAX_WATCH_AGENTS)) }
    }

    /** Widget actions: hello retries discovery, pin confirms, and unpair removes trust. */
    fun action(action: String) {
        when (action.lowercase()) {
            "hello", "connect", "retry" -> announce()
            "pin", "confirm" -> if (!pinPendingWatch()) updateState { it.copy(line = "no watch to pin") }
            "unpair", "forget" -> forgetWatch()
        }
    }

    fun sendStatus(
        agent: String,
        status: String,
        ref: String,
        line: String? = null,
        reason: String? = null,
    ) {
        val body = JSONObject()
            .put("agent", agent.take(24))
            .put("state", status)
            .put("ref", ref.take(64))
        if (status == "failed") reason?.let { body.put("reason", it) }
        line?.takeIf { it.isNotBlank() }?.let { body.put("line", it.take(120)) }
        sendMessage("status", body, sign = true)
    }

    /** Called by the WearableListenerService. */
    fun onMessage(event: MessageEvent) {
        if (event.path != BRIDGE_PATH) return
        val envelope = parse(event.data) ?: return
        synchronized(seenIds) {
            if (seenIds.put(envelope.id, Unit) != null) return
        }
        when (envelope.type) {
            "hello" -> receiveHello(envelope.body)
            "prompt" -> receivePrompt(envelope)
        }
    }

    private fun receivePrompt(envelope: Envelope) {
        val pinned = pinnedKey() ?: return
        if (!verify(envelope, pinned)) return
        val body = envelope.body
        val text = (body.opt("text") as? String)?.trim().orEmpty()
        val seq = integer(body.opt("seq")) ?: return
        val expires = integer(body.opt("expires")) ?: return
        if (text.isEmpty() || text.length > MAX_PROMPT) return
        val time = now()
        if (time > expires || expires - time > MAX_PROMPT_AHEAD_MS) {
            sendStatus("launcher", "failed", envelope.id, reason = "expired")
            return
        }
        val fresh = synchronized(prefs) {
            if (seq <= prefs.getLong(KEY_PROMPT_SEQ, 0L)) {
                false
            } else {
                prefs.edit().putLong(KEY_PROMPT_SEQ, seq).commit()
                true
            }
        }
        if (!fresh) {
            sendStatus("launcher", "failed", envelope.id, reason = "duplicate")
            return
        }
        onPrompt?.invoke(envelope.id, text)
    }

    private fun receiveHello(body: JSONObject) {
        val pubHex = body.opt("pubkey") as? String ?: return
        val suppliedFingerprint = body.opt("fingerprint") as? String ?: return
        val raw = runCatching { unhex(pubHex) }.getOrNull() ?: return
        if (raw.size != ED25519_PUBLIC_BYTES || fingerprint(raw) != suppliedFingerprint) return
        val pinned = prefs.getString(KEY_PINNED_FINGERPRINT, null)
        if (pinned != null) {
            // A pinned side ignores hello. The owner unpairs first to accept a new watch.
            if (pinned == suppliedFingerprint) updateState { it.copy(line = "watch connected") }
            return
        }
        val first = pendingWatchKey?.contentEquals(raw) != true
        pendingWatchKey = raw
        updateState { it.copy(line = "confirm watch", pendingFingerprint = suppliedFingerprint, pinned = false) }
        // Answer the first hello from a key once, so the watch can show this phone's fingerprint.
        if (first) phoneKey()?.let { sendMessage("hello", helloBody(it), sign = false) }
    }

    private fun putTheme(theme: WatchTheme) {
        val bytes = envelopeBytes("theme", theme.body(), sign = true) ?: return
        val request = PutDataMapRequest.create(THEME_PATH).apply {
            dataMap.putByteArray("env", bytes)
        }.asPutDataRequest().setUrgent()
        runCatching {
            Wearable.getDataClient(appContext).putDataItem(request)
                .addOnFailureListener { updateState { it.copy(line = "theme not published") } }
        }.onFailure { updateState { it.copy(line = "play services unavailable") } }
    }

    private fun sendMessage(
        type: String,
        body: JSONObject,
        sign: Boolean,
    ) {
        val bytes = envelopeBytes(type, body, sign) ?: return
        runCatching {
            Wearable.getCapabilityClient(appContext)
                .getCapability(WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .addOnSuccessListener { info ->
                    if (info.nodes.isEmpty()) {
                        updateState { it.copy(line = "watch not connected") }
                        return@addOnSuccessListener
                    }
                    val client = Wearable.getMessageClient(appContext)
                    info.nodes.forEach { node ->
                        client.sendMessage(node.id, BRIDGE_PATH, bytes).addOnFailureListener {
                            updateState { it.copy(line = "watch send failed") }
                        }
                    }
                }
                .addOnFailureListener { updateState { it.copy(line = "play services unavailable") } }
        }.onFailure { updateState { it.copy(line = "play services unavailable") } }
    }

    private fun envelopeBytes(type: String, body: JSONObject, sign: Boolean): ByteArray? {
        val id = UUID.randomUUID().toString().replace("-", "")
        val sig = if (sign) {
            val key = phoneKey() ?: return null
            runCatching { key.sign(signedBytes(type, id, body)) }.getOrNull() ?: return null
        } else {
            null
        }
        return encode(type, id, body, sig)
    }

    private fun pinnedKey(): ByteArray? =
        prefs.getString(KEY_PINNED_KEY, null)
            ?.let { runCatching { unhex(it) }.getOrNull() }
            ?.takeIf { _state.value.pinned && it.size == ED25519_PUBLIC_BYTES }

    private fun helloBody(key: PhoneKey) = JSONObject()
        .put("pubkey", hex(key.raw))
        .put("fingerprint", fingerprint(key.raw))

    /** An RFC 8032 key from a random seed in app-private storage. No platform Ed25519 needed. */
    private fun phoneKey(): PhoneKey? {
        phoneKey?.let { return it }
        val file = File(appContext.filesDir, KEY_FILE)
        phoneKey = runCatching {
            val seed = file.takeIf { it.isFile }?.readText()?.trim()?.let(::unhex)
                ?: ByteArray(Ed25519.SECRET_KEY_SIZE).also {
                    SecureRandom().nextBytes(it)
                    file.writeText(hex(it) + "\n")
                }
            require(seed.size == Ed25519.SECRET_KEY_SIZE)
            PhoneKey(seed)
        }.getOrNull()
        return phoneKey
    }

    private class PhoneKey(private val seed: ByteArray) {
        val raw = ByteArray(Ed25519.PUBLIC_KEY_SIZE).also { Ed25519.generatePublicKey(seed, 0, it, 0) }

        fun sign(message: ByteArray) =
            ByteArray(Ed25519.SIGNATURE_SIZE).also { Ed25519.sign(seed, 0, message, 0, message.size, it, 0) }
    }

    private fun readTheme(): WatchTheme? {
        val name = prefs.getString(KEY_THEME_NAME, null) ?: return null
        return WatchTheme(
            name,
            prefs.getString(KEY_THEME_BG, null) ?: return null,
            prefs.getString(KEY_THEME_FG, null) ?: return null,
            prefs.getString(KEY_THEME_ACCENT, null) ?: return null,
            prefs.getString(KEY_THEME_MUTED, null) ?: return null,
            prefs.getLong(KEY_THEME_SEQ, 0L),
        )
    }

    private fun readState() = WatchBridgeState(
        line = prefs.getString(KEY_LINE, "watch not connected") ?: "watch not connected",
        fingerprint = prefs.getString(KEY_PINNED_FINGERPRINT, null),
        pendingFingerprint = null,
        pinned = prefs.getBoolean(KEY_PINNED, false) && prefs.contains(KEY_PINNED_KEY),
        theme = prefs.getString(KEY_THEME_NAME, null),
        apps = emptyList(),
    )

    private fun updateState(change: (WatchBridgeState) -> WatchBridgeState) {
        val next = change(_state.value)
        prefs.edit()
            .putString(KEY_LINE, next.line)
            .putBoolean(KEY_PINNED, next.pinned)
            .apply()
        _state.value = next
    }

    /** One parsed message. Shape only; a signed type still has to pass [verify]. */
    class Envelope(val type: String, val id: String, val body: JSONObject, val sig: String?)

    companion object {
        const val BRIDGE_PATH = "/omawatch"
        const val THEME_PATH = "/omawatch/theme"
        private const val WATCH_CAPABILITY = "omawatch_watch"
        private const val PREFS = "watch_bridge"
        private const val KEY_FILE = "watch-bridge-seed"
        private const val KEY_LINE = "line"
        private const val KEY_PINNED = "pinned"
        private const val KEY_PINNED_KEY = "pinned_key"
        private const val KEY_PINNED_FINGERPRINT = "pinned_fingerprint"
        private const val KEY_PROMPT_SEQ = "prompt_seq"
        private const val KEY_THEME_SEQ = "theme_seq"
        private const val KEY_THEME_NAME = "theme_name"
        private const val KEY_THEME_BG = "theme_bg"
        private const val KEY_THEME_FG = "theme_fg"
        private const val KEY_THEME_ACCENT = "theme_accent"
        private const val KEY_THEME_MUTED = "theme_muted"
        private const val MAX_THEME_NAME = 40
        private const val MAX_WATCH_AGENTS = 12
        private const val MAX_PROMPT = 500
        private const val MAX_PROMPT_AHEAD_MS = 120_000L
        private const val MAX_BYTES = 8 * 1024
        private const val ED25519_PUBLIC_BYTES = 32
        private val TYPES = setOf("hello", "theme", "prompt", "status")
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")
        private val SIG = Regex("[0-9a-f]{128}")
        private val COLOR = Regex("#[0-9a-fA-F]{6}")

        fun rawPublic(x509: ByteArray): ByteArray = x509.takeLast(ED25519_PUBLIC_BYTES).toByteArray()

        fun fingerprint(raw: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(raw)
            return "SHA256:" + Base64.getEncoder().encodeToString(digest)
        }

        /** `#rrggbb`, lowercased, or null. A theme with an alpha channel or a name is not sent. */
        internal fun color(value: String): String? = value.trim().takeIf { it.matches(COLOR) }?.lowercase()

        fun parse(bytes: ByteArray): Envelope? {
            if (bytes.size > MAX_BYTES) return null
            val env = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return null
            if (integer(env.opt("v")) != 1L) return null
            val type = env.opt("type") as? String ?: return null
            val id = env.opt("id") as? String ?: return null
            val body = env.opt("body") as? JSONObject ?: return null
            val sig = if (env.has("sig")) env.opt("sig") as? String ?: return null else null
            if (type !in TYPES || !id.matches(ID)) return null
            if (sig != null && !sig.matches(SIG)) return null
            if ((type == "hello") != (sig == null)) return null
            return Envelope(type, id, body, sig)
        }

        /** RFC 8032 in software, so an Android 13 phone without an Ed25519 KeyFactory still verifies. */
        fun verify(envelope: Envelope, raw: ByteArray): Boolean {
            val sig = envelope.sig ?: return false
            if (raw.size != ED25519_PUBLIC_BYTES) return false
            return runCatching {
                val message = signedBytes(envelope.type, envelope.id, envelope.body)
                Ed25519.verify(unhex(sig), 0, raw, 0, message, 0, message.size)
            }.getOrDefault(false)
        }

        /** What `sig` covers: the canonical form of v, type, id, and body. */
        fun signedBytes(type: String, id: String, body: JSONObject): ByteArray =
            canonical(JSONObject().put("v", 1).put("type", type).put("id", id).put("body", body))
                .toByteArray(Charsets.UTF_8)

        fun encode(type: String, id: String, body: JSONObject, sig: ByteArray?): ByteArray {
            val env = JSONObject().put("v", 1).put("type", type).put("id", id).put("body", body)
            if (sig != null) env.put("sig", hex(sig))
            return canonical(env).toByteArray(Charsets.UTF_8)
        }

        /**
         * The canonical form from protocol/phone.md: objects, strings, and integers only.
         * JSONObject.toString and JSONObject.quote are not it, because they escape `/`.
         */
        fun canonical(value: Any?): String = buildString { canonical(value, this) }

        private fun canonical(value: Any?, out: StringBuilder) {
            when (value) {
                is JSONObject -> {
                    out.append('{')
                    value.keys().asSequence().toList().sorted().forEachIndexed { i, key ->
                        if (i > 0) out.append(',')
                        string(key, out)
                        out.append(':')
                        canonical(value.get(key), out)
                    }
                    out.append('}')
                }
                is String -> string(value, out)
                is Int, is Long -> out.append(value.toString())
                else -> throw IllegalArgumentException("not in the canonical subset")
            }
        }

        private fun string(s: String, out: StringBuilder) {
            out.append('"')
            for (c in s) {
                when (c) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\b' -> out.append("\\b")
                    '\t' -> out.append("\\t")
                    '\n' -> out.append("\\n")
                    '\u000c' -> out.append("\\f")
                    '\r' -> out.append("\\r")
                    else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
                }
            }
            out.append('"')
        }

        private fun integer(value: Any?): Long? = when (value) {
            is Int -> value.toLong()
            is Long -> value
            else -> null
        }

        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        fun unhex(text: String): ByteArray {
            require(text.isNotEmpty() && text.length % 2 == 0 && text.matches(Regex("[0-9a-fA-F]+")))
            return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
    }
}

data class WatchBridgeState(
    val line: String,
    val fingerprint: String?,
    val pendingFingerprint: String?,
    val pinned: Boolean,
    val theme: String?,
    val apps: List<WatchAgent>,
)

data class WatchAgent(
    val id: String,
    val name: String,
    val available: Boolean,
)

data class WatchTheme(
    val name: String,
    val bg: String,
    val fg: String,
    val accent: String,
    val muted: String,
    val seq: Long,
) {
    fun body() = JSONObject()
        .put("name", name)
        .put("bg", bg)
        .put("fg", fg)
        .put("accent", accent)
        .put("muted", muted)
        .put("seq", seq)
}
