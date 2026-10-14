package dev.omalauncher.watch

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OmaWatchBridgeTest {
    @Test
    fun canonicalJsonSortsKeysAndNestedObjects() {
        val body = JSONObject()
            .put("z", "last")
            .put("nested", JSONObject().put("b", 2).put("a", "first"))
            .put("a", "first")

        assertEquals("{\"a\":\"first\",\"nested\":{\"a\":\"first\",\"b\":2},\"z\":\"last\"}", OmaWatchBridge.canonical(body))
    }

    @Test
    fun fingerprintsDependOnTheRawPublicKey() {
        val first = ByteArray(32) { it.toByte() }
        val second = first.copyOf().also { it[31] = 99 }

        assertEquals("SHA256:Yw3NKWbEM2aRElRIu7JbT/QSpJxzLbLIq8G4WBvXEN0=", OmaWatchBridge.fingerprint(first))
        assertNotEquals(OmaWatchBridge.fingerprint(first), OmaWatchBridge.fingerprint(second))
    }
}
