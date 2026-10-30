package com.tasneem.chesscoach.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tasneem.chesscoach.ChessCoachApp
import com.tasneem.chesscoach.Route
import com.tasneem.chesscoach.capture.CoachService
import com.tasneem.chesscoach.data.AppUpdater
import com.tasneem.chesscoach.ui.theme.Amoled

@Composable
fun CoachScreen(onStartCoach: () -> Unit, open: (Route) -> Unit, onTab: (Route) -> Unit) {
    val context = LocalContext.current
    val app = ChessCoachApp.get(context)
    val running by CoachService.running.collectAsState()
    val games by app.games.games.collectAsState()
    val content by app.content.content.collectAsState()
    val update by app.updater.state.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Chess Coach") {
            IconButton(onClick = { open(Route.Settings) }) { Icon(Icons.Default.Settings, "Settings", tint = Amoled.Muted) }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            when (val u = update) {
                is AppUpdater.State.Available -> AmoledCard(onClick = { app.updater.start(u.update) }) {
                    Text("Update ${u.update.versionName} available", color = Amoled.Accent, fontWeight = FontWeight.SemiBold)
                    Text("Tap to download and install.", color = Amoled.Muted, fontSize = 13.sp)
                }
                is AppUpdater.State.Downloading -> AmoledCard {
                    Text("Downloading ${u.update.versionName}… ${(u.progress * 100).toInt()}%", color = Amoled.Text)
                    Text("You can leave the app — it keeps downloading and shows in your notifications.", color = Amoled.Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { u.progress }, modifier = Modifier.fillMaxWidth(), color = Amoled.Accent, trackColor = Amoled.Raised)
                }
                is AppUpdater.State.Failed -> AmoledCard {
                    Text("Update failed: ${u.message}", color = Amoled.Bad, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("Try again", Modifier.weight(1f)) { app.updater.start(u.update) }
                        GhostButton("Download in browser", Modifier.weight(1f)) { app.updater.openInBrowser() }
                    }
                }
                AppUpdater.State.Idle -> Unit
            }
            if (update !is AppUpdater.State.Idle) Spacer(Modifier.height(12.dp))

            // main action
            AmoledCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (running) Amoled.Good else Amoled.Border))
                    Spacer(Modifier.width(8.dp))
                    Text(if (running) "Coach is watching your screen" else "Coach is off", color = Amoled.Text, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Open your game in the Chess.com app. The coach reads the board, shows the best moves with the reason behind them, and rates each move you play.",
                    color = Amoled.Muted, fontSize = 14.sp,
                )
                Spacer(Modifier.height(14.dp))
                if (running) GhostButton("Stop coach", Modifier.fillMaxWidth(), color = Amoled.Bad) { CoachService.stop(context) }
                else PrimaryButton("Start coach overlay", Modifier.fillMaxWidth(), onClick = onStartCoach)
            }

            SectionLabel("How it works")
            AmoledCard {
                Step("1", "Tap Start, allow “Display over other apps”, then choose “Entire screen” when Android asks to share.")
                Step("2", "Start a new game once: the coach learns your board and piece style from the starting position.")
                Step("3", "On your turn, think first, then tap Reveal. After each move you'll see Best / Good / Mistake.")
                Step("4", "Tap “Finish & review” for your strengths and weaknesses for the whole match.")
            }

            AmoledCard(Modifier.padding(top = 12.dp)) {
                Text("Fair play", color = Amoled.Warn, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    "Use the live overlay against bots, in unrated games or on the analysis board. Engine help in rated games against people breaks Chess.com's rules and can get your account closed.",
                    color = Amoled.Muted, fontSize = 13.sp,
                )
            }

            SectionLabel("This week")
            AmoledCard(onClick = { onTab(Route.Train) }) {
                Text(if (content.week.isNotEmpty()) "Week ${content.week}" else "Weekly training", color = Amoled.Text, fontWeight = FontWeight.SemiBold)
                Text(
                    "${content.puzzles.size} fresh puzzles" + (if (content.theme.isNotEmpty()) " · focus: ${content.theme}" else "") +
                        (if (content.focus.isNotEmpty()) " · ${content.focus.size} openings to study" else ""),
                    color = Amoled.Muted, fontSize = 13.sp,
                )
            }

            games.firstOrNull()?.let { g ->
                SectionLabel("Last game")
                AmoledCard(onClick = { open(Route.Report(g.id)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${g.outcome} vs ${g.opponent}", color = Amoled.Text, fontWeight = FontWeight.SemiBold)
                            Text(g.report?.opening ?: g.source, color = Amoled.Muted, fontSize = 13.sp)
                        }
                        g.report?.let { Stat("${it.accuracy.toInt()}%", "accuracy", color = accuracyColor(it.accuracy)) }
                            ?: Text("Review →", color = Amoled.Accent)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Step(n: String, text: String) {
    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(Amoled.Raised), contentAlignment = Alignment.Center) {
            Text(n, color = Amoled.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(text, color = Amoled.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}
