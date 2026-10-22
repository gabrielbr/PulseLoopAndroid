package com.pulseloop.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.ui.i18n.AppLanguage
import com.pulseloop.ui.i18n.L10n
import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.theme.PulseColors

/** Settings → Language. Each option names itself in its own language, so anyone can find theirs. */
@Composable
fun LanguageSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    SettingsSubScreen(title = tr("Language"), onBack = onBack) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PulseColors.card),
        ) {
            AppLanguage.entries.forEach { lang ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (lang != L10n.language) {
                            L10n.set(context, lang)
                            // Rebuild the Activity so dates, numbers and Android's own text follow too.
                            (context as? Activity)?.recreate()
                        }
                    }.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = L10n.language == lang, onClick = null)
                    Text(
                        if (lang == AppLanguage.SYSTEM) tr("System default") else lang.label,
                        fontSize = 15.sp, color = PulseColors.textPrimary,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
        Text(
            tr("The coach also replies in the language you choose."),
            fontSize = 12.sp, color = PulseColors.textMuted,
        )
    }
}
