package com.tasneem.chesscoach.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tasneem.chesscoach.ChessCoachApp
import com.tasneem.chesscoach.Route
import com.tasneem.chesscoach.chess.Pgn
import com.tasneem.chesscoach.data.ChessComApi
import com.tasneem.chesscoach.data.GameStore
import com.tasneem.chesscoach.data.SavedGame
import com.tasneem.chesscoach.ui.theme.Amoled
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun GamesScreen(open: (Route) -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var pasting by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Games") { GhostButton("Paste PGN") { pasting = true } }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Segment("My games", tab == 0) { tab = 0 }
            Segment("Chess.com", tab == 1) { tab = 1 }
        }
        Spacer(Modifier.height(8.dp))
        if (tab == 0) MyGames(open) else ChessComGames(open)
    }
    if (pasting) PasteDialog(onDone = { id -> pasting = false; id?.let { open(Route.Report(it)) } })
}

@Composable
fun Segment(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(20.dp)).background(if (selected) Amoled.Accent else Amoled.Black)
            .border(1.dp, if (selected) Amoled.Accent else Amoled.Border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
    ) { Text(text, color = if (selected) Amoled.Black else Amoled.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun MyGames(open: (Route) -> Unit) {
    val app = ChessCoachApp.get(LocalContext.current)
    val games by app.games.games.collectAsState()
    val progress by app.reviews.progress.collectAsState()
    if (games.isEmpty()) {
        Text(
            "No games yet. Play with the coach overlay, import from Chess.com, or paste a PGN.",
            color = Amoled.Muted, modifier = Modifier.padding(24.dp),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(games, key = { it.id }) { g -> GameRow(g, progress[g.id]) { open(Route.Report(g.id)) } }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun GameRow(g: SavedGame, p: com.tasneem.chesscoach.analysis.ReviewManager.Progress?, onClick: () -> Unit) {
    AmoledCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${g.outcome} vs ${g.opponent}", color = when (g.outcome) { "Won" -> Amoled.Good; "Lost" -> Amoled.Bad; else -> Amoled.Text }, fontWeight = FontWeight.SemiBold)
                Text(
                    listOf(g.timeClass.ifEmpty { null }, g.source, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(g.playedAt))).filterNotNull().joinToString(" · "),
                    color = Amoled.Muted, fontSize = 12.sp,
                )
                g.report?.opening?.let { Text(it, color = Amoled.Muted, fontSize = 12.sp) }
            }
            when {
                g.report != null -> Stat("${g.report.accuracy.toInt()}%", "accuracy", color = accuracyColor(g.report.accuracy))
                p != null && p.error == null -> CircularProgressIndicator(progress = { p.done.toFloat() / p.total }, color = Amoled.Accent, trackColor = Amoled.Raised)
                else -> Text("Review →", color = Amoled.Accent, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ChessComGames(open: (Route) -> Unit) {
    val app = ChessCoachApp.get(LocalContext.current)
    val settings by app.settings.flow.collectAsState()
    val saved by app.games.games.collectAsState()
    var user by remember(settings.chessComUser) { mutableStateOf(settings.chessComUser) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<ChessComApi.RemoteGame>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun load() {
        app.settings.update { it.copy(chessComUser = user) }
        loading = true; error = null
        scope.launch {
            runCatching { ChessComApi.recentGames(user) }
                .onSuccess { list = it; if (it.isEmpty()) error = "No games in the last two months." }
                .onFailure { error = it.message ?: "Could not reach Chess.com" }
            loading = false
        }
    }

    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = user, onValueChange = { user = it }, singleLine = true, label = { Text("Chess.com username") },
                modifier = Modifier.weight(1f), colors = fieldColors(),
            )
            Spacer(Modifier.width(8.dp))
            PrimaryButton(if (loading) "…" else "Load", enabled = user.isNotBlank() && !loading) { load() }
        }
        error?.let { Text(it, color = Amoled.Bad, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
    }
    Spacer(Modifier.height(8.dp))
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list, key = { it.url }) { r ->
            val existing = saved.firstOrNull { it.url == r.url }
            val outcome = when (r.result) {
                "1-0" -> if (r.userIsWhite) "Won" else "Lost"
                "0-1" -> if (r.userIsWhite) "Lost" else "Won"
                else -> "Draw"
            }
            AmoledCard(onClick = {
                if (existing != null) open(Route.Report(existing.id))
                else scope.launch {
                    val g = app.games.save(
                        SavedGame(GameStore.newId(), "chess.com", r.white, r.black, r.userIsWhite, r.result, r.endTime, r.pgn, r.timeClass, r.url),
                    )
                    app.reviews.review(g.id)
                    open(Route.Report(g.id))
                }
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("$outcome vs ${if (r.userIsWhite) r.black else r.white}", color = Amoled.Text, fontWeight = FontWeight.SemiBold)
                        Text("${r.timeClass} · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.endTime))}", color = Amoled.Muted, fontSize = 12.sp)
                    }
                    Text(if (existing != null) "Open" else "Review →", color = Amoled.Accent)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun PasteDialog(onDone: (String?) -> Unit) {
    val app = ChessCoachApp.get(LocalContext.current)
    var text by remember { mutableStateOf("") }
    var asWhite by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { onDone(null) },
        containerColor = Amoled.Surface,
        title = { Text("Paste a game (PGN)", color = Amoled.Text) },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp), colors = fieldColors(), placeholder = { Text("1. e4 e5 2. Nf3 …") })
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Segment("I was White", asWhite) { asWhite = true }
                    Segment("I was Black", !asWhite) { asWhite = false }
                }
                error?.let { Text(it, color = Amoled.Bad, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val g = runCatching { Pgn.parse(Pgn.splitGames(text).firstOrNull() ?: text) }
                g.onFailure { error = it.message }
                g.onSuccess { game ->
                    if (game.moves.size < 2) { error = "No moves found"; return@onSuccess }
                    scope.launch {
                        val h = game.headers
                        val saved = app.games.save(
                            SavedGame(
                                GameStore.newId(), "pgn", h["White"] ?: if (asWhite) "You" else "Opponent", h["Black"] ?: if (asWhite) "Opponent" else "You",
                                asWhite, h["Result"] ?: "*", System.currentTimeMillis(), game.toPgn(),
                            ),
                        )
                        app.reviews.review(saved.id)
                        onDone(saved.id)
                    }
                }
            }) { Text("Review", color = Amoled.Accent) }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel", color = Amoled.Muted) } },
    )
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Amoled.Accent, unfocusedBorderColor = Amoled.Border, cursorColor = Amoled.Accent,
    focusedLabelColor = Amoled.Accent, unfocusedLabelColor = Amoled.Muted, focusedTextColor = Amoled.Text, unfocusedTextColor = Amoled.Text,
)
