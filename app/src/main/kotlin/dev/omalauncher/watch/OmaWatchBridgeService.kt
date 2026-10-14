package dev.omalauncher.watch

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Receives only the /omawatch path; trust and message validation live in OmaWatchBridge. */
class OmaWatchBridgeService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val runtime = (application as? dev.omalauncher.App)?.runtime ?: return
        // A listener service can be the first component that starts the process. Make
        // the normal launcher bootstrap run before a background watch prompt arrives.
        runtime.start()
        runtime.watch.onMessage(event)
    }
}
