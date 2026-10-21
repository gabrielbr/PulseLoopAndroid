package com.pulseloop.ui.components

import android.provider.Settings
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Makes a label + [androidx.compose.material3.Switch] row one control: the whole row toggles, and
 * TalkBack reads the label with the state ("Athlete mode, off, switch") instead of a bare
 * "off, switch". Give the row's Switch `onCheckedChange = null` so it is only the indicator.
 */
fun Modifier.switchRow(
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
): Modifier = heightIn(min = 48.dp).toggleable(
    value = checked,
    enabled = enabled,
    role = Role.Switch,
    onValueChange = onCheckedChange,
)

/**
 * True when the user turned animations off (Settings › Accessibility › Remove animations, which
 * sets the animator duration scale to 0). Looping decoration should hold still then.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
