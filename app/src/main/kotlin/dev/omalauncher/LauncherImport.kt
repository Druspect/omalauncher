package dev.omalauncher

import android.content.Context
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * Brings home over from another build (a backup of .mod or the store app): the layout, the pinned apps and
 * the widgets' saves, which live in the app's own storage where nothing else can write. Linux drops them in
 * the widgets folder's .import/ (~/.config/omalauncher/widgets/.import in the guest), as the other build's
 * shared_prefs XML and widget-state JSON; the next start reads them before anything else does, then deletes them.
 */
object LauncherImport {
    private const val TAG = "import"
    private const val DIR = ".import"

    /** Only these: the launcher's own settings (terminal mode, setup) stay this build's. */
    private val PREFS = listOf("widget_grid", "shortcuts")

    fun run(context: Context) {
        val dir = File(LauncherData.widgetsDir(context), DIR)
        if (!dir.isDirectory) return
        runCatching {
            for (name in PREFS) File(dir, "$name.xml").takeIf { it.isFile }?.let { prefs(context, name, it) }
            // Not hidden files: a Mac's tar adds ._ ones.
            File(dir, "widget-state").listFiles { f -> f.name.endsWith(".owl.json") && !f.name.startsWith(".") }?.forEach { f ->
                f.copyTo(File(context.filesDir, "widget-state/${f.name}"), overwrite = true)
                Log.i(TAG, "widget save ${f.name}")
            }
        }.onFailure { Log.e(TAG, "import failed; nothing more is tried", it) }
        dir.deleteRecursively()
    }

    /** Replaces [name] with what a shared_prefs XML file holds. */
    private fun prefs(
        context: Context,
        name: String,
        file: File,
    ) {
        val edit = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        val p = Xml.newPullParser()
        file.inputStream().use { input ->
            p.setInput(input, null)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                // <map> is depth 1; its entries are depth 2.
                if (p.eventType != XmlPullParser.START_TAG || p.depth != 2) continue
                val key = p.getAttributeValue(null, "name") ?: continue
                val value = p.getAttributeValue(null, "value")
                when (p.name) {
                    "string" -> edit.putString(key, p.nextText())
                    "int" -> edit.putInt(key, value.toInt())
                    "long" -> edit.putLong(key, value.toLong())
                    "float" -> edit.putFloat(key, value.toFloat())
                    "boolean" -> edit.putBoolean(key, value.toBoolean())
                    "set" -> {
                        val set = mutableSetOf<String>()
                        while (!(p.next() == XmlPullParser.END_TAG && p.depth == 2)) {
                            if (p.eventType == XmlPullParser.START_TAG && p.name == "string") set += p.nextText()
                        }
                        edit.putStringSet(key, set)
                    }
                }
            }
        }
        edit.commit()
        Log.i(TAG, "$name from ${file.name}")
    }
}
