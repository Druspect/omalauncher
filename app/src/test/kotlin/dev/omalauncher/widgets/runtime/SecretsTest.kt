package dev.omalauncher.widgets.runtime

import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy

class SecretsTest {
    @get:Rule val dir = TemporaryFolder()

    private val program =
        Compiler.compile(
            "widget \"pc\" {\n meta { owl: 12 }\n state token = null secret\n state host = \"\" persist\n" +
                " source s = http(\"https://pc.test/a\", {Authorization: \"Bearer ${'$'}{token}\"})\n Text { text: token == null ? \"pair\" : host } }",
        )

    /** Services that only seal, by reversing the bytes, so the file doesn't hold the token as it is. */
    private val services =
        Proxy.newProxyInstance(Services::class.java.classLoader, arrayOf(Services::class.java)) { _, method, args ->
            when (method.name) {
                "seal", "unseal" -> (args[0] as ByteArray).reversedArray()
                else -> null
            }
        } as Services

    private fun instance(saved: File) = WidgetInstance(program, services, {}, saved)

    /** A secret survives a restart in its own sealed file, never in the persisted values; unset, the file goes. */
    @Test fun keptSealedApartFromPersistedValues() {
        val saved = File(dir.root, "pc.owl.json")
        val sealed = File(dir.root, "pc.owl.secret")
        val a = instance(saved)
        assertEquals(null, a.setState("token", Str("s3cr3t")))
        assertEquals(null, a.setState("host", Str("pc.lan")))
        a.stop()
        assertTrue(sealed.isFile)
        assertFalse(String(sealed.readBytes()).contains("s3cr3t"))
        assertFalse(saved.readText().contains("s3cr3t"))

        val b = instance(saved)
        assertEquals(Str("s3cr3t"), b.read(program.globals.indexOfFirst { it.name == "token" }))
        b.setState("token", Null)
        b.stop()
        assertFalse(sealed.exists())
    }
}
