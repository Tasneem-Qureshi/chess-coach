package com.tasneem.chesscoach.overlay

import com.tasneem.chesscoach.analysis.MoveClass
import com.tasneem.chesscoach.chess.Move

data class SuggestedLine(val move: Move, val san: String, val score: String, val line: String)

data class Suggestion(
    val lines: List<SuggestedLine>,
    val why: String,
    /** What the opponent threatens right now, if anything worth knowing. */
    val threat: String?,
    val depth: Int,
)

data class Feedback(val san: String, val cls: MoveClass, val bestSan: String)

enum class Stage { STARTING, FINDING_BOARD, LEARNING_PIECES, TRACKING, GAME_OVER }

data class CoachUi(
    val stage: Stage = Stage.STARTING,
    val message: String = "Starting…",
    val yourTurn: Boolean = false,
    val userIsWhite: Boolean = true,
    /** evaluation from white's point of view, as text and centipawns (for the bar) */
    val evalText: String = "",
    val evalCp: Int = 0,
    val thinking: Boolean = false,
    val suggestion: Suggestion? = null,
    val opponentIdea: String? = null,
    val revealed: Boolean = false,
    val hint: Boolean = false,
    val feedback: Feedback? = null,
    val plies: Int = 0,
    val collapsed: Boolean = false,
    val adjusting: Boolean = false,
    val result: String? = null,
    /** Short status shown while tracking (e.g. whose turn is still a guess, or an engine hiccup). */
    val note: String? = null,
)

/** Actions from the panel back to the service. */
interface CoachActions {
    fun reveal()
    fun hint()
    fun toggleCollapsed()
    fun flipBoard()
    fun switchSideToMove()
    fun redetect()
    fun toggleAdjust()
    fun nudge(dx: Int, dy: Int, ds: Int)
    fun finishAndReview()
    fun close()
}
