package com.tasneem.chesscoach.vision

import com.tasneem.chesscoach.chess.Game
import com.tasneem.chesscoach.chess.KING
import com.tasneem.chesscoach.chess.Move
import com.tasneem.chesscoach.chess.Position

/**
 * Follows a game from successive board readings. A reading only counts once it is stable (two equal
 * frames in a row), and is turned into moves by finding the legal move(s) that produce it. Readings that
 * no legal sequence explains are ignored unless they stay on screen long enough to resync.
 */
class GameTracker(private val resyncAfterMs: Long = 2_500) {

    sealed interface Event {
        /** [previous] is the game that was being tracked before the reset, if it had any moves. */
        data class NewGame(val userIsWhite: Boolean, val previous: Game?) : Event
        data class Moved(val move: Move, val san: String, val byWhite: Boolean, val before: Position) : Event
        data class Resynced(val position: Position, val previous: Game?) : Event
    }

    var userIsWhite = true
    var position: Position? = null
        private set
    private var startFen = Position.START_FEN
    private val moves = ArrayList<Move>()
    private var lastRaw: IntArray? = null
    private var stable = 0
    private var mismatchSince = 0L
    private var mismatchRaw: IntArray? = null

    val plies: Int get() = moves.size

    fun game(headers: Map<String, String> = emptyMap()): Game = Game(headers, startFen, moves.toList())

    fun reset() {
        position = null; moves.clear(); startFen = Position.START_FEN; lastRaw = null; stable = 0; mismatchRaw = null
    }

    /** Starts tracking from a known position (e.g. the user chose who is to move after a resync). */
    fun setPosition(p: Position) {
        position = p; startFen = p.toFen(); moves.clear(); mismatchRaw = null
    }

    fun onBoard(raw: IntArray, now: Long): List<Event> {
        if (!plausible(raw)) return emptyList()
        if (lastRaw?.contentEquals(raw) == true) stable++ else { lastRaw = raw; stable = 0; return emptyList() }
        if (stable < 1) return emptyList()

        val pos = position
        if (matchesStart(raw) && (pos == null || moves.isNotEmpty() || pos.placement() != Position.start().placement())) {
            val prev = previousGame()
            setPosition(Position.start())
            return listOf(Event.NewGame(userIsWhite, prev))
        }
        if (pos == null) {
            if (stable != 1) return emptyList()
            val p = Position.fromBoard(fill(raw, null), whiteToMove = userIsWhite)
            setPosition(p)
            return listOf(Event.Resynced(p, null))
        }
        if (matches(pos, raw)) { mismatchRaw = null; return emptyList() }

        if (mismatchRaw?.contentEquals(raw) != true) {
            // one ply
            for (m in pos.legalMoves()) {
                val next = pos.play(m)
                if (matches(next, raw)) return listOf(record(pos, m))
            }
            // two plies (a fast reply between frames)
            for (m in pos.legalMoves()) {
                val mid = pos.play(m)
                for (m2 in mid.legalMoves()) if (matches(mid.play(m2), raw)) return listOf(record(pos, m), record(mid, m2))
            }
            // unexplained: wait, then resync if it persists
            mismatchRaw = raw; mismatchSince = now
            return emptyList()
        }
        if (now - mismatchSince < resyncAfterMs || diffCount(pos, raw) > 16) return emptyList()
        val p = Position.fromBoard(fill(raw, pos), whiteToMove = guessSideToMove(pos, raw))
        val prev = previousGame()
        setPosition(p)
        return listOf(Event.Resynced(p, prev))
    }

    private fun previousGame(): Game? = if (moves.isEmpty()) null else game()

    private fun record(before: Position, m: Move): Event.Moved {
        val san = before.san(m)
        moves += m
        position = before.play(m)
        mismatchRaw = null
        return Event.Moved(m, san, before.whiteToMove, before)
    }

    private fun plausible(raw: IntArray): Boolean {
        val wk = raw.count { it == KING }; val bk = raw.count { it == -KING }
        val unknown = raw.count { it == BoardReader.UNKNOWN }
        return wk <= 1 && bk <= 1 && (wk + bk + unknown > 0) && raw.count { it != 0 && it != BoardReader.UNKNOWN } >= 2
    }

    private fun matches(p: Position, raw: IntArray): Boolean {
        for (i in 0 until 64) if (raw[i] != BoardReader.UNKNOWN && raw[i] != p.board[i]) return false
        return true
    }

    private fun matchesStart(raw: IntArray) = matches(Position.start(), raw) && raw.count { it == BoardReader.UNKNOWN } < 8

    private fun diffCount(p: Position, raw: IntArray) = (0 until 64).count { raw[it] != BoardReader.UNKNOWN && raw[it] != p.board[it] }

    /** Unknown cells take the last known piece (or empty). */
    private fun fill(raw: IntArray, prev: Position?) = IntArray(64) { if (raw[it] == BoardReader.UNKNOWN) prev?.board?.get(it) ?: 0 else raw[it] }

    /** The side whose pieces arrived on new squares most recently moved, so the other side is to move. */
    private fun guessSideToMove(pos: Position, raw: IntArray): Boolean {
        var white = 0; var black = 0
        for (i in 0 until 64) {
            val v = raw[i]
            if (v == BoardReader.UNKNOWN || v == pos.board[i] || v == 0) continue
            if (v > 0) white++ else black++
        }
        return when {
            white > black -> false
            black > white -> true
            else -> userIsWhite
        }
    }
}
