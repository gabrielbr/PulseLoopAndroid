package com.pulseloop.ui.dashboard

import com.pulseloop.ui.i18n.tr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Edit-mode controller for a dashboard tab's card grid (iOS #64, Android adaptation). A long-press
 * enters edit mode; the user reorders with up/down controls and hides/shows via a "–" badge + a
 * Hidden tray. A discrete model (not free-form drag) — Compose has no reorderable grid and the
 * discrete ops mirror iOS's own VoiceOver reorder fallback exactly.
 *
 * The persisted order in [store] is the FULL supported order (including hidden cards, so a restored
 * card returns to its position); the grid renders that order filtered to visible.
 */
class DashboardEditState(
    private val scope: MetricScope,
    private val store: MetricPrefsStore,
) {
    var editing by mutableStateOf(false)
        private set

    /** Full working order (visible + hidden) while editing; the persisted source of truth. */
    var liveOrder by mutableStateOf<List<DashboardCard>>(emptyList())
        private set

    // The screen refreshes these each composition (cheap) so a long-press can enter edit mode
    // without the gesture site needing the supported/default lists.
    private var supportedCards: List<DashboardCard> = emptyList()
    private var defaultCards: List<DashboardCard> = emptyList()

    fun configure(supported: List<DashboardCard>, defaultOrder: List<DashboardCard>) {
        supportedCards = supported
        defaultCards = defaultOrder
    }

    /** Enter edit mode using the lists most recently passed to [configure]. */
    fun enterEditing() = enter(supportedCards, defaultCards)

    /** Enter edit mode, seeding the working order from the full resolved order of [supported] cards. */
    fun enter(supported: List<DashboardCard>, defaultOrder: List<DashboardCard>) {
        liveOrder = store.resolvedOrder(
            visible = supported.map { it.key }.toSet(),
            defaultOrder = defaultOrder.map { it.key },
            scope = scope,
        ).mapNotNull { DashboardCard.fromKey(it) }
        editing = true
    }

    fun exit() { editing = false }

    fun isHidden(card: DashboardCard): Boolean = store.isHidden(card, scope)

    fun setHidden(card: DashboardCard, hidden: Boolean) {
        // Order is untouched, so a restored card returns to its place (resolvedOrder just filters).
        store.setHidden(card, hidden, scope)
    }

    fun moveUp(card: DashboardCard) {
        val idx = liveOrder.indexOf(card)
        if (idx <= 0) return
        val prev = (idx - 1 downTo 0).firstOrNull { !isHidden(liveOrder[it]) } ?: return
        liveOrder = CardOrder.moving(liveOrder, idx, prev)
        persist()
    }

    fun moveDown(card: DashboardCard) {
        val idx = liveOrder.indexOf(card)
        if (idx < 0) return
        val next = (idx + 1 until liveOrder.size).firstOrNull { !isHidden(liveOrder[it]) } ?: return
        liveOrder = CardOrder.moving(liveOrder, idx, next)
        persist()
    }

    /** True when [card] is not the first visible card in the working order. */
    fun canMoveUp(card: DashboardCard): Boolean {
        val idx = liveOrder.indexOf(card)
        return idx > 0 && (0 until idx).any { !isHidden(liveOrder[it]) }
    }

    /** True when [card] is not the last visible card in the working order. */
    fun canMoveDown(card: DashboardCard): Boolean {
        val idx = liveOrder.indexOf(card)
        return idx >= 0 && (idx + 1 until liveOrder.size).any { !isHidden(liveOrder[it]) }
    }

    private fun persist() = store.setOrder(liveOrder.map { it.key }, scope)
}

@Composable
fun rememberDashboardEditState(scope: MetricScope, store: MetricPrefsStore): DashboardEditState =
    remember(scope, store) { DashboardEditState(scope, store) }

/**
 * Wraps a card's content with the edit-mode overlay: a "–" hide badge (top-start) and up/down move
 * controls (top-end), shown only while [DashboardEditState.editing]. While editing, a transparent
 * scrim over the card body swallows taps so the card's own tap-to-navigate is suppressed; the hide/
 * move controls sit above the scrim. Edit mode is entered via [CustomizeCardsButton], not a
 * long-press, to avoid conflicting with each card's existing clickable.
 */
@Composable
fun EditableCard(
    editState: DashboardEditState,
    card: DashboardCard,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier) {
        // While editing, TalkBack hears the card's name only, not its navigate action.
        Box(if (editState.editing) Modifier.clearAndSetSemantics { contentDescription = card.displayName } else Modifier) {
            content()
        }
        if (editState.editing) {
            // Consume taps on the card body so navigation doesn't fire while editing (no ripple).
            // pointerInput rather than clickable: a clickable scrim was a TalkBack stop that did nothing.
            Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { } })
            EditBadge(
                Icons.Filled.Remove, "Hide ${card.displayName}",
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.align(Alignment.TopStart),
            ) { editState.setHidden(card, true) }
            Row(Modifier.align(Alignment.TopEnd)) {
                if (editState.canMoveUp(card)) {
                    EditBadge(Icons.Filled.KeyboardArrowUp, "Move ${card.displayName} up") { editState.moveUp(card) }
                }
                if (editState.canMoveDown(card)) {
                    EditBadge(Icons.Filled.KeyboardArrowDown, "Move ${card.displayName} down") { editState.moveDown(card) }
                }
            }
        }
    }
}

/** A 28dp badge drawn inside a 48dp touch target, so the up/down pair isn't a 4dp-apart miss. */
@Composable
private fun EditBadge(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(50), color = container, modifier = Modifier.size(28.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * The tray of currently-hidden cards shown while editing, each with a "+" to restore it to its
 * saved position (iOS `HiddenMetricsTray`). Renders nothing when nothing is hidden.
 */
@Composable
fun HiddenMetricsTray(hidden: List<DashboardCard>, onRestore: (DashboardCard) -> Unit) {
    if (hidden.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("HIDDEN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        hidden.forEach { card ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(card.displayName, style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { onRestore(card) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Show ${card.displayName}")
                }
            }
        }
    }
}

/** Low-emphasis entry point that puts the dashboard into edit mode (Android's explicit substitute
 *  for iOS's long-press). Show it only when not already editing. */
@Composable
fun CustomizeCardsButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(tr("Customize"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The "Done" bar shown while editing, to exit edit mode (iOS `ReorderDoneBar`). */
@Composable
fun ReorderDoneBar(onDone: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(50),
        modifier = Modifier.wrapContentSize(),
    ) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("Reorder or hide cards"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
            TextButton(onClick = onDone) { Text(tr("Done")) }
        }
    }
}
