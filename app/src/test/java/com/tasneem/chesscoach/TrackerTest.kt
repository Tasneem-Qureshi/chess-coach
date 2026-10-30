package com.tasneem.chesscoach

import com.tasneem.chesscoach.chess.Position
import com.tasneem.chesscoach.chess.parseSq
import com.tasneem.chesscoach.vision.GameTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Turn tracking from board readings, including the cases that used to make the coach lose track. */
class TrackerTest {

    private var now = 0L

    /** Feeds a reading twice (it only counts once stable) and returns the events. */
    private fun GameTracker.see(board: IntArray): List<GameTracker.Event> {
        now += 250; val a = onBoard(board, now)
        now += 250; return a + onBoard(board, now)
    }

    private fun after(vararg sans: String): Position {
        var p = Position.start()
        for (s in sans) p = p.play(p.parseSan(s)!!)
        return p
    }

    @Test fun joinsMidGameOnOpponentsTurnAndCorrectsItself() {
        val t = GameTracker().apply { userIsWhite = true }
        // coach opens after 1.e4: it's black's turn, but the tracker can only guess "your (white) turn"
        val ev = t.see(after("e4").board)
        assertTrue(ev.single() is GameTracker.Event.Resynced)
        assertTrue(t.sideGuessed)
        assertTrue(t.position!!.whiteToMove)

        // black replies: previously this never matched a white move and the coach froze
        val reply = t.see(after("e4", "e5").board)
        val moved = reply.single() as GameTracker.Event.Moved
        assertEquals("e5", moved.san)
        assertFalse(moved.byWhite)
        assertFalse(t.sideGuessed)
        assertTrue(t.position!!.whiteToMove)

        val next = t.see(after("e4", "e5", "Nf3").board).single() as GameTracker.Event.Moved
        assertEquals("Nf3", next.san)
        assertEquals(listOf("e5", "Nf3"), t.game().sans)
    }

    @Test fun oneMisreadSquareDoesNotStopTracking() {
        val t = GameTracker().apply { userIsWhite = true }
        assertTrue(t.see(Position.start().board).single() is GameTracker.Event.NewGame)
        val noisy = { p: Position -> p.board.copyOf().also { it[parseSq("h5")] = 1 } }   // phantom white pawn on h5, every frame
        val sans = listOf("e4", "e5", "Nf3", "Nc6", "Bc4", "Bc5", "O-O", "Nf6")
        var p = Position.start()
        for (s in sans) {
            p = p.play(p.parseSan(s)!!)
            val ev = t.see(noisy(p))
            assertEquals("after $s", s, (ev.single() as GameTracker.Event.Moved).san)
        }
        assertEquals(sans, t.game().sans)
    }

    @Test fun noiseAloneNeverResyncs() {
        val t = GameTracker().apply { userIsWhite = true }
        t.see(Position.start().board)
        val p = after("d4", "d5")
        t.see(after("d4").board); t.see(p.board)
        val noisy = p.board.copyOf().also { it[parseSq("a6")] = -1 }
        repeat(40) { assertTrue(t.see(noisy).isEmpty()) }       // 20 seconds of a misread square
        assertEquals(2, t.plies)
    }

    @Test fun fastReplyBetweenFramesIsFollowed() {
        val t = GameTracker().apply { userIsWhite = false }
        t.see(Position.start().board)
        t.see(after("c4").board)
        val ev = t.see(after("c4", "e5", "Nc3").board)
        assertEquals(listOf("e5", "Nc3"), ev.map { (it as GameTracker.Event.Moved).san })
    }
}
