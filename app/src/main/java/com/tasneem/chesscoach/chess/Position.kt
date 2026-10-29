package com.tasneem.chesscoach.chess

import kotlin.math.abs
import kotlin.math.sign

/**
 * Squares are 0..63 with a1 = 0, h1 = 7, a8 = 56. Pieces are signed ints: positive = white,
 * negative = black, magnitude = PAWN..KING. Positions are immutable; [play] returns a new one.
 */
const val EMPTY = 0
const val PAWN = 1
const val KNIGHT = 2
const val BISHOP = 3
const val ROOK = 4
const val QUEEN = 5
const val KING = 6

fun fileOf(sq: Int) = sq and 7
fun rankOf(sq: Int) = sq shr 3
fun sq(file: Int, rank: Int) = rank * 8 + file
fun sqName(s: Int) = "${'a' + fileOf(s)}${rankOf(s) + 1}"
fun parseSq(s: String): Int = sq(s[0] - 'a', s[1] - '1')

data class Move(val from: Int, val to: Int, val promo: Int = 0) {
    fun uci(): String = sqName(from) + sqName(to) + if (promo != 0) "nbrq"[promo - KNIGHT].toString() else ""

    companion object {
        fun fromUci(s: String): Move? {
            if (s.length < 4) return null
            val promo = if (s.length >= 5) when (s[4].lowercaseChar()) { 'n' -> KNIGHT; 'b' -> BISHOP; 'r' -> ROOK; 'q' -> QUEEN; else -> 0 } else 0
            return runCatching { Move(parseSq(s.substring(0, 2)), parseSq(s.substring(2, 4)), promo) }.getOrNull()
        }
    }
}

