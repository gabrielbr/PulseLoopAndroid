package com.pulseloop.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.pulseloop.ui.theme.pressScale

/** The four kinds of button the app uses. Which one a screen gets is a hierarchy decision. */
enum class PulseButtonStyle {
    /** The one filled accent action per screen. */
    Filled,
    /** A screen's own primary action when the accent is taken (Activity's Record). */
    Tonal,
    /** A secondary action. */
    Outlined,
    /** A dialog action, or a quiet action beside a stronger one. */
    Text,
}

object PulseButtonDefaults {
    /**
     * Every button is at least 52dp tall (Gabriel Design System, DESIGN.md "Buttons"; the
     * `button` size token, 52px). Material3's own 40dp only reached 48dp as a touch target.
     * A min, not a fixed height, so a two-line pt-BR label or large font scale grows the button
     * instead of clipping it.
     */
    val MinHeight = 52.dp
}

/**
 * The app's only button. Every `Button` / `FilledTonalButton` / `OutlinedButton` / `TextButton`
 * goes through it, so the system's button rules hold in one place: 52dp minimum height and the
 * 97% press scale (`pressScale`, which follows "Remove animations"). `PulseButtonTest` fails on a
 * Material button called anywhere else.
 *
 * [style] comes first so a call reads `PulseButton(PulseButtonStyle.Outlined, onClick = …)`, and
 * positional arguments after it keep the Material order (onClick, modifier). [colors], [border]
 * and [shape] default to that style's Material defaults.
 */
@Composable
fun PulseButton(
    style: PulseButtonStyle = PulseButtonStyle.Filled,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: ButtonColors? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val m = modifier.heightIn(min = PulseButtonDefaults.MinHeight).pressScale(interaction)
    when (style) {
        PulseButtonStyle.Filled -> Button(
            onClick = onClick, modifier = m, enabled = enabled,
            shape = shape ?: ButtonDefaults.shape,
            colors = colors ?: ButtonDefaults.buttonColors(),
            border = border,
            contentPadding = contentPadding ?: ButtonDefaults.ContentPadding,
            interactionSource = interaction, content = content,
        )
        PulseButtonStyle.Tonal -> FilledTonalButton(
            onClick = onClick, modifier = m, enabled = enabled,
            shape = shape ?: ButtonDefaults.filledTonalShape,
            colors = colors ?: ButtonDefaults.filledTonalButtonColors(),
            border = border,
            contentPadding = contentPadding ?: ButtonDefaults.ContentPadding,
            interactionSource = interaction, content = content,
        )
        PulseButtonStyle.Outlined -> OutlinedButton(
            onClick = onClick, modifier = m, enabled = enabled,
            shape = shape ?: ButtonDefaults.outlinedShape,
            colors = colors ?: ButtonDefaults.outlinedButtonColors(),
            border = border ?: ButtonDefaults.outlinedButtonBorder,
            contentPadding = contentPadding ?: ButtonDefaults.ContentPadding,
            interactionSource = interaction, content = content,
        )
        PulseButtonStyle.Text -> TextButton(
            onClick = onClick, modifier = m, enabled = enabled,
            shape = shape ?: ButtonDefaults.textShape,
            colors = colors ?: ButtonDefaults.textButtonColors(),
            border = border,
            contentPadding = contentPadding ?: ButtonDefaults.TextButtonContentPadding,
            interactionSource = interaction, content = content,
        )
    }
}
