package com.tasneem.chesscoach.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Recent games from Chess.com's public API (no login needed). */
object ChessComApi {

    data class RemoteGame(
        val url: String,
        val pgn: String,
        val white: String,
        val black: String,
        val userIsWhite: Boolean,
        val result: String,
        val endTime: Long,
        val timeClass: String,
    )

    /** Games from the newest [months] monthly archives, newest first. */
    suspend fun recentGames(username: String, months: Int = 2): List<RemoteGame> = withContext(Dispatchers.IO) {
        val user = username.trim().lowercase()
        require(user.isNotEmpty()) { "Enter your Chess.com username in Settings" }
        val archives = try {
            JSONObject(Net.get("https://api.chess.com/pub/player/$user/games/archives")).getJSONArray("archives")
        } catch (e: HttpException) {
            if (e.code == 404) throw IllegalArgumentException("Chess.com user \"$username\" not found") else throw e
        }
        val urls = (0 until archives.length()).map { archives.getString(it) }.takeLast(months).reversed()
        urls.flatMap { u ->
            val games = JSONObject(Net.get(u)).getJSONArray("games")
            (0 until games.length()).mapNotNull { i ->
                val g = games.getJSONObject(i)
                if (g.optString("rules", "chess") != "chess" || !g.has("pgn")) return@mapNotNull null
                val w = g.getJSONObject("white"); val b = g.getJSONObject("black")
                val wName = w.optString("username"); val bName = b.optString("username")
                val result = when {
                    w.optString("result") == "win" -> "1-0"
                    b.optString("result") == "win" -> "0-1"
                    else -> "1/2-1/2"
                }
                RemoteGame(
                    url = g.optString("url"), pgn = g.getString("pgn"), white = wName, black = bName,
                    userIsWhite = wName.equals(user, ignoreCase = true), result = result,
                    endTime = g.optLong("end_time") * 1000, timeClass = g.optString("time_class"),
                )
            }
        }.sortedByDescending { it.endTime }
    }
}
