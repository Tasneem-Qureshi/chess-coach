package com.tasneem.chesscoach.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.tasneem.chesscoach.chess.Move
import com.tasneem.chesscoach.chess.PAWN
import com.tasneem.chesscoach.chess.Position
import com.tasneem.chesscoach.chess.QUEEN
import com.tasneem.chesscoach.chess.fileOf
import com.tasneem.chesscoach.chess.rankOf
import com.tasneem.chesscoach.chess.sq
import com.tasneem.chesscoach.ui.theme.Amoled
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class BoardArrow(val move: Move, val color: Color)

private const val GLYPHS = " ♟♞♝♜♛♚"

/**
 * Chess board drawn on a Canvas. With [onMove] set, tap a piece then a square to move it
 * (promotions become queens).
 */
@Composable
fun ChessBoard(
    position: Position,
    modifier: Modifier = Modifier,
    whiteAtBottom: Boolean = true,
    lastMove: Move? = null,
    arrows: List<BoardArrow> = emptyList(),
    marked: Map<Int, Color> = emptyMap(),
    onMove: ((Move) -> Unit)? = null,
) {
    val measurer = rememberTextMeasurer()
    var selected by remember(position) { mutableStateOf<Int?>(null) }
    val targets = remember(position, selected) {
        selected?.let { s -> position.legalMoves().filter { it.from == s }.map { it.to }.toSet() } ?: emptySet()
    }

    fun squareAt(o: Offset, size: Float): Int {
        val c = size / 8
        val col = (o.x / c).toInt().coerceIn(0, 7); val row = (o.y / c).toInt().coerceIn(0, 7)
        return if (whiteAtBottom) sq(col, 7 - row) else sq(7 - col, row)
    }

    Canvas(
        modifier.fillMaxWidth().aspectRatio(1f).pointerInput(position, onMove, whiteAtBottom) {
            if (onMove == null) return@pointerInput
            detectTapGestures { o ->
                val s = squareAt(o, size.width.toFloat())
                val sel = selected
                val piece = position.pieceAt(s)
                val mine = piece != 0 && (piece > 0) == position.whiteToMove
                if (sel != null && s in targets) {
                    val promo = if (abs(position.pieceAt(sel)) == PAWN && (rankOf(s) == 7 || rankOf(s) == 0)) QUEEN else 0
                    onMove(Move(sel, s, promo))
                    selected = null
                } else selected = if (mine) s else null
            }
        },
    ) {
        val c = size.width / 8
        fun topLeft(square: Int): Offset {
            val f = fileOf(square); val r = rankOf(square)
            val col = if (whiteAtBottom) f else 7 - f
            val row = if (whiteAtBottom) 7 - r else r
            return Offset(col * c, row * c)
        }
        for (s in 0 until 64) {
            val light = (fileOf(s) + rankOf(s)) % 2 == 1
            drawRect(if (light) Amoled.BoardLight else Amoled.BoardDark, topLeft(s), Size(c, c))
        }
        lastMove?.let { m -> for (s in listOf(m.from, m.to)) drawRect(Amoled.Accent.copy(alpha = 0.16f), topLeft(s), Size(c, c)) }
        marked.forEach { (s, col) -> drawRect(col.copy(alpha = 0.35f), topLeft(s), Size(c, c)) }
        selected?.let { drawRect(Amoled.Accent.copy(alpha = 0.3f), topLeft(it), Size(c, c)) }
        for (s in 0 until 64) {
            val p = position.pieceAt(s)
            if (p != 0) drawPiece(measurer, p, topLeft(s), c)
        }
        for (t in targets) drawCircle(Amoled.Accent.copy(alpha = 0.5f), c * 0.14f, topLeft(t) + Offset(c / 2, c / 2))
        for (a in arrows) drawArrow(a, c, ::topLeft)
    }
}

private fun DrawScope.drawPiece(measurer: TextMeasurer, p: Int, at: Offset, c: Float) {
    val glyph = GLYPHS[abs(p)].toString() + "︎"          // force text (not emoji) presentation
    val fontSize = (c * 0.78f / density).sp
    val fill = if (p > 0) Color(0xFFF7F7F7) else Color(0xFF0B0B0B)
    val edge = if (p > 0) Color(0xFF000000) else Color(0xFFBDBDBD)
    val base = TextStyle(fontSize = fontSize, color = fill)
    val layout = measurer.measure(glyph, base)
    val o = at + Offset((c - layout.size.width) / 2, (c - layout.size.height) / 2)
    drawText(layout, topLeft = o)
    drawText(measurer.measure(glyph, base.copy(color = edge, drawStyle = Stroke(width = c * 0.025f))), topLeft = o)
}

private fun DrawScope.drawArrow(a: BoardArrow, c: Float, topLeft: (Int) -> Offset) {
    val s = topLeft(a.move.from) + Offset(c / 2, c / 2)
    val e = topLeft(a.move.to) + Offset(c / 2, c / 2)
    val ang = atan2(e.y - s.y, e.x - s.x)
    val head = c * 0.38f
    val end = Offset(e.x - cos(ang) * head * 0.8f, e.y - sin(ang) * head * 0.8f)
    drawLine(a.color.copy(alpha = 0.85f), s, end, strokeWidth = c * 0.15f, cap = StrokeCap.Round)
    val path = Path().apply {
        moveTo(e.x, e.y)
        lineTo(e.x - cos(ang - 0.5f) * head, e.y - sin(ang - 0.5f) * head)
        lineTo(e.x - cos(ang + 0.5f) * head, e.y - sin(ang + 0.5f) * head)
        close()
    }
    drawPath(path, a.color.copy(alpha = 0.85f))
}
