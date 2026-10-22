package com.pulseloop.ui.screens

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.ui.i18n.AppLanguage
import com.pulseloop.ui.i18n.L10n
import com.pulseloop.ui.i18n.Voice
import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf
import com.pulseloop.ui.theme.PulseColors

/**
 * Settings → Language. Each option names itself in its own language, so anyone can find theirs.
 * A voice with strong language asks once before switching, the way F*cking Health's inspiration
 * warns the easily offended.
 */
@Composable
fun LanguageSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var confirming by remember { mutableStateOf<AppLanguage?>(null) }

    SettingsSubScreen(title = tr("Language"), onBack = onBack) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card),
        ) {
            AppLanguage.entries.forEach { lang ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        when {
                            lang == L10n.language -> Unit
                            lang.voice == Voice.FUNNY -> confirming = lang
                            else -> switchTo(context, lang)
                        }
                    }.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = L10n.language == lang, onClick = null)
                    Column(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp)) {
                        Text(
                            if (lang == AppLanguage.SYSTEM) tr("System default") else lang.label,
                            fontSize = 15.sp, color = PulseColors.textPrimary,
                        )
                        lang.caption?.let { Text(tr(it), fontSize = 12.sp, color = PulseColors.textMuted) }
                    }
                }
            }
        }
        Text(
            tr("The coach also replies in the language you choose."),
            fontSize = 12.sp, color = PulseColors.textMuted,
        )
    }

    confirming?.let { lang ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(tr("Strong language ahead")) },
            text = {
                Text(
                    trf(
                        "{0} swears, grumbles and judges your habits — the coach too. The numbers stay exactly as " +
                            "accurate, and anything serious is said plainly. If you easily get offended, maybe stick " +
                            "with the boring version.",
                        lang.label,
                    ),
                )
            },
            confirmButton = { TextButton(onClick = { confirming = null; switchTo(context, lang) }) { Text(tr("Bring it on")) } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(tr("Never mind")) } },
        )
    }
}

private fun switchTo(context: Context, lang: AppLanguage) {
    L10n.set(context, lang)
    // Rebuild the Activity so dates, numbers and Android's own text follow too.
    (context as? Activity)?.recreate()
}
