package com.tasneem.chesscoach.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tasneem.chesscoach.ChessCoachApp
import com.tasneem.chesscoach.Route
import com.tasneem.chesscoach.analysis.InsightsBuilder
import com.tasneem.chesscoach.ui.theme.Amoled

@Composable
fun ProgressScreen(open: (Route) -> Unit) {
    val app = ChessCoachApp.get(LocalContext.current)
    val games by app.games.games.collectAsState()
    val ins = remember(games) { InsightsBuilder.build(games) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Progress")
        Column(Modifier.padding(horizontal = 16.dp)) {
            if (ins == null) {
                Text("Review a game to see your strengths and weaknesses across all your matches.", color = Amoled.Muted, modifier = Modifier.padding(vertical = 24.dp))
                return@Column
            }
            AmoledCard {
                Row {
                    Stat("${ins.avgAccuracy.toInt()}%", "avg accuracy", Modifier.weight(1f), accuracyColor(ins.avgAccuracy))
                    Stat("${ins.games}", "games reviewed", Modifier.weight(1f))
                    Stat("${ins.score.first}-${ins.score.second}-${ins.score.third}", "W-D-L", Modifier.weight(1f))
                }
                if (ins.recentAccuracy.size >= 2) {
                    Spacer(Modifier.height(14.dp))
                    Text("Accuracy, last ${ins.recentAccuracy.size} games", color = Amoled.Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Sparkline(ins.recentAccuracy)
                }
            }

            if (ins.tagRate.isNotEmpty()) {
                SectionLabel("What costs you the most")
                AmoledCard {
                    ins.tagRate.take(4).forEach { (t, rate) ->
                        InsightRow("${t.label} · ${"%.1f".format(rate)} per game", t.tip, Amoled.Bad)
                    }
                }
            }

            if (ins.phaseAccuracy.isNotEmpty()) {
                SectionLabel("By phase")
                AmoledCard { ins.phaseAccuracy.forEach { (ph, a) -> PercentBar(ph.label, a, accuracyColor(a)) } }
                val best = ins.phaseAccuracy.maxBy { it.value }; val worst = ins.phaseAccuracy.minBy { it.value }
                if (best.key != worst.key) {
                    AmoledCard(Modifier.padding(top = 10.dp)) {
                        InsightRow("Strongest: ${best.key.label}", "${best.value.toInt()}% average accuracy.", Amoled.Good)
                        InsightRow("Train next: ${worst.key.label}", "${worst.value.toInt()}% average accuracy — the Train tab has puzzles from your own mistakes.", Amoled.Warn)
                    }
                }
            }

            if (ins.openings.isNotEmpty()) {
                SectionLabel("Openings")
                AmoledCard {
                    ins.openings.take(8).forEach { o ->
                        Row(Modifier.padding(vertical = 5.dp)) {
                            Text(o.name, color = Amoled.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text("${o.points.fmt()}/${o.games} · ${o.accuracy.toInt()}%", color = Amoled.Muted, fontSize = 13.sp)
                        }
                    }
                }
            }

            SectionLabel("Per game")
            AmoledCard {
                ins.classRate.forEach { (c, rate) ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Dot(Amoled.of(c)); Spacer(Modifier.padding(5.dp))
                        Text(c.label, color = Amoled.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text("%.1f".format(rate), color = Amoled.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun Double.fmt() = if (this % 1.0 == 0.0) toInt().toString() else "%.1f".format(this)

@Composable
private fun Sparkline(values: List<Double>) {
    Canvas(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(8.dp)).background(Amoled.Raised)) {
        val lo = (values.min() - 5).coerceAtLeast(0.0); val hi = (values.max() + 5).coerceAtMost(100.0)
        val span = (hi - lo).coerceAtLeast(1.0)
        fun pt(i: Int) = Offset(i * size.width / (values.size - 1), (size.height - ((values[i] - lo) / span * size.height)).toFloat())
        val path = Path().apply { values.indices.forEach { i -> if (i == 0) moveTo(pt(i).x, pt(i).y) else lineTo(pt(i).x, pt(i).y) } }
        drawPath(path, Amoled.Accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(Amoled.Accent, 3.dp.toPx(), pt(values.lastIndex))
    }
}
