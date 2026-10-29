package com.tasneem.chesscoach.analysis

import com.tasneem.chesscoach.chess.Game
import com.tasneem.chesscoach.chess.KNIGHT
import com.tasneem.chesscoach.chess.Move
import com.tasneem.chesscoach.chess.Position
import com.tasneem.chesscoach.engine.Analysis
import com.tasneem.chesscoach.engine.Line
import com.tasneem.chesscoach.engine.Score
import kotlin.math.exp
import kotlin.math.max

enum class MoveClass(val label: String, val symbol: String) {
    BEST("Best", "★"), EXCELLENT("Excellent", "!"), GOOD("Good", "✓"),
    INACCURACY("Inaccuracy", "?!"), MISTAKE("Mistake", "?"), BLUNDER("Blunder", "??");

    val isError get() = this >= INACCURACY
}

enum class Phase(val label: String) { OPENING("Opening"), MIDDLEGAME("Middlegame"), ENDGAME("Endgame") }

/** Weakness tags attached to your inaccurate moves. */
enum class Tag(val label: String, val tip: String) {
    HUNG_PIECE("Left a piece hanging", "Before every move, ask: what can my opponent capture after this?"),
    MISSED_TACTIC("Missed a tactic", "Scan checks, captures and threats — for both sides — before you move."),
    MISSED_MATE("Missed a checkmate", "When the enemy king is exposed, look at every check first."),
    ALLOWED_MATE("Allowed a mating attack", "Watch your king's escape squares and the checks your opponent has."),
    MISSED_PUNISH("Didn't punish a mistake", "After your opponent moves, ask what changed and what they left loose."),
    TIME_PRESSURE("Error under time pressure", "Spend less time early so you have time for critical moments."),
    POSITIONAL("Positional slip", "Compare the engine's plan: piece activity, pawn structure and king safety."),
}

data class PlyReview(
    val ply: Int,
    val whiteMoved: Boolean,
    val san: String,
    val move: Move,
    val bestSan: String,
    val bestMove: Move?,
    /** evaluation after the move, white's point of view (centipawns, mates mapped to ±large) */
    val evalAfterWhite: Int,
    val evalAfterText: String,
    val winBefore: Double,
    val winAfter: Double,
    val accuracy: Double,
    val cls: MoveClass,
    val phase: Phase,
    val tags: List<Tag>,
    val explanation: String,
    val bestLine: String,
    val clock: Double?,
) {
    val winDrop get() = max(0.0, winBefore - winAfter)
}

data class Insight(val title: String, val detail: String)

data class GameReport(
    val userIsWhite: Boolean,
    val plies: List<PlyReview>,
    val accuracy: Double,
    val opponentAccuracy: Double,
    val phaseAccuracy: Map<Phase, Double>,
    val counts: Map<MoveClass, Int>,
    val tagCounts: Map<Tag, Int>,
    val strengths: List<Insight>,
    val weaknesses: List<Insight>,
    val keyMoments: List<PlyReview>,
    val opening: String?,
) {
    val userPlies get() = plies.filter { it.whiteMoved == userIsWhite }
}

object GameAnalyzer {

    fun winPercent(cp: Int): Double {
        val c = cp.coerceIn(-1000, 1000)
        return 50 + 50 * (2 / (1 + exp(-0.00368208 * c)) - 1)
    }

    fun moveAccuracy(drop: Double): Double = (103.1668 * exp(-0.04354 * drop) - 3.1669).coerceIn(0.0, 100.0)

    fun classify(played: Move, best: Move?, drop: Double, winBefore: Double, winAfter: Double): MoveClass = when {
        played == best -> MoveClass.BEST
        drop < 2 -> MoveClass.EXCELLENT
        winBefore > 95 && winAfter > 90 -> MoveClass.GOOD          // still completely winning
        drop < 5 -> MoveClass.GOOD
        drop < 10 -> MoveClass.INACCURACY
        drop < 18 -> MoveClass.MISTAKE
        else -> MoveClass.BLUNDER
    }

    fun phaseOf(p: Position): Phase = when {
        p.nonPawnMaterial() <= 26 -> Phase.ENDGAME     // e.g. two rooks + a minor piece each, or less
        p.fullmove <= 12 -> Phase.OPENING
        else -> Phase.MIDDLEGAME
    }

    /** Mean of the arithmetic and harmonic means: a single blunder drags the score down like it should. */
    fun gameAccuracy(moves: List<Double>): Double {
        if (moves.isEmpty()) return 0.0
        val mean = moves.average()
        val harmonic = moves.size / moves.sumOf { 1.0 / max(it, 1.0) }
        return (mean + harmonic) / 2
    }

    private fun terminal(p: Position): Analysis? = when {
        // side to move is mated: a plain large negative (mate 0 cannot be negated)
        p.isCheckmate() -> Analysis(listOf(Line(Score(cp = -Score.MATE_CP), emptyList(), 0)), null)
        p.isStalemate() -> Analysis(listOf(Line(Score(cp = 0), emptyList(), 0)), null)
        else -> null
    }

