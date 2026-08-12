package com.shahar.shaharsat.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.shahar.shaharsat.ui.theme.MissionColors
import java.util.Locale

/** Card shell shared by every dashboard section — see MissionCards below for content. */
@Composable
fun MissionCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MissionColors.Surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MissionColors.TextSecondary)
            content()
        }
    }
}

/** A single label/value row — "--" for a null value, never a fabricated 0 (see docs/TELEMETRY.md). */
@Composable
fun MetricRow(label: String, value: String?, unit: String = "", valueColor: Color = MissionColors.TextPrimary) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MissionColors.TextSecondary)
        Text(
            text = if (value != null) "$value$unit" else "--",
            style = MaterialTheme.typography.bodyLarge,
            color = if (value != null) valueColor else MissionColors.TextDisabled,
            fontFamily = FontFamily.Monospace
        )
    }
}

fun Float?.fmt(decimals: Int = 1): String? = this?.let { String.format(Locale.US, "%.${decimals}f", it) }
