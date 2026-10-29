package com.tasneem.chesscoach

import com.tasneem.chesscoach.chess.Move
import com.tasneem.chesscoach.chess.Pgn
import com.tasneem.chesscoach.chess.Position
import com.tasneem.chesscoach.engine.UciParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChessCoreTest {

    private fun perft(p: Position, depth: Int): Long =
        if (depth == 0) 1 else p.legalMoves().sumOf { perft(p.play(it), depth - 1) }

    @Test fun perftStart() {
        val p = Position.start()
        assertEquals(20L, perft(p, 1))
        assertEquals(400L, perft(p, 2))
        assertEquals(8902L, perft(p, 3))
        assertEquals(197281L, perft(p, 4))
    }

    // "Kiwipete": castling, en passant, promotions, pins
    @Test fun perftKiwipete() {
        val p = Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
        assertEquals(48L, perft(p, 1))
        assertEquals(2039L, perft(p, 2))
        assertEquals(97862L, perft(p, 3))
    }

    @Test fun perftPosition3() {
        val p = Position.fromFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1")
        assertEquals(14L, perft(p, 1))
        assertEquals(191L, perft(p, 2))
        assertEquals(2812L, perft(p, 3))
        assertEquals(43238L, perft(p, 4))
    }

    @Test fun perftPosition4Promotions() {
        val p = Position.fromFen("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1")
        assertEquals(6L, perft(p, 1))
        assertEquals(264L, perft(p, 2))
        assertEquals(9467L, perft(p, 3))
    }

    @Test fun perftPosition5() {
        val p = Position.fromFen("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8")
        assertEquals(44L, perft(p, 1))
        assertEquals(1486L, perft(p, 2))
        assertEquals(62379L, perft(p, 3))
    }

    @Test fun fenRoundTrip() {
        val fens = listOf(
            Position.START_FEN,
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3",
        )
        for (f in fens) assertEquals(f, Position.fromFen(f).toFen())
    }

    @Test fun sanDisambiguationAndChecks() {
        val p = Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
        val sans = p.legalMoves().map { p.san(it) }.toSet()
        assertTrue("O-O" in sans && "O-O-O" in sans)
        assertTrue("Nxf7" in sans && "Qxf6" in sans && "Rb1" in sans)
        val mate = Position.fromFen("6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1")
        assertEquals("Rd8#", mate.san(mate.parseSan("Rd8")!!))
        // two knights can reach d2 → file disambiguation
        val n = Position.fromFen("4k3/8/8/8/8/8/8/1N2KN2 w - - 0 1")
        val sanN = n.legalMoves().filter { it.to == 11 }.map { n.san(it) }.toSet()
        assertEquals(setOf("Nbd2", "Nfd2", "Kd2"), sanN)
        assertNotNull(n.parseSan("Nbd2"))
    }

    @Test fun pgnParsesChessComStyle() {
        val pgn = """
            [Event "Live Chess"]
            [White "alice"]
            [Black "bob"]
            [Result "1-0"]

            1. e4 {[%clk 0:09:58.1]} 1... e5 {[%clk 0:09:57.0]} 2. Bc4 {[%clk 0:09:50]} 2... Nc6 3. Qh5 Nf6?? 4. Qxf7# 1-0
        """.trimIndent()
        val g = Pgn.parse(pgn)
        assertEquals(7, g.moves.size)
        assertEquals("Qxf7#", g.sans.last())
        assertTrue(g.positions.last().isCheckmate())
        assertEquals(598.1, g.clocks[0]!!, 0.01)
        assertEquals(590.0, g.clocks[2]!!, 0.01)
        assertEquals(null, g.clocks[3])
        // round trip
        val again = Pgn.parse(g.toPgn())
        assertEquals(g.moves, again.moves)
    }

    @Test fun pgnSkipsVariationsAndNags() {
        val g = Pgn.parse("1. d4 d5 (1... Nf6 2. c4) 2. c4 \$1 e6 {QGD} 3. Nc3 *")
        assertEquals(listOf("d4", "d5", "c4", "e6", "Nc3"), g.sans)
    }

    @Test fun enPassantAndPromotion() {
        var p = Position.fromFen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1")
        val ep = p.parseSan("exd6")!!
        p = p.play(ep)
        assertEquals(0, p.pieceAt(35))   // d5 pawn removed
        val promo = Position.fromFen("8/4P3/8/8/8/8/k7/4K3 w - - 0 1")
        val m = promo.parseSan("e8=Q")!!
        assertEquals("e7e8q", m.uci())
        assertEquals("e8=Q", promo.san(m))
    }

    @Test fun uciParser() {
        val u = UciParser()
        u.feed("info depth 18 seldepth 24 multipv 1 score cp 34 nodes 100 pv e2e4 e7e5 g1f3")
        u.feed("info depth 18 seldepth 22 multipv 2 score cp 20 nodes 100 pv d2d4 d7d5")
        u.feed("info depth 19 seldepth 22 multipv 2 score cp 25 lowerbound nodes 100 pv d2d4 d7d5")
        u.feed("info depth 19 multipv 1 score mate 3 pv d1h5")
        val a = u.result("d1h5")
        assertEquals(2, a.lines.size)
        assertEquals(3, a.lines[0].score.mate)
        assertEquals(20, a.lines[1].score.cp)
        assertEquals(Move.fromUci("d1h5"), a.bestMove)
        assertEquals("M3", a.lines[0].score.format())
        assertEquals("-M3", (-a.lines[0].score).format())
    }
}
