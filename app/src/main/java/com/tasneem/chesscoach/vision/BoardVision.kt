package com.tasneem.chesscoach.vision

import com.tasneem.chesscoach.chess.EMPTY
import com.tasneem.chesscoach.chess.Position
import com.tasneem.chesscoach.chess.sq
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A captured screen frame as ARGB ints. */
class Frame(val pixels: IntArray, val width: Int, val height: Int) {
    fun at(x: Int, y: Int): Int = pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
}

/** Board square on the frame; top-left corner plus side length, in frame pixels. */
data class BoardRect(val x: Float, val y: Float, val size: Float) {
    val cell get() = size / 8f
    fun scaled(s: Float) = BoardRect(x * s, y * s, size * s)
}

/** Colours the overlay draws its arrows with; vision ignores these pixels so arrows never confuse it. */
object OverlayInk {
    const val BEST = 0xFF00E5FF.toInt()
    const val ALT = 0xFFFF2BD6.toInt()
    fun isInk(c: Int) = dist(c, BEST) < 90 || dist(c, ALT) < 90
}

internal fun r(c: Int) = (c shr 16) and 0xFF
internal fun g(c: Int) = (c shr 8) and 0xFF
internal fun b(c: Int) = c and 0xFF
internal fun lum(c: Int) = (r(c) * 299 + g(c) * 587 + b(c) * 114) / 1000
internal fun dist(a: Int, c: Int) = abs(r(a) - r(c)) + abs(g(a) - g(c)) + abs(b(a) - b(c))

object BoardLocator {

    /** Finds the 8×8 board: the square region whose cells alternate light/dark most cleanly. */
    fun detect(f: Frame): BoardRect? {
        val w = f.width; val h = f.height
        val l = IntArray(w * h) { lum(f.pixels[it]) }
        val minDim = min(w, h)
        var best: BoardRect? = null
        var bestScore = Double.NEGATIVE_INFINITY
        fun consider(x: Int, y: Int, s: Int) {
            if (x < 0 || y < 0 || x + s > w || y + s > h) return
            val sc = score(l, w, x.toFloat(), y.toFloat(), s.toFloat())
            if (sc > bestScore) { bestScore = sc; best = BoardRect(x.toFloat(), y.toFloat(), s.toFloat()) }
        }
        // Fast path: phone apps (Chess.com, Lichess) show a full-width or centred board.
        for (s in (minDim * 0.4).toInt()..minDim step 2) for (y in 0..(h - s) step 2) consider((w - s) / 2, y, s)
        if (bestScore < STRONG) {
            val step = max(4, minDim / 50)
            var s = (minDim * 0.4).toInt()
            while (s <= minDim) {
                for (y in 0..(h - s) step step) for (x in 0..(w - s) step step) consider(x, y, s)
                s += step
            }
        }
        val start = best ?: return null
        if (bestScore < MIN_SCORE) return null
        // refine: coarse then fine
        for ((range, st) in listOf(12 to 2, 2 to 1)) {
            val c = best!!
            for (ds in -range..range step st) for (dy in -range..range step st) for (dx in -range..range step st)
                consider((c.x + dx).toInt(), (c.y + dy).toInt(), (c.size + ds).toInt())
        }
        return snapToEdges(l, w, h, best ?: start)
    }

    /**
     * Final pixel-level fit: the inner grid lines must sit exactly on the colour change between
     * neighbouring squares. Samples are taken near square corners, away from pieces.
     */
    private fun snapToEdges(l: IntArray, w: Int, h: Int, r: BoardRect): BoardRect {
        fun lum(x: Float, y: Float): Int = l[y.toInt().coerceIn(0, h - 1) * w + x.toInt().coerceIn(0, w - 1)]
        fun edgeScore(c: BoardRect): Double {
            val cell = c.cell
            var s = 0.0
            for (i in 1..7) for (j in 0 until 8) for (f in floatArrayOf(0.15f, 0.85f)) {
                // vertical line i, at height inside row j
                val lx = c.x + i * cell; val ly = c.y + (j + f) * cell
                val a = lum(lx - 2, ly); val b = lum(lx + 1, ly); val a2 = lum(lx - 5, ly); val b2 = lum(lx + 4, ly)
                s += abs(a - b) - abs(a - a2) - abs(b - b2)
                // horizontal line i, at width inside column j
                val hx = c.x + (j + f) * cell; val hy = c.y + i * cell
                val c1 = lum(hx, hy - 2); val d1 = lum(hx, hy + 1); val c2 = lum(hx, hy - 5); val d2 = lum(hx, hy + 4)
                s += abs(c1 - d1) - abs(c1 - c2) - abs(d1 - d2)
            }
            return s
        }
        var best = r; var bestScore = edgeScore(r)
        for (ds in -8..8) for (dy in -5..5) for (dx in -5..5) {
            val c = BoardRect(r.x + dx, r.y + dy, r.size + ds)
            if (c.x < 0 || c.y < 0 || c.x + c.size > w || c.y + c.size > h) continue
            val sc = edgeScore(c)
            if (sc > bestScore) { bestScore = sc; best = c }
        }
        return best
    }

