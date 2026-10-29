package com.tasneem.chesscoach.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.tasneem.chesscoach.chess.Move
import com.tasneem.chesscoach.vision.BoardRect
import com.tasneem.chesscoach.vision.Orientation
import com.tasneem.chesscoach.vision.OverlayInk
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Full-screen, touch-through layer that draws arrows on top of the board. Arrows use the exact
 * [OverlayInk] colours, which the board reader ignores, so drawing never disturbs recognition.
 */
@SuppressLint("ViewConstructor")
class ArrowLayer(context: Context) : View(context) {

    data class Arrow(val move: Move, val primary: Boolean)

    /** Board in screen pixels. */
    var board: BoardRect? = null
    var orientation = Orientation(true)
    var arrows: List<Arrow> = emptyList()
    /** Square to ring (hint: which piece to move). */
    var hintSquare: Int? = null
    /** Draw the board outline and grid (while the user fine-tunes the board position). */
    var showGrid = false

    private val loc = IntArray(2)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun update(board: BoardRect?, orientation: Orientation, arrows: List<Arrow>, hint: Int?, grid: Boolean) {
        this.board = board; this.orientation = orientation; this.arrows = arrows; this.hintSquare = hint; this.showGrid = grid
        invalidate()
    }

    private fun center(square: Int, b: BoardRect): Pair<Float, Float> {
        val (row, col) = orientation.cell(square)
        getLocationOnScreen(loc)
        return (b.x + (col + 0.5f) * b.cell - loc[0]) to (b.y + (row + 0.5f) * b.cell - loc[1])
    }

    override fun onDraw(canvas: Canvas) {
        val b = board ?: return
        if (showGrid) {
            getLocationOnScreen(loc)
            stroke.color = OverlayInk.ALT; stroke.strokeWidth = 3f
            for (i in 0..8) {
                val o = i * b.cell
                canvas.drawLine(b.x + o - loc[0], b.y - loc[1], b.x + o - loc[0], b.y + b.size - loc[1], stroke)
                canvas.drawLine(b.x - loc[0], b.y + o - loc[1], b.x + b.size - loc[0], b.y + o - loc[1], stroke)
            }
        }
        hintSquare?.let { s ->
            val (cx, cy) = center(s, b)
            stroke.color = OverlayInk.BEST; stroke.strokeWidth = b.cell * 0.08f
            canvas.drawCircle(cx, cy, b.cell * 0.44f, stroke)
        }
        // secondary arrows first so the best move sits on top
        for (a in arrows.sortedBy { it.primary }) drawArrow(canvas, b, a)
    }

    private fun drawArrow(canvas: Canvas, b: BoardRect, a: Arrow) {
        val (x1, y1) = center(a.move.from, b)
        val (x2, y2) = center(a.move.to, b)
        val color = if (a.primary) OverlayInk.BEST else OverlayInk.ALT
        val w = b.cell * if (a.primary) 0.16f else 0.09f
        val head = w * 2.6f
        val ang = atan2(y2 - y1, x2 - x1)
        val len = hypot(x2 - x1, y2 - y1)
        if (len < 1f) return
        val ex = x2 - cos(ang) * head * 0.8f
        val ey = y2 - sin(ang) * head * 0.8f
        stroke.color = color; stroke.strokeWidth = w
        canvas.drawLine(x1, y1, ex, ey, stroke)
        fill.color = color
        val p = Path().apply {
            moveTo(x2, y2)
            lineTo(x2 - cos(ang - 0.5f) * head, y2 - sin(ang - 0.5f) * head)
            lineTo(x2 - cos(ang + 0.5f) * head, y2 - sin(ang + 0.5f) * head)
            close()
        }
        canvas.drawPath(p, fill)
    }
}
