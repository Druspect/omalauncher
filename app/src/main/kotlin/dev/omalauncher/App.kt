package dev.omalauncher

import android.app.Application

class App : Application() {
    val runtime by lazy { Runtime(this) }

    override fun onCreate() {
        super.onCreate()
        // Before anything reads the layout or the widgets' saves.
        LauncherImport.run(this)
    }
}