    /**
     * Reviews every ply of [game]. [evaluate] returns the engine's view of a position (side to move POV).
     * [onProgress] receives (done, total).
     */
    suspend fun analyze(
        game: Game,
        userIsWhite: Boolean,
        openingName: String? = null,
        evaluate: suspend (Position) -> Analysis,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): GameReport {
        val positions = game.positions
        val evals = ArrayList<Analysis>(positions.size)
        positions.forEachIndexed { i, p ->
            evals += terminal(p) ?: evaluate(p)
            onProgress(i + 1, positions.size)
        }
        val firstClock = game.clocks.filterNotNull().take(2).maxOrNull()
        val plies = game.moves.indices.map { i ->
            val p = positions[i]
            val m = game.moves[i]
            val before = evals[i].best?.score ?: Score()
            val after = -(evals[i + 1].best?.score ?: Score())
            val wb = winPercent(before.value)
            val wa = winPercent(after.value)
            val drop = max(0.0, wb - wa)
            val best = evals[i].bestMove ?: evals[i].best?.pv?.firstOrNull()
            val cls = classify(m, best, drop, wb, wa)
            val clock = game.clocks.getOrNull(i)
            val tags = if (cls.isError) tagsFor(p, m, before, after, evals[i], evals[i + 1], clock, firstClock, cls) else emptyList()
            val whiteAfter = if (p.whiteToMove) after.value else -after.value
            PlyReview(
                ply = i, whiteMoved = p.whiteToMove, san = game.sans[i], move = m,
                bestSan = best?.takeIf { p.isLegal(it) }?.let { p.san(it) } ?: "",
                bestMove = best,
                evalAfterWhite = whiteAfter,
                evalAfterText = (if (p.whiteToMove) after else -after).format(),
                winBefore = wb, winAfter = wa, accuracy = moveAccuracy(drop), cls = cls, phase = phaseOf(p), tags = tags,
                explanation = evals[i].best?.let { MoveExplainer.explain(p, it, evals[i].lines.getOrNull(1)) } ?: "",
                bestLine = evals[i].best?.let { MoveExplainer.pvSan(p, it.pv) } ?: "",
                clock = clock,
            )
        }
        val withPunish = markMissedPunishments(plies, userIsWhite)
        return buildReport(game, withPunish, userIsWhite, openingName)
    }

    private fun tagsFor(
        p: Position, m: Move, before: Score, after: Score, evBefore: Analysis, evAfter: Analysis,
        clock: Double?, firstClock: Double?, cls: MoveClass,
    ): List<Tag> {
        val tags = ArrayList<Tag>()
        val best = evBefore.best
        if (before.mate != null && before.mate > 0 && (after.mate == null || after.mate <= 0)) tags += Tag.MISSED_MATE
        if (after.mate != null && after.mate < 0 && (before.mate == null || before.mate > 0)) tags += Tag.ALLOWED_MATE
        val next = p.play(m)
        val reply = evAfter.bestMove ?: evAfter.best?.pv?.firstOrNull()
        if (cls >= MoveClass.MISTAKE && reply != null && next.isLegal(reply) && next.captured(reply) >= KNIGHT &&
            MoveExplainer.materialSwing(next, evAfter.best?.pv ?: listOf(reply)) >= 2
        ) tags += Tag.HUNG_PIECE
        if (best != null && Tag.MISSED_MATE !in tags) {
            val bm = best.pv.firstOrNull()
            if (bm != null && p.isLegal(bm) && (p.isCapture(bm) || p.play(bm).inCheck()) && MoveExplainer.materialSwing(p, best.pv) >= 2)
                tags += Tag.MISSED_TACTIC
        }
        if (clock != null && cls >= MoveClass.MISTAKE && (clock < 20 || (firstClock != null && clock < firstClock * 0.1)))
            tags += Tag.TIME_PRESSURE
        if (tags.isEmpty()) tags += Tag.POSITIONAL
        return tags
    }

    /** Your move right after an opponent mistake: if you didn't find a good reply, tag it. */
    private fun markMissedPunishments(plies: List<PlyReview>, userIsWhite: Boolean): List<PlyReview> =
        plies.mapIndexed { i, r ->
            val prev = plies.getOrNull(i - 1)
            if (r.whiteMoved == userIsWhite && prev != null && prev.cls >= MoveClass.MISTAKE && r.cls >= MoveClass.INACCURACY &&
                Tag.MISSED_PUNISH !in r.tags
            ) r.copy(tags = (r.tags - Tag.POSITIONAL) + Tag.MISSED_PUNISH) else r
        }

