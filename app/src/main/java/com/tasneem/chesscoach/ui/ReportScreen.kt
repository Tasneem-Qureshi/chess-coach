package com.tasneem.chesscoach.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tasneem.chesscoach.ChessCoachApp
import com.tasneem.chesscoach.analysis.GameAnalyzer
import com.tasneem.chesscoach.analysis.GameReport
import com.tasneem.chesscoach.analysis.MoveClass
import com.tasneem.chesscoach.chess.Game
import com.tasneem.chesscoach.chess.Pgn
import com.tasneem.chesscoach.data.SavedGame
import com.tasneem.chesscoach.ui.theme.Amoled
import kotlinx.coroutines.launch

@Composable
fun ReportScreen(gameId: String, onBack: () -> Unit) {
    val app = ChessCoachApp.get(LocalContext.current)
    val games by app.games.games.collectAsState()
    val progress by app.reviews.progress.collectAsState()
    val g = games.firstOrNull { it.id == gameId }
    val game = remember(g?.pgn) { g?.let { runCatching { Pgn.parse(it.pgn) }.getOrNull() } }
    val scope = rememberCoroutineScope()

    LaunchedEffect(g?.id, g?.report == null) { if (g != null && g.report == null && progress[g.id] == null) app.reviews.review(g.id) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle(g?.let { "vs ${it.opponent}" } ?: "Game", onBack) {
            if (g != null) GhostButton("Delete", color = Amoled.Bad) { scope.launch { app.games.delete(g.id); onBack() } }
        }
        if (g == null || game == null) { Text("Game not found.", color = Amoled.Muted, modifier = Modifier.padding(20.dp)); return }
        Column(Modifier.padding(horizontal = 16.dp)) {
            val report = g.report
            val p = progress[g.id]
            if (report == null) {
                AmoledCard {
                    if (p?.error != null) {
                        Text("Review failed: ${p.error}", color = Amoled.Bad)
                        Spacer(Modifier.height(10.dp))
                        PrimaryButton("Try again") { app.reviews.review(g.id) }
                    } else {
                        Text("Reviewing every move with Stockfish…", color = Amoled.Text)
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { p?.let { it.done.toFloat() / it.total } ?: 0f }, modifier = Modifier.fillMaxWidth(),
                            color = Amoled.Accent, trackColor = Amoled.Raised,
                        )
                        Text("${p?.done ?: 0} of ${p?.total ?: game.positions.size} positions", color = Amoled.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                ChessBoard(game.positions.last(), whiteAtBottom = g.userIsWhite)
            } else ReportBody(g, game, report)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportBody(g: SavedGame, game: Game, r: GameReport) {
    var ply by remember(g.id) { mutableIntStateOf(r.keyMoments.firstOrNull()?.ply ?: (game.moves.size - 1)) }
    val review = r.plies.getOrNull(ply)

    // summary
    AmoledCard {
        Text("${g.outcome} · ${g.white} vs ${g.black}", color = Amoled.Text, fontWeight = FontWeight.SemiBold)
        r.opening?.let { Text(it, color = Amoled.Muted, fontSize = 13.sp) }
        Spacer(Modifier.height(12.dp))
        Row {
            Stat("${r.accuracy.toInt()}%", "your accuracy", Modifier.weight(1f), accuracyColor(r.accuracy))
            Stat("${r.opponentAccuracy.toInt()}%", "opponent", Modifier.weight(1f), Amoled.Muted)
            Stat("${r.counts[MoveClass.BLUNDER] ?: 0}/${r.counts[MoveClass.MISTAKE] ?: 0}", "blunders/mistakes", Modifier.weight(1f))
        }
    }

    // board + current move
    Spacer(Modifier.height(12.dp))
    val shownPos = game.positions[(ply + 1).coerceAtMost(game.positions.lastIndex)]
    val before = game.positions[ply.coerceIn(0, game.moves.lastIndex)]
    val showBest = review != null && review.cls.isError && review.bestMove != null
    ChessBoard(
        if (showBest) before else shownPos,
        whiteAtBottom = g.userIsWhite,
        lastMove = if (showBest) null else game.moves.getOrNull(ply),
        arrows = buildList {
            if (showBest) {
                add(BoardArrow(review!!.move, Amoled.of(review.cls)))
                add(BoardArrow(review.bestMove!!, Amoled.Accent))
            }
        },
    )
    review?.let { rv ->
        Spacer(Modifier.height(10.dp))
        AmoledCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${rv.ply / 2 + 1}${if (rv.whiteMoved) "." else "…"} ${rv.san}", color = Amoled.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.padding(4.dp))
                Text("${rv.cls.symbol} ${rv.cls.label}", color = Amoled.of(rv.cls), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(rv.evalAfterText, color = Amoled.Muted, fontSize = 13.sp)
            }
            if (rv.cls.isError && rv.bestSan.isNotEmpty()) {
                Text("Best was ${rv.bestSan}: ${rv.explanation}", color = Amoled.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                Text(rv.bestLine, color = Amoled.Muted, fontSize = 12.sp)
            } else if (rv.cls == MoveClass.BEST) {
                Text(rv.explanation.ifEmpty { "Engine's top choice." }, color = Amoled.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (rv.tags.isNotEmpty()) Text(rv.tags.joinToString(" · ") { it.label }, color = Amoled.Warn, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("◀", Modifier.weight(1f)) { ply = (ply - 1).coerceAtLeast(0) }
                GhostButton("▶", Modifier.weight(1f)) { ply = (ply + 1).coerceAtMost(game.moves.lastIndex) }
            }
        }
    }

    SectionLabel("Evaluation")
    EvalGraph(r, ply) { ply = it }

    SectionLabel("Moves")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        r.plies.forEach { p ->
            val mine = p.whiteMoved == r.userIsWhite
            val color = if (mine && p.cls >= MoveClass.INACCURACY) Amoled.of(p.cls) else if (mine) Amoled.Text else Amoled.Muted
            Text(
                (if (p.whiteMoved) "${p.ply / 2 + 1}." else "") + p.san + if (mine && p.cls >= MoveClass.INACCURACY) p.cls.symbol else "",
                color = color, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (p.ply == ply) Amoled.Raised else Amoled.Black)
                    .clickable { ply = p.ply }.padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }

    if (r.keyMoments.isNotEmpty()) {
        SectionLabel("Key moments")
        AmoledCard {
            r.keyMoments.forEach { k ->
                Row(Modifier.fillMaxWidth().clickable { ply = k.ply }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(Amoled.of(k.cls))
                    Spacer(Modifier.padding(5.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Move ${k.ply / 2 + 1}: ${k.san} — ${k.cls.label}", color = Amoled.Text, fontSize = 14.sp)
                        Text("Better: ${k.bestSan}. ${k.explanation}", color = Amoled.Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    SectionLabel("Strengths")
    AmoledCard { r.strengths.forEach { InsightRow(it.title, it.detail, Amoled.Good) } }

    SectionLabel("Weaknesses")
    AmoledCard {
        if (r.weaknesses.isEmpty()) Text("Nothing stood out — clean game.", color = Amoled.Muted)
        r.weaknesses.forEach { InsightRow(it.title, it.detail, Amoled.Bad) }
    }

    if (r.phaseAccuracy.isNotEmpty()) {
        SectionLabel("By phase")
        AmoledCard { r.phaseAccuracy.forEach { (ph, a) -> PercentBar(ph.label, a, accuracyColor(a)) } }
    }

    SectionLabel("Your moves")
    AmoledCard {
        MoveClass.entries.forEach { c ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(Amoled.of(c)); Spacer(Modifier.padding(5.dp))
                Text(c.label, color = Amoled.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("${r.counts[c] ?: 0}", color = Amoled.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Win chance for white over the game; dots mark your errors. Tap to jump. */
@Composable
private fun EvalGraph(r: GameReport, ply: Int, onPick: (Int) -> Unit) {
    val n = r.plies.size
    Canvas(
        Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(12.dp)).background(Amoled.Surface)
            .pointerInput(n) { detectTapGestures { o -> if (n > 0) onPick(((o.x / size.width) * n).toInt().coerceIn(0, n - 1)) } },
    ) {
        if (n == 0) return@Canvas
        val w = size.width; val h = size.height
        fun x(i: Int) = (i + 0.5f) / n * w
        fun y(cp: Int) = h - (GameAnalyzer.winPercent(cp) / 100).toFloat() * h
        val area = Path().apply {
            moveTo(0f, h)
            r.plies.forEachIndexed { i, p -> lineTo(x(i), y(p.evalAfterWhite)) }
            lineTo(w, h); close()
        }
        drawRect(Amoled.Raised, size = size)
        drawPath(area, Amoled.Text.copy(alpha = 0.85f))
        drawLine(Amoled.Border, Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 1f)
        r.plies.forEachIndexed { i, p ->
            if (p.whiteMoved == r.userIsWhite && p.cls >= MoveClass.MISTAKE) drawCircle(Amoled.of(p.cls), 5.dp.toPx(), Offset(x(i), y(p.evalAfterWhite)))
        }
        drawLine(Amoled.Accent, Offset(x(ply), 0f), Offset(x(ply), h), strokeWidth = 2f)
        drawRect(Amoled.Border, size = size, style = Stroke(1f))
    }
}
