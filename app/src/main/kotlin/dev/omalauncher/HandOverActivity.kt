package dev.omalauncher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.omalauncher.ui.HandOverDialog

/**
 * Another omalauncher build wants to run Linux while this one has it online (see [Presence]). Started by that build
 * for a result, over its own screen. Only a tap here stops Linux; a caller that isn't a build gets no dialog.
 */
class HandOverActivity : ComponentActivity() {
    private val runtime get() = (application as App).runtime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val caller = callingPackage
        if (caller == null || caller !in runtime.presence.builds()) return finish()
        // Nothing to hand over: the caller can go ahead.
        if (!runtime.presence.isOnline) {
            setResult(RESULT_OK)
            return finish()
        }
        val from = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(caller, 0)).toString() }.getOrDefault(caller)
        val here = applicationInfo.loadLabel(packageManager).toString()
        setContent {
            HandOverDialog(
                from = from,
                here = here,
                onHandOver = {
                    setResult(RESULT_OK)
                    finish()
                    runtime.handOver()
                },
                onDismiss = ::finish,
            )
        }
    }
}
