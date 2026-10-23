package com.pulseloop.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The app's motion vocabulary, kept small so every screen moves the same way:
 *
 * - [sweepIn] fills a ring, gauge or bar from empty when it first appears, then glides to each new
 *   value instead of jumping.
 * - [pressScale] gives a tappable card a slight press-in, so a tap is felt before the screen moves.
 *
 * All of it runs on Compose's animation clock, which honours the system "Remove animations"
 * setting: with animations off, every value lands at once.
 */
object Motion {
    /** A ring filling, a number counting up: long enough to see, short enough not to wait for. */
    const val FILL_MS = 900
}

/**
 * [target] (0..1 or any range starting at [from]), animated from [from] the first time it is shown
 * and from its previous value after that.
 */
@Composable
fun sweepIn(target: Float, from: Float = 0f): Float {
    val anim = remember { Animatable(from) }
    LaunchedEffect(target) { anim.animateTo(target, tween(Motion.FILL_MS, easing = FastOutSlowInEasing)) }
    return anim.value
}

/** Scales the element to 97% while it is pressed through [interaction]. */
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = 600f), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }
}
