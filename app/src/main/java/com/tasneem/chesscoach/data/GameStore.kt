package com.tasneem.chesscoach.data

import android.content.Context
import com.tasneem.chesscoach.analysis.GameReport
import com.tasneem.chesscoach.analysis.ReportJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SavedGame(
    val id: String,
    /** "overlay", "chess.com" or "pgn" */
    val source: String,
    val white: String,
    val black: String,
    val userIsWhite: Boolean,
    val result: String,
    val playedAt: Long,
    val pgn: String,
    val timeClass: String = "",
    /** Chess.com game URL, used to avoid importing the same game twice. */
    val url: String = "",
    val report: GameReport? = null,
) {
    val opponent get() = if (userIsWhite) black else white
    val outcome: String get() = when (result) {
        "1-0" -> if (userIsWhite) "Won" else "Lost"
        "0-1" -> if (userIsWhite) "Lost" else "Won"
        "1/2-1/2" -> "Draw"
        else -> "—"
    }
}

/** Games are small JSON files in app storage, one per game. */
class GameStore(context: Context) {
    private val dir = File(context.filesDir, "games").apply { mkdirs() }
    private val _games = MutableStateFlow<List<SavedGame>>(emptyList())
    val games: StateFlow<List<SavedGame>> = _games

    suspend fun load() = withContext(Dispatchers.IO) {
        _games.value = dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f -> runCatching { fromJson(JSONObject(f.readText())) }.getOrNull() }
            .sortedByDescending { it.playedAt }
    }

    fun get(id: String) = _games.value.firstOrNull { it.id == id }

    suspend fun save(g: SavedGame): SavedGame = withContext(Dispatchers.IO) {
        File(dir, "${g.id}.json").writeText(toJson(g).toString())
        _games.value = (_games.value.filterNot { it.id == g.id } + g).sortedByDescending { it.playedAt }
        g
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir, "$id.json").delete()
        _games.value = _games.value.filterNot { it.id == id }
    }

    fun hasUrl(url: String) = url.isNotEmpty() && _games.value.any { it.url == url }

    companion object {
        fun newId(): String = UUID.randomUUID().toString().take(12)

        private fun toJson(g: SavedGame) = JSONObject().apply {
            put("id", g.id); put("source", g.source); put("white", g.white); put("black", g.black)
            put("userIsWhite", g.userIsWhite); put("result", g.result); put("playedAt", g.playedAt)
            put("pgn", g.pgn); put("timeClass", g.timeClass); put("url", g.url)
            g.report?.let { put("report", ReportJson.write(it)) }
        }

        private fun fromJson(o: JSONObject) = SavedGame(
            id = o.getString("id"), source = o.optString("source"), white = o.optString("white"), black = o.optString("black"),
            userIsWhite = o.optBoolean("userIsWhite", true), result = o.optString("result", "*"), playedAt = o.optLong("playedAt"),
            pgn = o.getString("pgn"), timeClass = o.optString("timeClass"), url = o.optString("url"),
            report = o.optJSONObject("report")?.let { runCatching { ReportJson.read(it) }.getOrNull() },
        )
    }
}
