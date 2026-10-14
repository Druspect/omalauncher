package dev.omalauncher.watch

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Receives only the /omawatch path; trust and message validation live in OmaWatchBridge. */
class OmaWatchBridgeService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        (application as? dev.omalauncher.App)?.runtime?.watch?.onMessage(event)
    }
}
