package com.tasneem.chesscoach

import com.tasneem.chesscoach.chess.Move
import com.tasneem.chesscoach.chess.Position
import com.tasneem.chesscoach.vision.BoardLocator
import com.tasneem.chesscoach.vision.BoardReader
import com.tasneem.chesscoach.vision.Frame
import com.tasneem.chesscoach.vision.GameTracker
import com.tasneem.chesscoach.vision.Orientation
import com.tasneem.chesscoach.vision.OverlayInk
import com.tasneem.chesscoach.vision.PieceTemplates
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** Renders a phone-screenshot-like frame (Chess.com green theme) so the board reader can be tested on the JVM. */
private object Render {
    const val W = 540; const val H = 1170; const val TOP = 310; const val CELL = 67
    data class Theme(val light: Color, val dark: Color, val cell: Int = CELL, val left: Int = 0, val top: Int = TOP)
    val GREEN = Theme(Color(0xEE, 0xEE, 0xD2), Color(0x76, 0x96, 0x56))
    /** Lichess-like brown board, smaller and not touching the screen edges */
    val BROWN = Theme(Color(0xF0, 0xD9, 0xB5), Color(0xB5, 0x88, 0x63), cell = 58, left = 38, top = 402)
    private val HL_LIGHT = Color(0xF6, 0xF6, 0x69)
    private val HL_DARK = Color(0xBA, 0xCA, 0x2B)
    private val font: Font by lazy {
        val probe = "♚♛♜♝♞♟"
        val fam = listOf("Apple Symbols", "DejaVu Sans", "Segoe UI Symbol", "Noto Sans Symbols2").firstOrNull { f ->
            Font(f, Font.PLAIN, 40).canDisplayUpTo(probe) == -1
        } ?: GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.first { Font(it, Font.PLAIN, 40).canDisplayUpTo(probe) == -1 }
        Font(fam, Font.PLAIN, 54)
    }
    private fun fontFor(cell: Int) = font.deriveFont(cell * 0.8f)
    fun frame(p: Position, whiteBottom: Boolean = true, last: Move? = null, arrow: Move? = null, theme: Theme = GREEN): Frame {
        val LIGHT = theme.light; val DARK = theme.dark; val CELL = theme.cell; val TOP = theme.top; val L = theme.left
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0x31, 0x2E, 0x2B); g.fillRect(0, 0, W, H)
        // app chrome above and below the board
        g.color = Color(0x45, 0x42, 0x3F); g.fillRect(16, 200, 300, 70); g.fillRect(16, TOP + 8 * CELL + 40, 300, 70)
        fun cellOf(s: Int): Pair<Int, Int> = Orientation(whiteBottom).cell(s)
        for (s in 0 until 64) {
            val (row, col) = cellOf(s)
            val light = ((s and 7) + (s shr 3)) % 2 == 1
            val hl = last != null && (s == last.from || s == last.to)
            g.color = if (hl) (if (light) HL_LIGHT else HL_DARK) else if (light) LIGHT else DARK
            g.fillRect(L + col * CELL, TOP + row * CELL, CELL, CELL)
        }
        // coordinate labels like Chess.com: ranks top-left of the left column, files bottom-right of the bottom row
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
        for (i in 0 until 8) {
            g.color = if (i % 2 == 0) DARK else LIGHT
            g.drawString(if (whiteBottom) "${8 - i}" else "${i + 1}", L + 3, TOP + i * CELL + 13)
            g.color = if (i % 2 == 0) LIGHT else DARK
            g.drawString(if (whiteBottom) "${'a' + i}" else "${'h' - i}", L + i * CELL + CELL - 10, TOP + 8 * CELL - 4)
        }
        for (s in 0 until 64) {
            val piece = p.pieceAt(s)
            if (piece == 0) continue
            val (row, col) = cellOf(s)
            val glyph = " ♟♞♝♜♛♚"[abs(piece)].toString()
            val layout = TextLayout(glyph, fontFor(CELL), g.fontRenderContext)
            val b = layout.bounds
            val tx = L + col * CELL + (CELL - b.width) / 2 - b.x
            val ty = TOP + row * CELL + (CELL - b.height) / 2 - b.y
            val shape = layout.getOutline(AffineTransform.getTranslateInstance(tx, ty))
            g.color = if (piece > 0) Color.WHITE else Color(0x22, 0x22, 0x22)
            g.fill(shape)
            g.stroke = BasicStroke(2.2f)
            g.color = if (piece > 0) Color(0x22, 0x22, 0x22) else Color(0x55, 0x55, 0x55)
            g.draw(shape)
        }
        if (arrow != null) {
            val (r1, c1) = cellOf(arrow.from); val (r2, c2) = cellOf(arrow.to)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            g.color = Color(OverlayInk.BEST, true); g.stroke = BasicStroke(CELL * 0.16f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.drawLine(L + c1 * CELL + CELL / 2, TOP + r1 * CELL + CELL / 2, L + c2 * CELL + CELL / 2, TOP + r2 * CELL + CELL / 2)
        }
        g.dispose()
        return Frame(img.getRGB(0, 0, W, H, null, 0, W), W, H)
    }
}

class VisionTest {

