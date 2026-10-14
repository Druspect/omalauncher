package dev.omalauncher.watch

import android.content.Context
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.LinkedHashMap
import java.util.UUID

/** The phone side of the OmaWatch v1 link. It owns no socket and sends only through Play Services. */
class OmaWatchBridge(private val context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(readState())
    val state: StateFlow<WatchBridgeState> = _state.asStateFlow()

    /** Set by Runtime so a verified watch prompt follows the normal agent path. */
    var onPrompt: ((ref: String, text: String, agent: String?) -> Unit)? = null

    private var keyPair: KeyPair? = null
    private var pendingWatchKey: ByteArray? = null
    private var currentTheme: WatchTheme? = readTheme()
    private var currentAgents: List<WatchAgent> = emptyList()
    private var currentDefaultAgent: String? = null
    private val seenPromptIds = object : LinkedHashMap<String, Unit>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > 128
    }

    /** Announces the phone key and retries the current theme for a newly connected watch. */
    fun announce() {
        val key = keyPair() ?: run {
            updateState { it.copy(line = "phone key unavailable") }
            return
        }
        sendEnvelope("hello", helloBody(key), sign = false)
        currentTheme?.let { sendEnvelope("theme", it.body(), sign = true) }
        if (currentAgents.isNotEmpty()) sendAgents(currentAgents, currentDefaultAgent)
    }

    fun publishTheme(
        name: String,
        colors: Map<String, String>,
    ) {
        val theme = WatchTheme(
            name = name.take(MAX_THEME_NAME),
            bg = colors["background"] ?: colors["color0"] ?: return,
            fg = colors["foreground"] ?: colors["color7"] ?: return,
            accent = colors["accent"] ?: colors["color4"] ?: return,
            muted = colors["muted"] ?: colors["color8"] ?: return,
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
        sendEnvelope("theme", theme.body(), sign = true)
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
            .apply()
        pendingWatchKey = null
        updateState { it.copy(line = "watch pinned", fingerprint = fingerprint, pendingFingerprint = null, pinned = true) }
        announce()
        return true
    }

    fun forgetWatch() {
        pendingWatchKey = null
        prefs.edit().remove(KEY_PINNED_KEY).remove(KEY_PINNED_FINGERPRINT).apply()
        updateState { it.copy(line = "watch unpaired", fingerprint = null, pendingFingerprint = null, pinned = false) }
    }

    /** Publishes the phone's provider roster so the companion can render its own app picker. */
    fun publishAgents(agents: List<WatchAgent>, defaultAgent: String? = null) {
        currentAgents = agents.take(MAX_WATCH_AGENTS)
        currentDefaultAgent = defaultAgent?.takeIf { id -> currentAgents.any { it.id == id && it.available } }
        updateState { it.copy(apps = currentAgents) }
        sendAgents(currentAgents, currentDefaultAgent)
    }

    /** Asks a paired watch to open its microphone; speech recognition remains watch-owned. */
    fun requestListen() {
        if (!_state.value.pinned) {
            updateState { it.copy(line = "watch not pinned") }
            return
        }
        sendEnvelope("listen", JSONObject().put("source", "launcher"), sign = true)
        updateState { it.copy(line = "listen requested") }
    }

    private fun sendAgents(agents: List<WatchAgent>, defaultAgent: String?) {
        val body = JSONObject()
            .put("default", defaultAgent ?: "")
            .put("apps", JSONArray(agents.map { agent ->
                JSONObject()
                    .put("id", agent.id)
                    .put("name", agent.name)
                    .put("available", agent.available)
            }))
        sendEnvelope("agents", body, sign = true)
    }

    /** Widget actions: hello retries discovery, pin confirms, and unpair removes trust. */
    fun action(action: String) {
        when (action.lowercase()) {
            "hello", "connect", "retry" -> announce()
            "pin", "confirm" -> if (!pinPendingWatch()) updateState { it.copy(line = "no watch to pin") }
            "unpair", "forget" -> forgetWatch()
            "listen", "mic" -> requestListen()
        }
    }

    fun sendStatus(
        agent: String,
        status: String,
        ref: String,
        line: String? = null,
    ) {
        val body = JSONObject()
            .put("agent", agent.take(24))
            .put("state", status)
            .put("ref", ref.take(64))
        line?.takeIf { it.isNotBlank() }?.let { body.put("line", it.take(120)) }
        sendEnvelope("status", body, sign = true)
    }

    /** Called by the WearableListenerService. */
    fun onMessage(event: MessageEvent) {
        if (event.path != BRIDGE_PATH) return
        val envelope = runCatching { JSONObject(event.data.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        if (envelope.optInt("v", -1) != 1) return
        val type = envelope.optString("type")
        val id = envelope.optString("id")
        if (id.length !in 1..64) return
        val body = envelope.optJSONObject("body") ?: return
        when (type) {
            "hello" -> receiveHello(body)
            "prompt" -> {
                if (!verify(body, envelope.optString("sig"))) return
                synchronized(seenPromptIds) {
                    if (seenPromptIds.put(id, Unit) != null) return
                }
                val text = body.optString("text").trim().takeIf { it.isNotEmpty() } ?: return
                val agent = body.optString("agent").trim().takeIf { it.isNotEmpty() }
                onPrompt?.invoke(id, text, agent)
            }
        }
    }

    private fun receiveHello(body: JSONObject) {
        val pubHex = body.optString("pubkey")
        val suppliedFingerprint = body.optString("fingerprint")
        val raw = runCatching { unhex(pubHex) }.getOrNull() ?: return
        if (raw.size != ED25519_PUBLIC_BYTES || fingerprint(raw) != suppliedFingerprint) return
        pendingWatchKey = raw
        val pinned = prefs.getString(KEY_PINNED_FINGERPRINT, null)
        if (pinned == suppliedFingerprint) {
            updateState { it.copy(line = "watch connected", fingerprint = suppliedFingerprint, pendingFingerprint = null, pinned = true) }
        } else {
            updateState { it.copy(line = "confirm watch", pendingFingerprint = suppliedFingerprint, pinned = false) }
        }
    }

    private fun sendEnvelope(
        type: String,
        body: JSONObject,
        sign: Boolean,
    ) {
        val canonicalBody = canonical(body)
        val envelope = JSONObject()
            .put("v", 1)
            .put("type", type)
            .put("id", UUID.randomUUID().toString().replace("-", "").take(32))
            .put("body", JSONObject(canonicalBody))
        if (sign) signBody(canonicalBody)?.let { envelope.put("sig", it) } ?: return
        val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
        Wearable.getNodeClient(appContext).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    updateState { it.copy(line = "watch not connected") }
                    return@addOnSuccessListener
                }
                val client = Wearable.getMessageClient(appContext)
                nodes.forEach { node ->
                    client.sendMessage(node.id, BRIDGE_PATH, bytes).addOnFailureListener {
                        updateState { it.copy(line = "watch send failed") }
                    }
                }
            }
            .addOnFailureListener { updateState { it.copy(line = "play services unavailable") } }
    }

    private fun verify(body: JSONObject, signatureHex: String): Boolean {
        if (!_state.value.pinned || signatureHex.length != 128) return false
        val raw = prefs.getString(KEY_PINNED_KEY, null)?.let { runCatching { unhex(it) }.getOrNull() } ?: return false
        if (raw.size != ED25519_PUBLIC_BYTES) return false
        return runCatching {
            val verifier = Signature.getInstance("Ed25519")
            val public = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(x509(raw)))
            verifier.initVerify(public)
            verifier.update(canonical(body).toByteArray(Charsets.UTF_8))
            verifier.verify(unhex(signatureHex))
        }.getOrDefault(false)
    }

    private fun signBody(canonical: String): String? {
        val private = keyPair()?.private ?: return null
        return runCatching {
            val signer = Signature.getInstance("Ed25519")
            signer.initSign(private)
            signer.update(canonical.toByteArray(Charsets.UTF_8))
            hex(signer.sign())
        }.getOrNull()
    }

    private fun helloBody(key: KeyPair) = JSONObject()
        .put("pubkey", hex(rawPublic(key.public.encoded)))
        .put("fingerprint", fingerprint(rawPublic(key.public.encoded)))

    private fun keyPair(): KeyPair? {
        keyPair?.let { return it }
        val file = File(appContext.filesDir, KEY_FILE)
        keyPair = runCatching {
            if (file.isFile) {
                val parts = file.readText().trim().split("\n")
                if (parts.size == 2) {
                    val private = Base64.getDecoder().decode(parts[0])
                    val public = Base64.getDecoder().decode(parts[1])
                    val privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(private))
                    val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(public))
                    return@runCatching KeyPair(publicKey, privateKey)
                }
            }
            val generated = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            file.writeText(
                Base64.getEncoder().encodeToString(generated.private.encoded) + "\n" +
                    Base64.getEncoder().encodeToString(generated.public.encoded),
            )
            generated
        }.getOrNull()
        return keyPair
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

    companion object {
        const val BRIDGE_PATH = "/omawatch"
        private const val PREFS = "watch_bridge"
        private const val KEY_FILE = "watch-bridge-key"
        private const val KEY_LINE = "line"
        private const val KEY_PINNED = "pinned"
        private const val KEY_PINNED_KEY = "pinned_key"
        private const val KEY_PINNED_FINGERPRINT = "pinned_fingerprint"
        private const val KEY_THEME_SEQ = "theme_seq"
        private const val KEY_THEME_NAME = "theme_name"
        private const val KEY_THEME_BG = "theme_bg"
        private const val KEY_THEME_FG = "theme_fg"
        private const val KEY_THEME_ACCENT = "theme_accent"
        private const val KEY_THEME_MUTED = "theme_muted"
        private const val MAX_THEME_NAME = 40
        private const val MAX_WATCH_AGENTS = 12
        private const val ED25519_PUBLIC_BYTES = 32
        private val ED25519_X509_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

        fun rawPublic(x509: ByteArray): ByteArray = x509.takeLast(ED25519_PUBLIC_BYTES).toByteArray()

        fun fingerprint(raw: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(raw)
            return "SHA256:" + Base64.getEncoder().encodeToString(digest)
        }

        fun canonical(obj: JSONObject): String = buildString {
            append('{')
            obj.keys().asSequence().toList().sorted().forEachIndexed { index, key ->
                if (index > 0) append(',')
                append(JSONObject.quote(key)).append(':').append(canonicalValue(obj.get(key)))
            }
            append('}')
        }

        private fun canonicalValue(value: Any?): String = when (value) {
            is JSONObject -> canonical(value)
            is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonicalValue(value.get(it)) }
            JSONObject.NULL -> "null"
            is Number, is Boolean -> value.toString()
            else -> JSONObject.quote(value.toString())
        }

        private fun x509(raw: ByteArray) = ED25519_X509_PREFIX + raw

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        private fun unhex(text: String): ByteArray {
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
