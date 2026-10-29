package com.tasneem.chesscoach.ui

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tasneem.chesscoach.BuildConfig
import com.tasneem.chesscoach.ChessCoachApp
import com.tasneem.chesscoach.ui.theme.Amoled
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = ChessCoachApp.get(context)
    val s by app.settings.flow.collectAsState()
    val content by app.content.content.collectAsState()
    var user by remember { mutableStateOf(s.chessComUser) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Settings", onBack)
        Column(Modifier.padding(horizontal = 16.dp)) {
            SectionLabel("Live coach")
            AmoledCard {
                Toggle("Think first (tap to reveal)", "Hide the best move until you tap Reveal.", s.tapToReveal) { v -> app.settings.update { it.copy(tapToReveal = v) } }
                Toggle("Show opponent's likely reply", "Arrow for what the engine expects them to play.", s.showOpponentIdea) { v -> app.settings.update { it.copy(showOpponentIdea = v) } }
                LabeledSlider("Candidate moves", "${s.lines}", s.lines.toFloat(), 1f..3f, 1) { v -> app.settings.update { it.copy(lines = v.toInt()) } }
                LabeledSlider("Engine think time", "${"%.1f".format(s.thinkMs / 1000f)} s", s.thinkMs.toFloat(), 500f..5000f, 8) { v -> app.settings.update { it.copy(thinkMs = (v / 250).toInt() * 250) } }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Re-learn pieces", Modifier.weight(1f)) {
                        File(context.filesDir, "piece-templates.bin").delete()
                        note = "Pieces will be learned again from the next starting position."
                    }
                    GhostButton("Reset board spot", Modifier.weight(1f)) {
                        app.settings.saveBoardRect(null, 1)
                        note = "The board will be searched again next time."
                    }
                }
            }

            SectionLabel("Game review")
            AmoledCard {
                LabeledSlider("Time per position", "${"%.1f".format(s.reviewMs / 1000f)} s", s.reviewMs.toFloat(), 200f..2000f, 8) { v -> app.settings.update { it.copy(reviewMs = (v / 100).toInt() * 100) } }
                Text("Longer is more accurate; a 40-move game at 0.4 s takes about 30 s.", color = Amoled.Muted, fontSize = 12.sp)
            }

            SectionLabel("Chess.com")
            AmoledCard {
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
                Spacer(Modifier.height(8.dp))
                GhostButton("Save") { app.settings.update { it.copy(chessComUser = user) }; note = "Saved." }
            }

            SectionLabel("Updates")
            AmoledCard {
                Text("App ${BuildConfig.VERSION_NAME} · engine Stockfish ${BuildConfig.ENGINE_VERSION.removePrefix("sf_")}", color = Amoled.Text)
                Text("Content week ${content.week.ifEmpty { "—" }}" + if (content.updated.isNotEmpty()) " (updated ${content.updated})" else "", color = Amoled.Muted, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                GhostButton("Check for updates") {
                    note = "Checking…"
                    scope.launch {
                        val c = app.content.refresh()
                        val u = app.updater.check()
                        note = listOfNotNull(
                            if (c) "New weekly content downloaded." else "Weekly content is up to date.",
                            u?.let { "App ${it.versionName} is available — see the Coach tab." } ?: "App is up to date.",
                        ).joinToString(" ")
                    }
                }
            }
            note?.let { Text(it, color = Amoled.Accent, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Toggle(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Amoled.Text, fontWeight = FontWeight.Medium)
            Text(sub, color = Amoled.Muted, fontSize = 12.sp)
        }
        Switch(
            value, onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Amoled.Black, checkedTrackColor = Amoled.Accent, uncheckedTrackColor = Amoled.Raised, uncheckedBorderColor = Amoled.Border, uncheckedThumbColor = Amoled.Muted),
        )
    }
}

@Composable
private fun LabeledSlider(title: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row {
            Text(title, color = Amoled.Text, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(value, color = Amoled.Accent)
        }
        Slider(
            v, onChange, valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = Amoled.Accent, activeTrackColor = Amoled.Accent, inactiveTrackColor = Amoled.Raised),
        )
    }
}
