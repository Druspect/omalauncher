package dev.omalauncher.watch

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * phone-v1.json is a copy of protocol/vectors/phone-v1.json from the omawatch repo. The
 * watch shell runs the same file, so both sides agree on the exact signed bytes.
 */
class OmaWatchBridgeTest {
    private val vectors = JSONObject(javaClass.getResource("/omawatch/phone-v1.json")!!.readText())
    private val keys = vectors.getJSONObject("keys")

    private fun pub(name: String) = OmaWatchBridge.unhex(keys.getJSONObject(name).getString("pubkey"))

    private fun cases(name: String) = vectors.getJSONArray(name).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    @Test
    fun canonicalJsonSortsKeysAndNestedObjects() {
        val body = JSONObject()
            .put("z", "last")
            .put("nested", JSONObject().put("b", 2).put("a", "first"))
            .put("a", "first")

        assertEquals("{\"a\":\"first\",\"nested\":{\"a\":\"first\",\"b\":2},\"z\":\"last\"}", OmaWatchBridge.canonical(body))
    }

    @Test
    fun canonicalMatchesTheProtocolVectors() {
        for (case in cases("canonical")) {
            assertEquals(case.getString("name"), case.getString("canonical"), OmaWatchBridge.canonical(case.getJSONObject("value")))
        }
    }

    @Test
    fun fingerprintsDependOnTheRawPublicKey() {
        val first = ByteArray(32) { it.toByte() }
        val second = first.copyOf().also { it[31] = 99 }

        assertEquals("SHA256:Yw3NKWbEM2aRElRIu7JbT/QSpJxzLbLIq8G4WBvXEN0=", OmaWatchBridge.fingerprint(first))
        assertNotEquals(OmaWatchBridge.fingerprint(first), OmaWatchBridge.fingerprint(second))
        for (name in listOf("launcher", "watch")) {
            assertEquals(keys.getJSONObject(name).getString("fingerprint"), OmaWatchBridge.fingerprint(pub(name)))
        }
    }

    @Test
    fun signedVectorsVerifyAndSignTheWholeEnvelope() {
        for (case in cases("valid")) {
            val name = case.getString("name")
            val parsed = OmaWatchBridge.parse(case.getJSONObject("envelope").toString().toByteArray())
            assertNotNull(name, parsed)
            if (case.isNull("signer")) {
                assertNull(name, parsed!!.sig)
                continue
            }
            assertArrayEquals(
                name,
                case.getString("signed_bytes").toByteArray(),
                OmaWatchBridge.signedBytes(parsed!!.type, parsed.id, parsed.body),
            )
            assertTrue(name, OmaWatchBridge.verify(parsed, pub(case.getString("signer"))))
        }
    }

    @Test
    fun tamperedVectorsFail() {
        for (case in cases("invalid")) {
            val parsed = OmaWatchBridge.parse(case.getJSONObject("envelope").toString().toByteArray())
            assertFalse(case.getString("name"), parsed != null && OmaWatchBridge.verify(parsed, pub(case.getString("verify_with"))))
        }
    }

    @Test
    fun onlyPlainHexColorsAreSent() {
        assertEquals("#240037", OmaWatchBridge.color("#240037"))
        assertEquals("#ff00ff", OmaWatchBridge.color(" #FF00FF "))
        assertNull(OmaWatchBridge.color("#ff240037"))
        assertNull(OmaWatchBridge.color("magenta"))
    }
}
