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
