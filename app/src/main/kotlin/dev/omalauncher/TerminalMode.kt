package dev.omalauncher

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.PausableMonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.compositionContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * How long the launcher's animations take. Terminal mode (see [Runtime.terminalMode]) makes it 0: every animation
 * jumps to its end and no frames are drawn for it. Otherwise it follows Android's animator duration scale.
 */
object Motion : MotionDurationScale {
    @Volatile var off = false

    @Volatile private var system = 1f

    override val scaleFactor get() = if (off) 0f else system

    /**
     * Gives this activity's Compose content a recomposer that reads [Motion], in place of the window's default one,
     * which brings its own scale. Like that one, it draws no frames while the activity is stopped. Before setContent.
     */
    fun follow(activity: ComponentActivity) {
        system = Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val ui = AndroidUiDispatcher.CurrentThread
        val clock = PausableMonotonicFrameClock(ui[MonotonicFrameClock]!!)
        val context = ui + clock + Motion
        val recomposer = Recomposer(context)
        activity.window.decorView.compositionContext = recomposer
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> clock.resume()
                    Lifecycle.Event.ON_STOP -> clock.pause()
                    Lifecycle.Event.ON_DESTROY -> recomposer.cancel()
                    else -> Unit
                }
            },
        )
        activity.lifecycleScope.launch(context) { recomposer.runRecomposeAndApplyChanges() }
    }
}