    private fun buildReport(game: Game, plies: List<PlyReview>, userIsWhite: Boolean, opening: String?): GameReport {
        val mine = plies.filter { it.whiteMoved == userIsWhite }
        val theirs = plies.filter { it.whiteMoved != userIsWhite }
        val phaseAcc = Phase.entries.associateWith { ph -> mine.filter { it.phase == ph }.map { it.accuracy } }
            .filterValues { it.size >= 3 }.mapValues { gameAccuracy(it.value) }
        val counts = MoveClass.entries.associateWith { c -> mine.count { it.cls == c } }
        val tagCounts = mine.flatMap { it.tags }.groupingBy { it }.eachCount()
        val acc = gameAccuracy(mine.map { it.accuracy })

        val strengths = ArrayList<Insight>()
        val weaknesses = ArrayList<Insight>()

        phaseAcc.maxByOrNull { it.value }?.let { (ph, a) ->
            if (a >= 75) strengths += Insight("Strong ${ph.label.lowercase()}", "${a.toInt()}% accuracy in the ${ph.label.lowercase()}.")
        }
        phaseAcc.minByOrNull { it.value }?.let { (ph, a) ->
            if (a < 70 && phaseAcc.size > 1 || a < 60) weaknesses += Insight(
                "${ph.label} needs work", "Only ${a.toInt()}% accuracy in the ${ph.label.lowercase()}. " + phaseTip(ph),
            )
        }
        val blunders = counts[MoveClass.BLUNDER] ?: 0
        if (blunders == 0 && mine.size >= 10) strengths += Insight("No blunders", "You never gave away the game with one move — solid, safe play.")
        val good = mine.count { it.cls <= MoveClass.GOOD }
        if (mine.isNotEmpty() && good * 100 / mine.size >= 80) strengths += Insight("Consistent", "$good of ${mine.size} moves were good or better.")

        var streak = 0; var bestStreak = 0
        mine.forEach { if (it.cls <= MoveClass.EXCELLENT) { streak++; bestStreak = max(bestStreak, streak) } else streak = 0 }
        if (bestStreak >= 6) strengths += Insight("Long accurate run", "$bestStreak top-engine moves in a row.")

        // Did you punish your opponent's errors?
        val chances = theirs.filter { it.cls >= MoveClass.MISTAKE }.mapNotNull { t -> mine.firstOrNull { it.ply == t.ply + 1 } }
        val punished = chances.count { it.cls <= MoveClass.GOOD }
        if (chances.size >= 2 && punished * 2 >= chances.size) strengths += Insight("Punishes mistakes", "You exploited $punished of ${chances.size} opponent errors.")

        val result = game.headers["Result"]
        val userWon = (result == "1-0" && userIsWhite) || (result == "0-1" && !userIsWhite)
        val userLost = (result == "0-1" && userIsWhite) || (result == "1-0" && !userIsWhite)
        val maxWin = mine.maxOfOrNull { it.winAfter } ?: 50.0
        val minWin = mine.minOfOrNull { it.winAfter } ?: 50.0
        if (maxWin >= 85 && userWon) strengths += Insight("Converted the advantage", "Once you were winning, you brought it home.")
        if (maxWin >= 85 && !userWon && result != null && result != "*") weaknesses += Insight(
            "Let a winning position slip", "You reached a winning position (${maxWin.toInt()}% win chance) but didn't win. When ahead: trade pieces, keep it simple.",
        )
        if (minWin <= 15 && !userLost && result != null && result != "*") strengths += Insight("Fighting spirit", "You saved a position that was nearly lost.")

        tagCounts.entries.filter { it.key != Tag.POSITIONAL }.sortedByDescending { it.value }.forEach { (t, n) ->
            weaknesses += Insight("${t.label}${if (n > 1) " ×$n" else ""}", t.tip)
        }
        if ((tagCounts[Tag.POSITIONAL] ?: 0) >= 3) weaknesses += Insight("Positional slips ×${tagCounts[Tag.POSITIONAL]}", Tag.POSITIONAL.tip)
        if (strengths.isEmpty()) strengths += Insight("Keep going", "Every analysed game teaches you something — review the key moments below.")

        val keyMoments = mine.filter { it.cls.isError }.sortedByDescending { it.winDrop }.take(3).sortedBy { it.ply }

        return GameReport(
            userIsWhite = userIsWhite, plies = plies, accuracy = acc,
            opponentAccuracy = gameAccuracy(theirs.map { it.accuracy }),
            phaseAccuracy = phaseAcc, counts = counts, tagCounts = tagCounts,
            strengths = strengths, weaknesses = weaknesses, keyMoments = keyMoments, opening = opening,
        )
    }

    private fun phaseTip(ph: Phase) = when (ph) {
        Phase.OPENING -> "Focus on development, castling early and controlling the centre."
        Phase.MIDDLEGAME -> "Look for tactics every move and make a plan for your worst piece."
        Phase.ENDGAME -> "Activate your king and push passed pawns; practise basic endgames."
    }

    /** Engine evaluation of one move, used to rate the move just played in the overlay. */
    fun quickClass(scoreBefore: Score, scoreAfterMover: Score, played: Move, best: Move?): MoveClass {
        val wb = winPercent(scoreBefore.value); val wa = winPercent(scoreAfterMover.value)
        return classify(played, best, max(0.0, wb - wa), wb, wa)
    }
}
