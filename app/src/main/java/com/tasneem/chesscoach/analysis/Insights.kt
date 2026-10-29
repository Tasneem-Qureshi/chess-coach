package com.tasneem.chesscoach.analysis

import com.tasneem.chesscoach.data.Puzzle
import com.tasneem.chesscoach.data.SavedGame

/** Trends across all reviewed games: what you do well, what keeps costing you points. */
data class OverallInsights(
    val games: Int,
    val avgAccuracy: Double,
    val recentAccuracy: List<Double>,
    val phaseAccuracy: Map<Phase, Double>,
    /** average occurrences per game */
    val tagRate: List<Pair<Tag, Double>>,
    val classRate: Map<MoveClass, Double>,
    val openings: List<OpeningStat>,
    val score: Triple<Int, Int, Int>,
)

data class OpeningStat(val name: String, val games: Int, val points: Double, val accuracy: Double)

object InsightsBuilder {

    fun build(all: List<SavedGame>): OverallInsights? {
        val reviewed = all.filter { it.report != null }.sortedBy { it.playedAt }
        if (reviewed.isEmpty()) return null
        val reports = reviewed.map { it.report!! }
        val phase = Phase.entries.associateWith { ph -> reports.mapNotNull { it.phaseAccuracy[ph] } }
            .filterValues { it.isNotEmpty() }.mapValues { it.value.average() }
        val tags = Tag.entries.map { t -> t to reports.sumOf { it.tagCounts[t] ?: 0 }.toDouble() / reports.size }
            .filter { it.second > 0 }.sortedByDescending { it.second }
        val classes = MoveClass.entries.associateWith { c -> reports.sumOf { it.counts[c] ?: 0 }.toDouble() / reports.size }
        val openings = reviewed.groupBy { it.report!!.opening?.substringBefore(':') ?: "Unknown" }.map { (name, gs) ->
            OpeningStat(name, gs.size, gs.sumOf { points(it) }, gs.map { it.report!!.accuracy }.average())
        }.sortedByDescending { it.games }
        return OverallInsights(
            games = reviewed.size,
            avgAccuracy = reports.map { it.accuracy }.average(),
            recentAccuracy = reports.takeLast(20).map { it.accuracy },
            phaseAccuracy = phase, tagRate = tags, classRate = classes, openings = openings,
            score = Triple(reviewed.count { it.outcome == "Won" }, reviewed.count { it.outcome == "Draw" }, reviewed.count { it.outcome == "Lost" }),
        )
    }

    private fun points(g: SavedGame) = when (g.outcome) { "Won" -> 1.0; "Draw" -> 0.5; else -> 0.0 }

    /** Your own mistakes as puzzles: the position before the error, find the engine's move. */
    fun mistakePuzzles(all: List<SavedGame>): List<Puzzle> = all.mapNotNull { g ->
        val r = g.report ?: return@mapNotNull null
        val game = runCatching { com.tasneem.chesscoach.chess.Pgn.parse(g.pgn) }.getOrNull() ?: return@mapNotNull null
        r.userPlies.filter { it.cls >= MoveClass.MISTAKE && it.bestMove != null }.map { p ->
            Puzzle(
                id = "${g.id}-${p.ply}", fen = game.positions[p.ply].toFen(), moves = listOf(p.bestMove!!.uci()),
                rating = 0, themes = p.tags.map { it.label }, source = "vs ${g.opponent}",
            )
        }
    }.flatten()
}
