package com.tasneem.chesscoach.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tasneem.chesscoach.ChessCoachApp
import com.tasneem.chesscoach.analysis.InsightsBuilder
import com.tasneem.chesscoach.chess.Move
import com.tasneem.chesscoach.chess.Position
import com.tasneem.chesscoach.data.Opening
import com.tasneem.chesscoach.data.Puzzle
import com.tasneem.chesscoach.ui.theme.Amoled
import kotlinx.coroutines.delay

@Composable
fun TrainScreen() {
    val app = ChessCoachApp.get(LocalContext.current)
    val content by app.content.content.collectAsState()
    val games by app.games.games.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val mistakes = remember(games) { InsightsBuilder.mistakePuzzles(games) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Train")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Segment("This week", tab == 0) { tab = 0 }
            Segment("My mistakes", tab == 1) { tab = 1 }
            Segment("Openings", tab == 2) { tab = 2 }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.padding(horizontal = 16.dp)) {
            when (tab) {
                0 -> if (content.puzzles.isEmpty()) Empty("No puzzles yet — they arrive with the weekly update.")
                else {
                    Text("Week ${content.week}" + if (content.theme.isNotEmpty()) " · focus: ${content.theme}" else "", color = Amoled.Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    PuzzleRunner(content.puzzles, "weekly")
                }
                1 -> if (mistakes.isEmpty()) Empty("Review a few games first: every mistake you make becomes a puzzle here.")
                else PuzzleRunner(mistakes, "mistakes")
                2 -> OpeningStudy(content.focus)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Empty(text: String) = Text(text, color = Amoled.Muted, modifier = Modifier.padding(vertical = 24.dp))

private fun solvedSet(c: Context) = c.getSharedPreferences("puzzles", Context.MODE_PRIVATE)

@Composable
private fun PuzzleRunner(puzzles: List<Puzzle>, key: String) {
    val context = LocalContext.current
    val prefs = remember { solvedSet(context) }
    var index by remember(puzzles) {
        val solved = prefs.getStringSet("solved", emptySet())!!
        mutableIntStateOf(puzzles.indexOfFirst { it.id !in solved }.coerceAtLeast(0))
    }
    val puzzle = puzzles[index.coerceIn(0, puzzles.lastIndex)]
    var pos by remember(puzzle.id) { mutableStateOf(Position.fromFen(puzzle.fen)) }
    var step by remember(puzzle.id) { mutableIntStateOf(0) }
    var status by remember(puzzle.id) { mutableStateOf("") }
    var solved by remember(puzzle.id) { mutableStateOf(false) }
    var showSolution by remember(puzzle.id) { mutableStateOf(false) }
    var last by remember(puzzle.id) { mutableStateOf<Move?>(null) }
    val userWhite = remember(puzzle.id) { Position.fromFen(puzzle.fen).whiteToMove }
    val solvedCount = puzzles.count { it.id in (prefs.getStringSet("solved", emptySet()) ?: emptySet()) }

    // the opponent's replies in multi-move puzzles
    LaunchedEffect(puzzle.id, step) {
        if (step % 2 == 1 && step < puzzle.moves.size) {
            delay(450)
            Move.fromUci(puzzle.moves[step])?.takeIf { pos.isLegal(it) }?.let { pos = pos.play(it); last = it }
            step++
        }
    }

    fun markSolved() {
        solved = true; status = "Solved!"
        prefs.edit().putStringSet("solved", (prefs.getStringSet("solved", emptySet())!! + puzzle.id)).apply()
    }

    AmoledCard {
        Row {
            Text(
                (if (userWhite) "White" else "Black") + " to move — find the best move",
                color = Amoled.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            )
            Text("${index + 1}/${puzzles.size}", color = Amoled.Muted, fontSize = 13.sp)
        }
        val meta = listOfNotNull(puzzle.rating.takeIf { it > 0 }?.let { "rating $it" }, puzzle.source.takeIf { key == "mistakes" }, puzzle.themes.take(3).joinToString(", ").ifEmpty { null })
        Text(meta.joinToString(" · "), color = Amoled.Muted, fontSize = 12.sp)
        Text("$solvedCount solved", color = Amoled.Muted, fontSize = 12.sp)
    }
    Spacer(Modifier.height(10.dp))
    val expected = puzzle.moves.getOrNull(step)?.let { Move.fromUci(it) }
    ChessBoard(
        pos, whiteAtBottom = userWhite, lastMove = last,
        arrows = if (showSolution && expected != null) listOf(BoardArrow(expected, Amoled.Accent)) else emptyList(),
        onMove = if (solved || step % 2 == 1) null else { m ->
            val ok = expected != null && m.from == expected.from && m.to == expected.to
            val mates = pos.play(m).isCheckmate()
            if (ok || mates) {
                pos = pos.play(m); last = m; step++
                showSolution = false
                if (mates || step >= puzzle.moves.size) markSolved() else status = "Correct — keep going"
            } else status = "Not quite. Look for checks, captures and threats."
        },
    )
    Spacer(Modifier.height(10.dp))
    if (status.isNotEmpty()) Text(status, color = if (solved) Amoled.Good else if (status.startsWith("Correct")) Amoled.Accent else Amoled.Warn, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton("Show answer", Modifier.weight(1f)) { showSolution = true }
        PrimaryButton("Next", Modifier.weight(1f)) { index = (index + 1) % puzzles.size }
    }
}

@Composable
private fun OpeningStudy(openings: List<Opening>) {
    if (openings.isEmpty()) { Empty("This week's openings arrive with the weekly update."); return }
    var pick by remember { mutableIntStateOf(0) }
    val o = openings[pick.coerceIn(0, openings.lastIndex)]
    val positions = remember(o) {
        val out = arrayListOf(Position.start()); val moves = ArrayList<Move>()
        for (s in o.sans) { val m = out.last().parseSan(s) ?: break; moves += m; out += out.last().play(m) }
        out to moves
    }
    var ply by remember(o) { mutableIntStateOf(positions.second.size) }
    Column {
        openings.forEachIndexed { i, op ->
            AmoledCard(Modifier.padding(bottom = 8.dp), onClick = { pick = i }) {
                Text("${op.eco} ${op.name}", color = if (i == pick) Amoled.Accent else Amoled.Text, fontWeight = FontWeight.SemiBold)
                Text(op.sans.chunked(2).mapIndexed { n, p -> "${n + 1}. ${p.joinToString(" ")}" }.joinToString(" "), color = Amoled.Muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        ChessBoard(positions.first[ply], lastMove = positions.second.getOrNull(ply - 1))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("◀", Modifier.weight(1f)) { ply = (ply - 1).coerceAtLeast(0) }
            GhostButton("▶", Modifier.weight(1f)) { ply = (ply + 1).coerceAtMost(positions.second.size) }
        }
        Text("Play through the line and ask yourself why each move is played.", color = Amoled.Muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}
