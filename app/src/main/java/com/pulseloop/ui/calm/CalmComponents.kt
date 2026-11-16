package com.pulseloop.ui.calm

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pulseloop.ui.components.Chevron
import com.pulseloop.ui.components.PulseButton
import com.pulseloop.ui.components.PulseButtonStyle
import com.pulseloop.ui.i18n.tr

/** A pushed screen's bar in the new look: back arrow, an Inter title, optional actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalmTopBar(title: String, onBack: () -> Unit, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
    CenterAlignedTopAppBar(
        title = { Text(title, style = CalmType.title, color = CalmColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back"), tint = CalmColors.text) }
        },
        actions = actions,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = CalmColors.page),
    )
}

/** A card: the slightly lighter surface with one hairline border, nothing else. */
@Composable
fun CalmCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(CalmShape.radius)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CalmColors.card)
            .border(1.dp, CalmColors.border, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** A section: room above, a title, then its content. */
@Composable
fun CalmSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(top = CalmShape.sectionGap), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = CalmType.sectionTitle, color = CalmColors.text)
        content()
    }
}

/** Text that shows [collapsedLines] lines and a "Show more" link, so an explanation never pushes the screen down. */
@Composable
fun ExpandableText(text: String, collapsedLines: Int = 3, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Column(modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text, style = CalmType.body, color = CalmColors.textSecondary,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (overflows || expanded) {
            Row(
                Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (expanded) tr("Show less") else tr("Show more"), style = CalmType.title, color = CalmColors.text)
                Spacer(Modifier.width(4.dp))
                Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = CalmColors.text, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Text tabs with an underline under the selected one ("Yesterday · Today"). */
@Composable
fun UnderlineTabs(items: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) {
        items.forEachIndexed { i, label ->
            val on = i == selected
            Column(
                Modifier.weight(1f).heightIn(min = 48.dp)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(i) }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(label, style = CalmType.title, color = if (on) CalmColors.text else CalmColors.textMuted, modifier = Modifier.padding(bottom = 10.dp))
                Box(Modifier.fillMaxWidth(0.6f).height(2.dp).background(if (on) CalmColors.text else Color.Transparent))
            }
        }
    }
}

/**
 * A scale of states with the current one marked, like "No signs · Minor signs · Major signs":
 * every option is visible, so the user sees where they sit and what the others would be.
 */
@Composable
fun LevelScale(names: List<String>, colors: List<Color>, current: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) {
        names.forEachIndexed { i, name ->
            val on = i == current
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    if (on) Box(Modifier.size(44.dp).clip(CircleShape).background(colors[i].copy(alpha = 0.25f)))
                    Box(Modifier.size(if (on) 28.dp else 24.dp).clip(CircleShape).background(if (on) colors[i] else colors[i].copy(alpha = 0.45f)))
                }
                Text(name, style = CalmType.bodySmall, color = if (on) CalmColors.text else CalmColors.textMuted, textAlign = TextAlign.Center)
            }
        }
    }
}

/** A small rounded label, for a state on a row ("Higher"). */
@Composable
fun CalmBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = CalmType.label, color = CalmColors.text,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(CalmColors.cardRaised).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** One metric on a card: a small-caps name, the value large, a badge and an optional arrow. */
@Composable
fun CalmMetricRow(name: String, value: String, unit: String, badge: String?, detail: String? = null, onClick: (() -> Unit)? = null) {
    CalmCard(onClick = onClick) {
        Text(name.uppercase(), style = CalmType.eyebrow, color = CalmColors.textSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                Text(value, style = CalmType.value, color = CalmColors.text)
                if (unit.isNotEmpty()) Text(" $unit", style = CalmType.bodySmall, color = CalmColors.textSecondary, modifier = Modifier.padding(bottom = 3.dp))
            }
            if (badge != null) CalmBadge(badge)
            if (onClick != null) { Spacer(Modifier.width(8.dp)); Chevron(tint = CalmColors.textSecondary) }
        }
        if (detail != null) Text(detail, style = CalmType.bodySmall, color = CalmColors.textMuted)
    }
}

/**
 * The new look's secondary action: a [PulseButton] (so its height and press rules hold) with the
 * card radius and the hairline border, instead of Material's pill and mint outline.
 */
@Composable
fun CalmOutlinedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PulseButton(
        PulseButtonStyle.Outlined, onClick = onClick, modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CalmShape.radius),
        border = BorderStroke(1.dp, CalmColors.border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = CalmColors.text),
    ) { Text(text, style = CalmType.title) }
}
