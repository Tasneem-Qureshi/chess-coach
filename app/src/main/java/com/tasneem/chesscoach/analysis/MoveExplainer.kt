package com.tasneem.chesscoach.analysis

import com.tasneem.chesscoach.chess.KING
import com.tasneem.chesscoach.chess.KNIGHT
import com.tasneem.chesscoach.chess.BISHOP
import com.tasneem.chesscoach.chess.Move
import com.tasneem.chesscoach.chess.PAWN
import com.tasneem.chesscoach.chess.Position
import com.tasneem.chesscoach.chess.rankOf
import com.tasneem.chesscoach.chess.sqName
import com.tasneem.chesscoach.engine.Line
import kotlin.math.abs

/**
 * Turns an engine line into a short human reason ("Wins a knight", "Stops the threat Nxf7").
 * Rule based on purpose: it describes what the line does, so you can check the idea yourself.
 */
object MoveExplainer {

    fun pvSan(p: Position, pv: List<Move>, max: Int = 6): String {
        var pos = p
        val parts = ArrayList<String>()
        for ((i, m) in pv.take(max).withIndex()) {
            if (!pos.isLegal(m)) break
            val num = if (pos.whiteToMove) "${pos.fullmove}. " else if (i == 0) "${pos.fullmove}… " else ""
            parts += num + pos.san(m)
            pos = pos.play(m)
        }
        return parts.joinToString(" ")
    }

    /**
     * Material change for the side to move in [p] after following [pv] for up to [plies].
     * Counted only after the opponent's reply, so a capture that gets recaptured does not count as a win.
     */
    fun materialSwing(p: Position, pv: List<Move>, plies: Int = 6): Int {
        val sign = if (p.whiteToMove) 1 else -1
        val before = p.materialBalance() * sign
        var last = before
        var pos = p
        val n = pv.size.coerceAtMost(plies)
        for (i in 0 until n) {
            val m = pv[i]
            if (!pos.isLegal(m)) break
            pos = pos.play(m)
            if (i % 2 == 1 || n == 1) last = pos.materialBalance() * sign
        }
        return last - before
    }

    fun materialName(n: Int) = when {
        n >= 9 -> "the queen"
        n >= 5 -> "a rook"
        n >= 3 -> "a piece"
        n == 2 -> "two pawns"
        else -> "a pawn"
    }

    /**
     * One-line reason for the engine's choice in [p].
     * [threat] is what the opponent would play if it were their move (null-move search), when known.
     */
    fun explain(p: Position, line: Line, second: Line? = null, threat: Move? = null): String {
        val m = line.pv.firstOrNull() ?: return ""
        if (!p.isLegal(m)) return ""
        val after = p.play(m)
        val reasons = ArrayList<String>()
        val mate = line.score.mate
        when {
            after.isCheckmate() -> return "Checkmate!"
            mate != null && mate > 0 -> reasons += "Forces checkmate in $mate"
            mate != null && mate < 0 -> reasons += "Best defence — you are getting mated, make it as hard as possible"
        }
        val piece = abs(p.pieceAt(m.from))
        val gain = materialSwing(p, line.pv)
        if (mate == null && gain >= 1) reasons += "Wins ${materialName(gain)}"
        if (p.isCapture(m) && gain >= 0 && reasons.none { it.startsWith("Wins") })
            reasons += "Takes the ${Position.PIECE_NAMES[p.captured(m)]} on ${sqName(m.to)}"
        if (threat != null && threatStopped(p, m, threat)) reasons += "Stops the threat ${threatSan(p, threat)}"
        if (m.promo != 0) reasons += "Promotes to a ${Position.PIECE_NAMES[m.promo]}"
        if (after.inCheck() && reasons.isEmpty()) reasons += "Check — forces the king to react"
        if (piece == KING && abs(m.to - m.from) == 2) reasons += "Castles: king to safety, rooks connected"
        if (reasons.isEmpty() && p.fullmove <= 12 && (piece == KNIGHT || piece == BISHOP) && isHomeRank(p, m.from))
            reasons += "Develops a piece toward the centre"
        if (reasons.isEmpty() && piece == PAWN && sqName(m.to) in CENTRE) reasons += "Claims the centre"
        if (reasons.isEmpty()) {
            val margin = second?.let { line.score.value - it.score.value }
            reasons += if (margin != null && margin >= 80) "Only good move — alternatives are ${"%.1f".format(margin / 100.0)} worse"
            else "Improves the position"
        }
        return reasons.take(2).joinToString(" · ")
    }

    private fun isHomeRank(p: Position, from: Int) = rankOf(from) == if (p.whiteToMove) 0 else 7

    private fun threatSan(p: Position, threat: Move): String {
        val flipped = Position(p.board, !p.whiteToMove, p.castling, -1, 0, p.fullmove)
        return if (flipped.isLegal(threat)) flipped.san(threat) else threat.uci()
    }

    /** The threat counts as stopped if, after our move, the threatening move is gone or no longer wins material. */
    private fun threatStopped(p: Position, m: Move, threat: Move): Boolean {
        val after = p.play(m)
        if (!after.isLegal(threat)) return true
        val flipped = Position(p.board, !p.whiteToMove, p.castling, -1, 0, p.fullmove)
        val before = flipped.isLegal(threat) && flipped.captured(threat) >= KNIGHT
        return before && after.captured(threat) < flipped.captured(threat)
    }

    private val CENTRE = setOf("d4", "e4", "d5", "e5")
}
