package com.pulseloop.ui.calm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pulseloop.ui.theme.PulseColors
import com.pulseloop.ui.theme.PulseType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * The surface and text roles of whichever look is on, for pieces shared by converted and
 * unconverted screens (a section, a fact row, the My Health entry card). Reading [NewLook.on]
 * here, not at each call site, keeps the two looks from drifting apart inside one component.
 * Delete with the old look: every getter then returns its Calm value.
 */
object Look {
    val calm: Boolean get() = NewLook.on
    val page: Color get() = if (calm) CalmColors.page else PulseColors.background
    val card: Color get() = if (calm) CalmColors.card else PulseColors.card
    val border: Color get() = if (calm) CalmColors.border else PulseColors.borderSubtle
    val text: Color get() = if (calm) CalmColors.text else PulseColors.textPrimary
    val textSecondary: Color get() = if (calm) CalmColors.textSecondary else PulseColors.textSecondary
    val textMuted: Color get() = if (calm) CalmColors.textMuted else PulseColors.textMuted
    val radius: Dp get() = if (calm) CalmShape.radius else 8.dp

    /** A card's or a row's title (PulseType.title / CalmType.title). */
    val titleStyle: TextStyle get() = if (calm) CalmType.title else PulseType.title
    /** Running text (PulseType.body / CalmType.bodySmall, both 14sp). */
    val bodyStyle: TextStyle get() = if (calm) CalmType.bodySmall else PulseType.body
    /** Small text: an address, a caption (12sp in both). */
    val labelStyle: TextStyle get() = if (calm) CalmType.label.copy(fontWeight = FontWeight.Normal) else PulseType.label
}

/**
 * The name over a group of controls. The old look's tracked capitals (PulseType.eyebrow); in the
 * new look sentence case at 14sp, since a caps kicker over content is what the craft floor bans.
 * Pass the text in sentence case: the old look upper-cases it.
 */
@androidx.compose.runtime.Composable
fun LookLabel(text: String, modifier: Modifier = Modifier) {
    if (Look.calm) {
        androidx.compose.material3.Text(text, style = CalmType.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = Look.textSecondary, modifier = modifier)
    } else {
        androidx.compose.material3.Text(text.uppercase(), style = PulseType.eyebrow, color = PulseColors.textSecondary, modifier = modifier)
    }
}

/**
 * A card in whichever look is on: the radius, the surface and its hairline. [oldBorder] keeps an
 * old-look card that never had a hairline exactly as it was; the new look always draws one.
 */
fun Modifier.lookCard(oldBorder: Boolean = true): Modifier {
    val shape = RoundedCornerShape(Look.radius)
    val base = this.clip(shape).background(Look.card)
    return if (Look.calm || oldBorder) base.border(1.dp, Look.border, shape) else base
}