    /**
     * Light-vs-dark contrast minus spread within each colour class. Each cell is sampled just inside two
     * opposite corners (top-right, bottom-left: the corners coordinate labels and pieces rarely touch), so
     * a grid that is slightly too small, too big or shifted lands samples in the wrong squares and loses.
     * A tiny size bonus breaks ties toward the full board.
     */
    fun score(l: IntArray, w: Int, x: Float, y: Float, size: Float): Double {
        val c = size / 8f
        val h = l.size / w
        var sl = 0.0; var sd = 0.0; var ql = 0.0; var qd = 0.0
        fun px(fx: Float, fy: Float): Int {
            val xx = fx.toInt().coerceIn(1, w - 2); val yy = fy.toInt().coerceIn(1, h - 2)
            return l[yy * w + xx]
        }
        for (row in 0 until 8) for (col in 0 until 8) {
            val x0 = x + col * c; val y0 = y + row * c
            val v = (px(x0 + c * 0.92f, y0 + c * 0.08f) + px(x0 + c * 0.88f, y0 + c * 0.12f) +
                px(x0 + c * 0.08f, y0 + c * 0.92f) + px(x0 + c * 0.12f, y0 + c * 0.88f)) / 4.0
            if ((row + col) % 2 == 0) { sl += v; ql += v * v } else { sd += v; qd += v * v }
        }
        val ml = sl / 32; val md = sd / 32
        val vl = sqrt(max(0.0, ql / 32 - ml * ml)); val vd = sqrt(max(0.0, qd / 32 - md * md))
        return (ml - md) - 1.5 * (vl + vd) + size * 0.002
    }

    private const val STRONG = 25.0
    private const val MIN_SCORE = 12.0
}

/** One board square resampled to N×N, with a foreground mask (piece pixels) relative to its own background. */
class SquareSample(val rgb: IntArray, val fg: BooleanArray, val ignore: BooleanArray) {
    val fgFraction: Float = fg.count { it }.toFloat() / fg.size
    val fgLum: Double = run {
        var s = 0.0; var n = 0
        for (i in rgb.indices) if (fg[i]) { s += lum(rgb[i]); n++ }
        if (n == 0) 0.0 else s / n
    }

    companion object {
        const val N = 24
        private const val INSET = 0.08f
        private const val FG_DIST = 60

        fun from(f: Frame, rect: BoardRect, row: Int, col: Int): SquareSample {
            val c = rect.cell
            val x0 = rect.x + col * c + c * INSET
            val y0 = rect.y + row * c + c * INSET
            val span = c * (1 - 2 * INSET)
            val rgb = IntArray(N * N)
            val ignore = BooleanArray(N * N)
            for (v in 0 until N) for (u in 0 until N) {
                val px = (x0 + (u + 0.5f) * span / N).toInt()
                val py = (y0 + (v + 0.5f) * span / N).toInt()
                val p = f.at(px, py)
                rgb[v * N + u] = p
                ignore[v * N + u] = OverlayInk.isInk(p)
            }
            val bg = background(rgb)
            val fg = BooleanArray(N * N) { !ignore[it] && dist(rgb[it], bg) > FG_DIST }
            return SquareSample(rgb, fg, ignore)
        }

        /** The corner colour closest to the other three corners (robust to a coordinate label in one corner). */
        private fun background(rgb: IntArray): Int {
            fun avg(u0: Int, v0: Int): Int {
                var rr = 0; var gg = 0; var bb = 0
                for (v in v0 until v0 + 2) for (u in u0 until u0 + 2) { val p = rgb[v * N + u]; rr += r(p); gg += g(p); bb += b(p) }
                return (0xFF shl 24) or ((rr / 4) shl 16) or ((gg / 4) shl 8) or (bb / 4)
            }
            val corners = listOf(avg(0, 0), avg(N - 2, 0), avg(0, N - 2), avg(N - 2, N - 2))
            return corners.minBy { a -> corners.sumOf { dist(a, it) } }
        }
    }
}

/**
 * Learns what each piece looks like from the starting position (whatever piece set and board theme
 * the user has) and then reads any position by template matching.
 */
class PieceTemplates(val samples: Map<Int, List<SquareSample>>) {

    fun classify(s: SquareSample): Int {
        if (s.fgFraction < 0.035f) return EMPTY
        var bestPiece = EMPTY
        var bestScore = s.fgFraction * 3.0          // cost of calling it empty
        for ((piece, list) in samples) for (t in list) {
            val sc = matchScore(s, t)
            if (sc < bestScore) { bestScore = sc; bestPiece = piece }
        }
        return bestPiece
    }

    fun write(out: OutputStream) {
        val d = DataOutputStream(out)
        d.writeInt(VERSION); d.writeInt(SquareSample.N)
        val all = samples.flatMap { (p, l) -> l.map { p to it } }
        d.writeInt(all.size)
        for ((p, s) in all) {
            d.writeInt(p)
            for (i in s.rgb.indices) { d.writeInt(s.rgb[i]); d.writeBoolean(s.fg[i]) }
        }
        d.flush()
    }

