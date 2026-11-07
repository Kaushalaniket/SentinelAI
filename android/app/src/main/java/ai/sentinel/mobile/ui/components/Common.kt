package ai.sentinel.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.sentinel.mobile.ui.theme.SentinelBackground
import ai.sentinel.mobile.ui.theme.SentinelCard
import ai.sentinel.mobile.ui.theme.SentinelGrid
import ai.sentinel.mobile.ui.theme.SentinelLine
import ai.sentinel.mobile.ui.theme.SentinelSurface
import ai.sentinel.mobile.ui.theme.SentinelText
import ai.sentinel.mobile.ui.theme.SentinelTextSecondary
import ai.sentinel.mobile.ui.theme.SentinelTitleText

@Composable
fun Panel(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SentinelCard), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = SentinelTitleText)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SentinelTextSecondary)
                    }
                }
                trailing?.invoke()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun StatTile(
    label: String,
    value: String,
    hint: String? = null,
    tint: Color = SentinelTitleText,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = SentinelCard), shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = SentinelTextSecondary, letterSpacing = 0.6.sp)
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, color = tint, fontWeight = FontWeight.Bold)
            if (hint != null) {
                Spacer(Modifier.height(2.dp))
                Text(hint, style = MaterialTheme.typography.bodySmall, color = SentinelTextSecondary)
            }
        }
    }
}

@Composable
fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(color.copy(alpha = 0.16f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun EmptyState(title: String, body: String) {
    Column(modifier = Modifier.fillMaxWidth().background(SentinelBackground).padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = SentinelTitleText)
        Text(body, style = MaterialTheme.typography.bodySmall, color = SentinelTextSecondary)
    }
}

@Composable
fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(label, modifier = Modifier.width(148.dp), style = MaterialTheme.typography.bodySmall, color = SentinelTextSecondary)
        Text(value, style = if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), color = SentinelText)
    }
}

@Composable
fun SectionHeading(text: String, hint: String? = null) {
    Column(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = SentinelTitleText)
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = SentinelTextSecondary)
        }
    }
}
