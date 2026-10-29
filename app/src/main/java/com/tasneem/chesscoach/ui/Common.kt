package com.tasneem.chesscoach.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tasneem.chesscoach.ui.theme.Amoled

val CardShape = RoundedCornerShape(16.dp)

@Composable
fun AmoledCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(CardShape).background(Amoled.Surface).border(1.dp, Amoled.Border, CardShape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(16.dp),
        content = content,
    )
}

@Composable
fun ScreenTitle(title: String, onBack: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Amoled.Text)
            }
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Amoled.Text, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = Amoled.Muted, modifier = modifier.padding(top = 20.dp, bottom = 8.dp))
}

@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier, color: Color = Amoled.Text) {
    Column(modifier) {
        Text(value, color = color, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Amoled.Muted, fontSize = 12.sp)
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, color: Color = Amoled.Accent, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(if (enabled) color else Amoled.Raised)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) Amoled.Black else Amoled.Muted, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, color: Color = Amoled.Text, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, Amoled.Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = color, fontWeight = FontWeight.Medium, fontSize = 14.sp) }
}

/** Horizontal percentage bar with a label and value. */
@Composable
fun PercentBar(label: String, value: Double, color: Color = Amoled.Accent) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row {
            Text(label, color = Amoled.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("${value.toInt()}%", color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Amoled.Raised)) {
            Box(Modifier.fillMaxWidth((value / 100).toFloat().coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
}

@Composable
fun Dot(color: Color) = Box(Modifier.size(8.dp).clip(CircleShape).background(color))

@Composable
fun InsightRow(title: String, detail: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Box(Modifier.padding(top = 6.dp)) { Dot(color) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = Amoled.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(detail, color = Amoled.Muted, fontSize = 13.sp)
        }
    }
}

fun accuracyColor(a: Double): Color = when {
    a >= 85 -> Amoled.Accent
    a >= 70 -> Amoled.Good
    a >= 55 -> Amoled.Warn
    else -> Amoled.Bad
}