class Position(
    val board: IntArray,
    val whiteToMove: Boolean,
    /** bit 1 = K, 2 = Q, 4 = k, 8 = q */
    val castling: Int,
    val ep: Int,
    val halfmove: Int,
    val fullmove: Int,
) {
    val us get() = if (whiteToMove) 1 else -1

    fun pieceAt(s: Int) = board[s]

    fun kingSquare(white: Boolean): Int {
        val k = if (white) KING else -KING
        for (i in 0 until 64) if (board[i] == k) return i
        return -1
    }

    fun inCheck(): Boolean {
        val k = kingSquare(whiteToMove)
        return k >= 0 && isAttacked(k, !whiteToMove)
    }

    /** True if [s] is attacked by the side given by [byWhite]. */
    fun isAttacked(s: Int, byWhite: Boolean): Boolean {
        val sgn = if (byWhite) 1 else -1
        val f = fileOf(s); val r = rankOf(s)
        // pawns attack diagonally forward, so look one rank "behind" from the attacker's view
        val pr = r - sgn
        if (pr in 0..7) {
            if (f > 0 && board[sq(f - 1, pr)] == PAWN * sgn) return true
            if (f < 7 && board[sq(f + 1, pr)] == PAWN * sgn) return true
        }
        for ((df, dr) in KNIGHT_D) {
            val nf = f + df; val nr = r + dr
            if (nf in 0..7 && nr in 0..7 && board[sq(nf, nr)] == KNIGHT * sgn) return true
        }
        for ((df, dr) in KING_D) {
            val nf = f + df; val nr = r + dr
            if (nf in 0..7 && nr in 0..7 && board[sq(nf, nr)] == KING * sgn) return true
        }
        for ((df, dr) in ROOK_D) if (slideHits(f, r, df, dr, sgn, ROOK)) return true
        for ((df, dr) in BISHOP_D) if (slideHits(f, r, df, dr, sgn, BISHOP)) return true
        return false
    }

    private fun slideHits(f: Int, r: Int, df: Int, dr: Int, sgn: Int, kind: Int): Boolean {
        var nf = f + df; var nr = r + dr
        while (nf in 0..7 && nr in 0..7) {
            val p = board[sq(nf, nr)]
            if (p != EMPTY) return p == kind * sgn || p == QUEEN * sgn
            nf += df; nr += dr
        }
        return false
    }

    fun pseudoMoves(): List<Move> {
        val out = ArrayList<Move>(48)
        val u = us
        for (s in 0 until 64) {
            val p = board[s]
            if (p == EMPTY || p.sign != u) continue
            val f = fileOf(s); val r = rankOf(s)
            when (abs(p)) {
                PAWN -> {
                    val start = if (u == 1) 1 else 6
                    val last = if (u == 1) 7 else 0
                    val r1 = r + u
                    if (r1 in 0..7 && board[sq(f, r1)] == EMPTY) {
                        addPawn(out, s, sq(f, r1), r1 == last)
                        if (r == start && board[sq(f, r + 2 * u)] == EMPTY) out += Move(s, sq(f, r + 2 * u))
                    }
                    for (df in intArrayOf(-1, 1)) {
                        val nf = f + df
                        if (nf !in 0..7 || r1 !in 0..7) continue
                        val t = sq(nf, r1)
                        if ((board[t] != EMPTY && board[t].sign == -u) || t == ep) addPawn(out, s, t, r1 == last)
                    }
                }
                KNIGHT -> steps(out, s, f, r, KNIGHT_D)
                KING -> {
                    steps(out, s, f, r, KING_D)
                    castles(out, s)
                }
                BISHOP -> slides(out, s, f, r, BISHOP_D)
                ROOK -> slides(out, s, f, r, ROOK_D)
                QUEEN -> { slides(out, s, f, r, BISHOP_D); slides(out, s, f, r, ROOK_D) }
            }
        }
        return out
    }

    private fun addPawn(out: MutableList<Move>, from: Int, to: Int, promo: Boolean) {
        if (promo) for (pp in intArrayOf(QUEEN, ROOK, BISHOP, KNIGHT)) out += Move(from, to, pp)
        else out += Move(from, to)
    }

    private fun steps(out: MutableList<Move>, s: Int, f: Int, r: Int, d: Array<IntArray>) {
        for ((df, dr) in d) {
            val nf = f + df; val nr = r + dr
            if (nf !in 0..7 || nr !in 0..7) continue
            val t = sq(nf, nr)
            if (board[t] == EMPTY || board[t].sign != us) out += Move(s, t)
        }
    }

    private fun slides(out: MutableList<Move>, s: Int, f: Int, r: Int, d: Array<IntArray>) {
        for ((df, dr) in d) {
            var nf = f + df; var nr = r + dr
            while (nf in 0..7 && nr in 0..7) {
                val t = sq(nf, nr)
                if (board[t] == EMPTY) out += Move(s, t)
                else { if (board[t].sign != us) out += Move(s, t); break }
                nf += df; nr += dr
            }
        }
    }

    private fun castles(out: MutableList<Move>, s: Int) {
        val white = whiteToMove
        val home = if (white) 4 else 60
        if (s != home) return
        val kBit = if (white) 1 else 4
        val qBit = if (white) 2 else 8
        val rook = ROOK * us
        if (castling and kBit != 0 && board[home + 3] == rook && board[home + 1] == EMPTY && board[home + 2] == EMPTY &&
            !isAttacked(home, !white) && !isAttacked(home + 1, !white) && !isAttacked(home + 2, !white)
        ) out += Move(home, home + 2)
        if (castling and qBit != 0 && board[home - 4] == rook && board[home - 1] == EMPTY && board[home - 2] == EMPTY &&
            board[home - 3] == EMPTY && !isAttacked(home, !white) && !isAttacked(home - 1, !white) && !isAttacked(home - 2, !white)
        ) out += Move(home, home - 2)
    }

    private var legalCache: List<Move>? = null

    fun legalMoves(): List<Move> = legalCache ?: pseudoMoves().filter { m ->
        val next = play(m)
        val k = next.kingSquare(whiteToMove)
        k >= 0 && !next.isAttacked(k, !whiteToMove)
    }.also { legalCache = it }

    fun isLegal(m: Move) = legalMoves().contains(m)
    fun isCheckmate() = inCheck() && legalMoves().isEmpty()
    fun isStalemate() = !inCheck() && legalMoves().isEmpty()

    fun isCapture(m: Move) = board[m.to] != EMPTY || (abs(board[m.from]) == PAWN && m.to == ep && fileOf(m.from) != fileOf(m.to))

    fun captured(m: Move): Int = when {
        board[m.to] != EMPTY -> abs(board[m.to])
        abs(board[m.from]) == PAWN && m.to == ep && fileOf(m.from) != fileOf(m.to) -> PAWN
        else -> EMPTY
    }

    fun play(m: Move): Position {
        val b = board.copyOf()
        val p = b[m.from]
        val kind = abs(p)
        var newEp = -1
        var hm = halfmove + 1
        if (kind == PAWN || b[m.to] != EMPTY) hm = 0
        if (kind == PAWN) {
            if (m.to == ep && fileOf(m.from) != fileOf(m.to) && b[m.to] == EMPTY) b[sq(fileOf(m.to), rankOf(m.from))] = EMPTY
            if (abs(m.to - m.from) == 16) newEp = (m.from + m.to) / 2
        }
        b[m.to] = if (m.promo != 0) m.promo * p.sign else p
        b[m.from] = EMPTY
        if (kind == KING && abs(m.to - m.from) == 2) {
            if (m.to > m.from) { b[m.from + 1] = b[m.from + 3]; b[m.from + 3] = EMPTY }
            else { b[m.from - 1] = b[m.from - 4]; b[m.from - 4] = EMPTY }
        }
        var c = castling
        for (s in intArrayOf(m.from, m.to)) c = c and when (s) {
            4 -> 0b1100; 0 -> 0b1101; 7 -> 0b1110
            60 -> 0b0011; 56 -> 0b0111; 63 -> 0b1011
            else -> 0b1111
        }
        return Position(b, !whiteToMove, c, newEp, hm, if (whiteToMove) fullmove else fullmove + 1)
    }

    // ---- notation -------------------------------------------------------------------------

    fun san(m: Move): String {
        val p = abs(board[m.from])
        val sb = StringBuilder()
        if (p == KING && abs(m.to - m.from) == 2) {
            sb.append(if (m.to > m.from) "O-O" else "O-O-O")
        } else {
            val cap = isCapture(m)
            if (p == PAWN) {
                if (cap) sb.append('a' + fileOf(m.from)).append('x')
                sb.append(sqName(m.to))
                if (m.promo != 0) sb.append('=').append(PIECE_LETTERS[m.promo])
            } else {
                sb.append(PIECE_LETTERS[p])
                val rivals = legalMoves().filter { it.to == m.to && it.from != m.from && abs(board[it.from]) == p }
                if (rivals.isNotEmpty()) {
                    val sameFile = rivals.any { fileOf(it.from) == fileOf(m.from) }
                    val sameRank = rivals.any { rankOf(it.from) == rankOf(m.from) }
                    when {
                        !sameFile -> sb.append('a' + fileOf(m.from))
                        !sameRank -> sb.append('1' + rankOf(m.from))
                        else -> sb.append(sqName(m.from))
                    }
                }
                if (cap) sb.append('x')
                sb.append(sqName(m.to))
            }
        }
        val next = play(m)
        if (next.inCheck()) sb.append(if (next.legalMoves().isEmpty()) '#' else '+')
        return sb.toString()
    }

    fun parseSan(raw: String): Move? {
        val s = raw.trim().replace("0-0-0", "O-O-O").replace("0-0", "O-O").trimEnd('+', '#', '!', '?')
        if (s.isEmpty()) return null
        val norm = normalizeSan(s)
        return legalMoves().firstOrNull { normalizeSan(san(it).trimEnd('+', '#')) == norm }
            ?: looseSan(s)
            ?: Move.fromUci(raw.trim())?.takeIf { isLegal(it) }
    }

    /** Accepts over-specified SAN such as "Ngf3" or "e7e8Q" that the strict match rejects. */
    private fun looseSan(s: String): Move? {
        val m = SAN_RE.matchEntire(s) ?: return null
        val kind = m.groupValues[1].firstOrNull()?.let { abs(pieceFromChar(it)) } ?: PAWN
        val fromFile = m.groupValues[2].firstOrNull()?.let { it - 'a' }
        val fromRank = m.groupValues[3].firstOrNull()?.let { it - '1' }
        val to = parseSq(m.groupValues[4])
        val promo = m.groupValues[5].lastOrNull()?.let { abs(pieceFromChar(it)) } ?: 0
        return legalMoves().singleOrNull {
            it.to == to && abs(board[it.from]) == kind && it.promo == promo &&
                (fromFile == null || fileOf(it.from) == fromFile) && (fromRank == null || rankOf(it.from) == fromRank)
        }
    }

    private fun normalizeSan(s: String) = s.replace("=", "").replace("x", "").replace("ep", "").replace(" ", "")

    fun toFen(): String {
        val sb = StringBuilder()
        for (r in 7 downTo 0) {
            var empty = 0
            for (f in 0..7) {
                val p = board[sq(f, r)]
                if (p == EMPTY) { empty++; continue }
                if (empty > 0) { sb.append(empty); empty = 0 }
                sb.append(pieceChar(p))
            }
            if (empty > 0) sb.append(empty)
            if (r > 0) sb.append('/')
        }
        sb.append(if (whiteToMove) " w " else " b ")
        val c = buildString {
            if (castling and 1 != 0) append('K'); if (castling and 2 != 0) append('Q')
            if (castling and 4 != 0) append('k'); if (castling and 8 != 0) append('q')
        }
        sb.append(c.ifEmpty { "-" }).append(' ')
        sb.append(if (ep >= 0) sqName(ep) else "-")
        sb.append(' ').append(halfmove).append(' ').append(fullmove)
        return sb.toString()
    }

    /** Placement part of the FEN; used to compare positions regardless of clocks. */
    fun placement() = toFen().substringBefore(' ')

    fun nonPawnMaterial(): Int = board.sumOf { p -> when (abs(p)) { KNIGHT, BISHOP -> 3; ROOK -> 5; QUEEN -> 9; else -> 0 }.toInt() }

    /** Material balance in pawns from white's point of view. */
    fun materialBalance(): Int = board.sumOf { p -> p.sign * PIECE_VALUE[abs(p)] }

    override fun equals(other: Any?) = other is Position && toFen() == other.toFen()
    override fun hashCode() = toFen().hashCode()
    override fun toString() = toFen()

    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val PIECE_LETTERS = charArrayOf(' ', 'P', 'N', 'B', 'R', 'Q', 'K')
        val PIECE_VALUE = intArrayOf(0, 1, 3, 3, 5, 9, 0)
        val PIECE_NAMES = arrayOf("", "pawn", "knight", "bishop", "rook", "queen", "king")
        private val SAN_RE = Regex("^([NBRQK])?([a-h])?([1-8])?x?([a-h][1-8])(=?[NBRQ])?$")
        private val KNIGHT_D = arrayOf(intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(2, -1), intArrayOf(1, -2), intArrayOf(-1, -2), intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, 2))
        private val KING_D = arrayOf(intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(-1, 1), intArrayOf(-1, 0), intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1))
        private val ROOK_D = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
        private val BISHOP_D = arrayOf(intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1))

        fun start() = fromFen(START_FEN)

        fun pieceChar(p: Int): Char {
            val c = PIECE_LETTERS[abs(p)]
            return if (p > 0) c else c.lowercaseChar()
        }

        fun pieceFromChar(c: Char): Int {
            val k = when (c.uppercaseChar()) { 'P' -> PAWN; 'N' -> KNIGHT; 'B' -> BISHOP; 'R' -> ROOK; 'Q' -> QUEEN; 'K' -> KING; else -> 0 }
            return if (c.isUpperCase()) k else -k
        }

        fun fromFen(fen: String): Position {
            val parts = fen.trim().split(Regex("\\s+"))
            val b = IntArray(64)
            var r = 7; var f = 0
            for (c in parts[0]) when {
                c == '/' -> { r--; f = 0 }
                c.isDigit() -> f += c - '0'
                else -> { b[sq(f, r)] = pieceFromChar(c); f++ }
            }
            val white = parts.getOrElse(1) { "w" } == "w"
            var castle = 0
            parts.getOrElse(2) { "-" }.forEach { castle = castle or when (it) { 'K' -> 1; 'Q' -> 2; 'k' -> 4; 'q' -> 8; else -> 0 } }
            val ep = parts.getOrElse(3) { "-" }.let { if (it == "-") -1 else parseSq(it) }
            return Position(b, white, castle, ep, parts.getOrElse(4) { "0" }.toIntOrNull() ?: 0, parts.getOrElse(5) { "1" }.toIntOrNull() ?: 1)
        }

        /**
         * Builds a position from a bare piece placement (as read off the screen). Castling rights are
         * granted when king and rook still stand on their home squares; there is no en-passant square.
         */
        fun fromBoard(b: IntArray, whiteToMove: Boolean): Position {
            var c = 0
            if (b[4] == KING) { if (b[7] == ROOK) c = c or 1; if (b[0] == ROOK) c = c or 2 }
            if (b[60] == -KING) { if (b[63] == -ROOK) c = c or 4; if (b[56] == -ROOK) c = c or 8 }
            return Position(b.copyOf(), whiteToMove, c, -1, 0, 1)
        }
    }
}
