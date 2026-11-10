package com.pulseloop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.data.dao.CoachConversationSummary
import com.pulseloop.ui.components.Chevron
import com.pulseloop.ui.components.PulseButton
import com.pulseloop.ui.components.PulseButtonStyle
import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf
import com.pulseloop.ui.theme.PulseColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The coach's past conversations (issue #131): tap one to reopen it, swipe it away to delete it,
 * or delete them all. Every delete asks first — it takes the messages, the tool-call trace and any
 * attached photos with it, and there is no undo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachHistorySheet(
    conversations: List<CoachConversationSummary>,
    currentId: String?,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var pendingDelete by remember { mutableStateOf<CoachConversationSummary?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = PulseColors.background) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tr("Past conversations"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary)
            if (conversations.isEmpty()) {
                Text(
                    tr("No conversations yet. Ask the coach something and it will show up here."),
                    fontSize = 14.sp, color = PulseColors.textMuted,
                )
            } else {
                Text(tr("Swipe a conversation to delete it."), fontSize = 12.sp, color = PulseColors.textMuted)
                LazyColumn(
                    Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(conversations, key = { it.id }) { convo ->
                        HistoryRow(
                            convo = convo,
                            isCurrent = convo.id == currentId,
                            onOpen = { onOpen(convo.id) },
                            onSwipedAway = { pendingDelete = convo },
                        )
                    }
                }
                PulseButton(
                    PulseButtonStyle.Text,
                    onClick = { confirmDeleteAll = true },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text(tr("Delete all conversations"), color = PulseColors.danger) }
            }
        }
    }

    pendingDelete?.let { convo ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(tr("Delete this conversation?")) },
            text = {
                Text(trf("“{0}” and its replies will be deleted. This can't be undone.", CoachHistoryText.title(convo.firstQuestion)))
            },
            confirmButton = {
                PulseButton(PulseButtonStyle.Text, onClick = { onDelete(convo.id); pendingDelete = null }) {
                    Text(tr("Delete"), color = PulseColors.danger)
                }
            },
            dismissButton = {
                PulseButton(PulseButtonStyle.Text, onClick = { pendingDelete = null }) { Text(tr("Cancel")) }
            },
        )
    }
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(tr("Delete all conversations?")) },
            text = {
                Text(trf("All {0} conversations and their replies will be deleted. This can't be undone.", conversations.size))
            },
            confirmButton = {
                PulseButton(PulseButtonStyle.Text, onClick = { confirmDeleteAll = false; onDeleteAll() }) {
                    Text(tr("Delete all"), color = PulseColors.danger)
                }
            },
            dismissButton = {
                PulseButton(PulseButtonStyle.Text, onClick = { confirmDeleteAll = false }) { Text(tr("Cancel")) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryRow(
    convo: CoachConversationSummary,
    isCurrent: Boolean,
    onOpen: () -> Unit,
    onSwipedAway: () -> Unit,
) {
    // The swipe only asks; the row snaps back and the dialog decides. Returning false keeps the
    // state from settling on "dismissed", which would leave a cancelled row swiped off screen.
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) onSwipedAway()
            false
        },
    )
    val shape = RoundedCornerShape(8.dp)
    val deleteLabel = tr("Delete")
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(PulseColors.danger.copy(alpha = 0.25f))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Filled.Delete, deleteLabel, tint = PulseColors.danger)
            }
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(shape)
                .background(PulseColors.card)
                // The open conversation is told apart by its "open now" line and a stronger edge;
                // not the brand colour, which marks what to tap.
                .border(1.dp, if (isCurrent) PulseColors.textMuted else PulseColors.borderSubtle, shape)
                .clickable(role = Role.Button, onClick = onOpen)
                // A swipe can't be found or performed by every user; the same delete is offered
                // as an accessibility action.
                .semantics {
                    customActions = listOf(CustomAccessibilityAction(deleteLabel) { onSwipedAway(); true })
                }
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    CoachHistoryText.title(convo.firstQuestion),
                    fontSize = 14.sp, color = PulseColors.textPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    CoachHistoryText.subtitle(convo, isCurrent),
                    fontSize = 12.sp, color = PulseColors.textMuted,
                )
            }
            Chevron()
        }
    }
}

/** The history row's text, kept out of the composable so it can be unit-tested. */
internal object CoachHistoryText {

    fun title(firstQuestion: String?): String =
        firstQuestion?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
            ?: tr("Untitled conversation")

    fun subtitle(
        convo: CoachConversationSummary,
        isCurrent: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
    ): String {
        val parts = mutableListOf(whenText(convo.updatedAt, zone, today))
        parts += if (convo.messageCount == 1) tr("1 message") else trf("{0} messages", convo.messageCount)
        if (isCurrent) parts += tr("open now")
        return parts.joinToString(" · ")
    }

    fun whenText(epochMs: Long, zone: ZoneId, today: LocalDate): String {
        val at = Instant.ofEpochMilli(epochMs).atZone(zone)
        val day = at.toLocalDate()
        return when {
            day == today -> trf("Today {0}", at.format(time()))
            day == today.minusDays(1) -> trf("Yesterday {0}", at.format(time()))
            day.year == today.year -> at.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
            else -> at.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
        }
    }

    // Built per call, not held in vals: the default Locale follows the app language (L10n), so a
    // formatter created once would keep the first language's month names.
    private fun time(): DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
}
