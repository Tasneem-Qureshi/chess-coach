package com.tasneem.chesscoach.overlay

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tasneem.chesscoach.analysis.GameAnalyzer
import com.tasneem.chesscoach.analysis.MoveClass
import com.tasneem.chesscoach.ui.theme.Amoled
import kotlinx.coroutines.flow.StateFlow

private val Shape = RoundedCornerShape(16.dp)

@Composable
fun CoachPanel(state: StateFlow<CoachUi>, actions: CoachActions, onDrag: (Float, Float) -> Unit) {
    val ui by state.collectAsState()
    val drag = Modifier.pointerInput(Unit) { detectDragGestures { change, d -> change.consume(); onDrag(d.x, d.y) } }

    if (ui.collapsed) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(Amoled.Black).border(1.5.dp, if (ui.yourTurn) Amoled.Accent else Amoled.Border, CircleShape)
                .then(drag).clickable { actions.toggleCollapsed() },
            contentAlignment = Alignment.Center,
        ) {
            Text(ui.evalText.ifEmpty { "♞" }, color = Amoled.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        return
    }

    Column(
        Modifier.widthIn(max = 320.dp).clip(Shape).background(Amoled.Black.copy(alpha = 0.94f)).border(1.dp, Amoled.Border, Shape)
            .animateContentSize().padding(12.dp),
    ) {
        // header: drag handle, eval, collapse, close
        Row(drag.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EvalBar(ui.evalCp, ui.userIsWhite)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        ui.stage != Stage.TRACKING -> "Coach"
                        ui.yourTurn -> "Your move"
                        else -> "Opponent's move"
                    },
                    color = if (ui.yourTurn) Amoled.Accent else Amoled.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                )
                if (ui.evalText.isNotEmpty()) Text("Eval ${ui.evalText} · move ${ui.plies / 2 + 1}", color = Amoled.Muted, fontSize = 11.sp)
            }
            if (ui.thinking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Amoled.Accent)
            IconBtn(Icons.Default.ExpandLess) { actions.toggleCollapsed() }
            IconBtn(Icons.Default.Close) { actions.close() }
        }

        if (ui.stage != Stage.TRACKING || ui.result != null) {
            Spacer(Modifier.height(8.dp))
            Text(ui.result ?: ui.message, color = Amoled.Text, fontSize = 13.sp)
        }
        if (ui.stage == Stage.TRACKING && ui.result == null) ui.note?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Amoled.Warn, fontSize = 12.sp)
        }

        ui.feedback?.let { f ->
            Spacer(Modifier.height(8.dp))
            val c = Amoled.of(f.cls)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text("${f.cls.symbol} ", color = c, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    "${f.san} — ${f.cls.label}" + if (f.cls >= MoveClass.INACCURACY && f.bestSan.isNotEmpty()) ". Better: ${f.bestSan}" else "",
                    color = Amoled.Text, fontSize = 13.sp,
                )
            }
        }

        if (ui.stage == Stage.TRACKING && ui.result == null) {
            val s = ui.suggestion
            if (ui.yourTurn && s != null) {
                Spacer(Modifier.height(10.dp))
                if (!ui.revealed) {
                    if (s.threat != null) Text("⚠ Watch out: ${s.threat}", color = Amoled.Warn, fontSize = 13.sp)
                    Text("Think first: what are the checks, captures and threats?", color = Amoled.Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(if (ui.hint) "Hint shown" else "Hint", Amoled.Raised, Amoled.Text, Modifier.weight(1f)) { actions.hint() }
                        Pill("Reveal best move", Amoled.Accent, Amoled.Black, Modifier.weight(1.6f)) { actions.reveal() }
                    }
                } else {
                    s.lines.forEachIndexed { i, l ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(if (i == 0) Amoled.Accent else Amoled.Alt))
                            Spacer(Modifier.width(8.dp))
                            Text(l.san, color = Amoled.Text, fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal, fontSize = if (i == 0) 17.sp else 14.sp)
                            Spacer(Modifier.weight(1f))
                            Text(l.score, color = Amoled.Muted, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    if (s.why.isNotEmpty()) Text("Why: ${s.why}", color = Amoled.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    s.lines.firstOrNull()?.let { Text(it.line, color = Amoled.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)) }
                    if (s.threat != null) Text("⚠ Their threat: ${s.threat}", color = Amoled.Warn, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
            } else if (!ui.yourTurn) {
                ui.opponentIdea?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("They'll likely play $it", color = Amoled.Muted, fontSize = 13.sp)
                }
            }
        }

        if (ui.adjusting) {
            Spacer(Modifier.height(10.dp))
            Text("Line the pink grid up with the board", color = Amoled.Muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                listOf("←" to Triple(-2, 0, 0), "→" to Triple(2, 0, 0), "↑" to Triple(0, -2, 0), "↓" to Triple(0, 2, 0),
                    "−" to Triple(1, 1, -2), "+" to Triple(-1, -1, 2)).forEach { (label, d) ->
                    Pill(label, Amoled.Raised, Amoled.Text, Modifier.weight(1f)) { actions.nudge(d.first, d.second, d.third) }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Flip") { actions.flipBoard() }
            Chip("Side ⇄") { actions.switchSideToMove() }
            Chip(if (ui.adjusting) "Done" else "Adjust") { actions.toggleAdjust() }
            Chip("Find board") { actions.redetect() }
        }
        Spacer(Modifier.height(6.dp))
        Pill(if (ui.result != null) "Review this game" else "Finish & review", Amoled.Raised, Amoled.Accent, Modifier.fillMaxWidth()) { actions.finishAndReview() }
    }
}

@Composable
private fun EvalBar(cp: Int, userIsWhite: Boolean) {
    // share of the bar that is "white", from the user's side
    val white = (GameAnalyzer.winPercent(cp) / 100).toFloat()
    val mine = if (userIsWhite) white else 1 - white
    Box(Modifier.width(6.dp).height(34.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF2A2A2A))) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(mine.coerceIn(0.02f, 0.98f)).align(Alignment.BottomCenter).background(Amoled.Text))
    }
}

@Composable
private fun IconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Amoled.Muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Pill(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(bg).border(1.dp, Amoled.Border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
}

@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, Amoled.Border, RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) { Text(text, color = Amoled.Muted, fontSize = 11.sp) }
}