    private fun play(vararg sans: String): Pair<Position, Move?> {
        var p = Position.start(); var last: Move? = null
        for (s in sans) { val m = p.parseSan(s)!!; p = p.play(m); last = m }
        return p to last
    }

    @Test fun findsBoardLearnsPiecesAndReadsPositions() {
        val start = Render.frame(Position.start())
        val rect = BoardLocator.detect(start)!!
        assertEquals(0f, rect.x, 3f)
        assertEquals(Render.TOP.toFloat(), rect.y, 3f)
        assertEquals((Render.CELL * 8).toFloat(), rect.size, 4f)

        val samples = BoardReader.sampleAll(start, rect)
        assertTrue(BoardReader.looksLikeStart(samples))
        val o = BoardReader.orientationFromStart(samples)
        assertTrue(o.whiteAtBottom)
        val t = BoardReader.learn(samples, o)
        assertArrayEquals(Position.start().board, BoardReader.read(samples, t, o))

        // a middlegame with captures, castling and a highlighted last move
        val (p, last) = play("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Bxc6", "dxc6", "O-O", "Bg4", "h3", "Bxf3", "Qxf3", "Qd6")
        val f = Render.frame(p, last = last)
        val read = BoardReader.read(BoardReader.sampleAll(f, rect), t, o)
        assertArrayEquals(p.board, read)
        assertFalse(BoardReader.looksLikeStart(BoardReader.sampleAll(f, rect)))

        // our own cyan arrow drawn across pieces must not change the reading
        val withArrow = Render.frame(p, last = last, arrow = Move(5 * 8 + 5, 1 * 8 + 3))   // f6 → d2 diagonal-ish
        assertArrayEquals(p.board, BoardReader.read(BoardReader.sampleAll(withArrow, rect), t, o))

        // templates survive a save / load
        val bytes = ByteArrayOutputStream().also { t.write(it) }.toByteArray()
        val t2 = PieceTemplates.read(ByteArrayInputStream(bytes))!!
        assertArrayEquals(p.board, BoardReader.read(BoardReader.sampleAll(f, rect), t2, o))
    }

    @Test fun otherThemeSizeAndPlacement() {
        val th = Render.BROWN
        val start = Render.frame(Position.start(), theme = th)
        val rect = BoardLocator.detect(start)!!
        assertEquals(th.left.toFloat(), rect.x, 3f)
        assertEquals(th.top.toFloat(), rect.y, 3f)
        assertEquals((th.cell * 8).toFloat(), rect.size, 4f)
        val s = BoardReader.sampleAll(start, rect)
        val o = BoardReader.orientationFromStart(s)
        val t = BoardReader.learn(s, o)
        val (p, last) = play("e4", "e6", "d4", "d5", "Nc3", "Bb4", "e5", "c5", "a3", "Bxc3+", "bxc3", "Ne7")
        assertArrayEquals(p.board, BoardReader.read(BoardReader.sampleAll(Render.frame(p, last = last, theme = th), rect), t, o))
    }

    @Test fun blackAtBottomIsDetected() {
        val start = Render.frame(Position.start(), whiteBottom = false)
        val rect = BoardLocator.detect(start)!!
        val s = BoardReader.sampleAll(start, rect)
        val o = BoardReader.orientationFromStart(s)
        assertFalse(o.whiteAtBottom)
        val t = BoardReader.learn(s, o)
        val (p, last) = play("d4", "Nf6", "c4", "e6", "Nc3", "Bb4")
        assertArrayEquals(p.board, BoardReader.read(BoardReader.sampleAll(Render.frame(p, false, last), rect), t, o))
    }

    @Test fun trackerFollowsMovesFromFrames() {
        val start = Render.frame(Position.start())
        val rect = BoardLocator.detect(start)!!
        val s0 = BoardReader.sampleAll(start, rect)
        val o = BoardReader.orientationFromStart(s0)
        val t = BoardReader.learn(s0, o)
        val tracker = GameTracker().apply { userIsWhite = true }
        var now = 0L
        fun feed(f: Frame): List<GameTracker.Event> {
            val raw = BoardReader.read(BoardReader.sampleAll(f, rect), t, o)
            now += 250
            return tracker.onBoard(raw, now) + run { now += 250; tracker.onBoard(raw, now) }
        }
        assertTrue(feed(start).single() is GameTracker.Event.NewGame)
        val sans = listOf("e4", "c5", "Nf3", "d6", "d4", "cxd4", "Nxd4", "Nf6", "Nc3", "a6")
        var p = Position.start()
        for ((i, san) in sans.withIndex()) {
            val m = p.parseSan(san)!!
            p = p.play(m)
            // skip one frame now and then: two plies between readings must still be followed
            if (i == 6) continue
            val ev = feed(Render.frame(p, last = m))
            assertTrue("after $san: $ev", ev.isNotEmpty() && ev.all { it is GameTracker.Event.Moved })
        }
        assertEquals(sans, tracker.game().sans)
        assertEquals(p.placement(), tracker.position!!.placement())
        assertEquals(p.whiteToMove, tracker.position!!.whiteToMove)

        // a new game hands back the finished one so it can be saved
        val ng = feed(start).single() as GameTracker.Event.NewGame
        assertEquals(sans, ng.previous!!.sans)
        assertEquals(0, tracker.plies)
    }
}
