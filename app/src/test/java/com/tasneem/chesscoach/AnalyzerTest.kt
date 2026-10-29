package com.tasneem.chesscoach

import com.tasneem.chesscoach.analysis.GameAnalyzer
import com.tasneem.chesscoach.analysis.MoveClass
import com.tasneem.chesscoach.analysis.MoveExplainer
import com.tasneem.chesscoach.analysis.ReportJson
import com.tasneem.chesscoach.analysis.Tag
import com.tasneem.chesscoach.chess.Pgn
import com.tasneem.chesscoach.chess.Position
import com.tasneem.chesscoach.engine.Analysis
import com.tasneem.chesscoach.engine.Line
import com.tasneem.chesscoach.engine.Score
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tiny stand-in for Stockfish: 1-ply material search (with mate detection), so the analyzer can be
 * tested on the JVM without the Android engine binary.
 */
private fun fakeEngine(p: Position): Analysis {
    val sign = if (p.whiteToMove) 1 else -1
    val scored = p.legalMoves().map { m ->
        val n = p.play(m)
        val s = when {
            n.isCheckmate() -> Score(mate = 1)
            else -> {
                // assume the opponent then takes the best capture available
                val replies = n.legalMoves()
                val worst = replies.maxOfOrNull { r -> n.play(r).materialBalance() * -sign } ?: (n.materialBalance() * -sign)
                Score(cp = -worst * 100)
            }
        }
        Line(s, listOf(m), 2)
    }.sortedByDescending { it.score.value }
    return Analysis(scored.take(2), scored.firstOrNull()?.pv?.firstOrNull())
}

class AnalyzerTest {

    @Test fun scholarsMateReport() = runBlocking {
        val g = Pgn.parse("[Result \"1-0\"]\n1. e4 e5 2. Bc4 Nc6 3. Qh5 Nf6 4. Qxf7# 1-0")
        val black = GameAnalyzer.analyze(g, userIsWhite = false, evaluate = ::fakeEngine)
        val nf6 = black.plies[5]
        assertEquals("Nf6", nf6.san)
        assertEquals(MoveClass.BLUNDER, nf6.cls)
        assertTrue(Tag.ALLOWED_MATE in nf6.tags)
        assertTrue(black.weaknesses.any { it.title.startsWith("Allowed a mating attack") })
        assertTrue(black.keyMoments.any { it.ply == 5 })

        val white = GameAnalyzer.analyze(g, userIsWhite = true, evaluate = ::fakeEngine)
        assertEquals(MoveClass.BEST, white.plies[6].cls)
        assertTrue(white.plies[6].winAfter > 97)
        assertTrue(white.accuracy > black.accuracy)

        // stored and restored without loss
        val back = ReportJson.read(ReportJson.write(black))
        assertEquals(black.plies.map { it.cls }, back.plies.map { it.cls })
        assertEquals(black.weaknesses, back.weaknesses)
        assertEquals(black.keyMoments.map { it.ply }, back.keyMoments.map { it.ply })
    }

    @Test fun hangingPieceIsTagged() = runBlocking {
        // 3...Qh4?? walks the queen into Nxh4
        val g = Pgn.parse("1. e4 e5 2. Nf3 d6 3. d3 Qh4 4. Nxh4 *")
        val r = GameAnalyzer.analyze(g, userIsWhite = false, evaluate = ::fakeEngine)
        val qh4 = r.plies[5]
        assertEquals("Qh4", qh4.san)
        assertTrue(qh4.cls >= MoveClass.MISTAKE)
        assertTrue(Tag.HUNG_PIECE in qh4.tags)
    }

    @Test fun explainerNamesMaterialAndMate() {
        val p = Position.fromFen("6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1")
        val m = p.parseSan("Rd8")!!
        assertEquals("Checkmate!", MoveExplainer.explain(p, Line(Score(mate = 1), listOf(m), 10)))
        val q = Position.fromFen("4k3/8/8/3q4/8/8/8/3RK3 w - - 0 1")
        val take = q.parseSan("Rxd5")!!
        assertTrue(MoveExplainer.explain(q, Line(Score(cp = 900), listOf(take), 10)).startsWith("Wins the queen"))
    }

    @Test fun winPercentAndAccuracy() {
        assertEquals(50.0, GameAnalyzer.winPercent(0), 0.001)
        assertTrue(GameAnalyzer.winPercent(300) > 75)
        assertEquals(100.0, GameAnalyzer.moveAccuracy(0.0), 0.5)
        assertTrue(GameAnalyzer.moveAccuracy(30.0) < 30)
    }
}