    companion object {
        private const val VERSION = 1

        fun matchScore(s: SquareSample, t: SquareSample): Double {
            var cost = 0.0; var union = 0
            for (i in s.rgb.indices) {
                if (s.ignore[i]) continue
                val a = s.fg[i]; val b = t.fg[i]
                if (!a && !b) continue
                union++
                cost += if (a && b) dist(s.rgb[i], t.rgb[i]) / 765.0 * 2.5 else 1.0
            }
            return if (union == 0) 1.0 else cost / union
        }

        fun read(input: InputStream): PieceTemplates? = runCatching {
            val d = DataInputStream(input)
            if (d.readInt() != VERSION || d.readInt() != SquareSample.N) return null
            val n = d.readInt()
            val map = HashMap<Int, MutableList<SquareSample>>()
            repeat(n) {
                val p = d.readInt()
                val rgb = IntArray(SquareSample.N * SquareSample.N)
                val fg = BooleanArray(rgb.size)
                for (i in rgb.indices) { rgb[i] = d.readInt(); fg[i] = d.readBoolean() }
                map.getOrPut(p) { ArrayList() } += SquareSample(rgb, fg, BooleanArray(rgb.size))
            }
            PieceTemplates(map)
        }.getOrNull()
    }
}

/** Maps screen cells to board squares for a board shown with white or black at the bottom. */
data class Orientation(val whiteAtBottom: Boolean) {
    fun square(row: Int, col: Int) = if (whiteAtBottom) sq(col, 7 - row) else sq(7 - col, row)
    fun cell(square: Int): Pair<Int, Int> {   // (row, col)
        val f = square and 7; val r = square shr 3
        return if (whiteAtBottom) (7 - r) to f else r to (7 - f)
    }
}

object BoardReader {
    const val UNKNOWN = 99

    fun sampleAll(f: Frame, rect: BoardRect): Array<SquareSample> =
        Array(64) { i -> SquareSample.from(f, rect, i / 8, i % 8) }   // index = row * 8 + col (screen order)

    /** True if the screen shows the initial setup: two full ranks at each edge, empty middle. */
    fun looksLikeStart(s: Array<SquareSample>): Boolean {
        for (row in 0 until 8) for (col in 0 until 8) {
            val frac = s[row * 8 + col].fgFraction
            val edge = row <= 1 || row >= 6
            if (edge && frac < 0.08f) return false
            if (!edge && frac > 0.05f) return false
        }
        return true
    }

    /** Whose pieces are at the bottom, judged by which side's pieces are brighter. */
    fun orientationFromStart(s: Array<SquareSample>): Orientation {
        val top = (0 until 16).map { s[it].fgLum }.average()
        val bottom = (48 until 64).map { s[it].fgLum }.average()
        return Orientation(whiteAtBottom = bottom >= top)
    }

    /**
     * Orientation from any position (not just the start): pawns only move forward, so on average the
     * white pawns sit lower on the screen than the black ones when white is at the bottom. Kings decide
     * when pawns don't. Returns null when it can't tell.
     */
    fun guessOrientation(s: Array<SquareSample>, t: PieceTemplates): Orientation? {
        var wp = 0.0; var wn = 0; var bp = 0.0; var bn = 0
        var wk = -1; var bk = -1
        for (row in 0 until 8) for (col in 0 until 8) {
            when (t.classify(s[row * 8 + col])) {
                1 -> { wp += row; wn++ }
                -1 -> { bp += row; bn++ }
                6 -> wk = row
                -6 -> bk = row
            }
        }
        if (wn >= 2 && bn >= 2) {
            val diff = wp / wn - bp / bn          // > 0: white pawns lower on screen
            if (diff >= 1.0) return Orientation(true)
            if (diff <= -1.0) return Orientation(false)
        }
        if (wk >= 0 && bk >= 0 && kotlin.math.abs(wk - bk) >= 4) return Orientation(wk > bk)
        return null
    }

    fun learn(s: Array<SquareSample>, o: Orientation): PieceTemplates {
        val start = Position.start().board
        val map = HashMap<Int, MutableList<SquareSample>>()
        for (row in listOf(0, 1, 6, 7)) for (col in 0 until 8) {
            val piece = start[o.square(row, col)]
            map.getOrPut(piece) { ArrayList() } += s[row * 8 + col]
        }
        // a few pawns are enough and keep matching fast
        map.keys.toList().forEach { k -> map[k] = map[k]!!.take(4).toMutableList() }
        return PieceTemplates(map)
    }

    /**
     * Reads the board into board-square order. Cells listed in [blocked] (covered by our own panel)
     * come back as [UNKNOWN].
     */
    fun read(s: Array<SquareSample>, t: PieceTemplates, o: Orientation, blocked: Set<Int> = emptySet()): IntArray {
        val out = IntArray(64)
        for (row in 0 until 8) for (col in 0 until 8) {
            val idx = row * 8 + col
            out[o.square(row, col)] = if (idx in blocked) UNKNOWN else t.classify(s[idx])
        }
        return out
    }
}
