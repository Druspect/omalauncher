package dev.omalauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Asks before Linux stops here so the build [from] can run it: what's open in it ends. */
@Composable
internal fun HandOverDialog(
    from: String,
    fromPackage: String,
    here: String,
    onHandOver: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogFrame(onDismiss) {
        BasicText("hand linux over?", style = Mono.copy(fontSize = TextSize.Title))
        BasicText("$from wants to run Linux. It stops in $here: its terminals, agents and their sessions end.", style = Mono)
        BasicText(fromPackage, style = Mono.copy(color = Palette.muted))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            BracketAction("keep here", Palette.muted, onDismiss)
            BracketAction("hand over", Palette.accent, onHandOver)
        }
    }
}
