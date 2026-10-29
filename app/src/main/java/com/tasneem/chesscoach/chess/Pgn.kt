package com.tasneem.chesscoach.chess

/** A game as a start position plus moves. Clock readings (seconds left after each ply) come from %clk tags. */
data class Game(
    val headers: Map<String, String>,
    val startFen: String,
    val moves: List<Move>,
    val clocks: List<Double?> = emptyList(),
) {
    /** positions[i] is the position before ply i; positions.last() is the final position. */
    val positions: List<Position> by lazy {
        val out = ArrayList<Position>(moves.size + 1)
        var p = Position.fromFen(startFen)
        out += p
        for (m in moves) { p = p.play(m); out += p }
        out
    }

    val sans: List<String> by lazy { moves.mapIndexed { i, m -> positions[i].san(m) } }

    fun toPgn(): String = buildString {
        val h = LinkedHashMap(headers)
        if (startFen != Position.START_FEN) { h["SetUp"] = "1"; h["FEN"] = startFen }
        h.forEach { (k, v) -> append("[$k \"${v.replace("\"", "'")}\"]\n") }
        append('\n')
        val start = positions.first()
        var white = start.whiteToMove
        var num = start.fullmove
        sans.forEachIndexed { i, s ->
            if (white) append("$num. ") else if (i == 0) append("$num... ")
            append(s).append(' ')
            if (!white) num++
            white = !white
        }
        append(headers["Result"] ?: "*")
    }
}

object Pgn {
    private val HEADER = Regex("""\[(\w+)\s+"((?:[^"\\]|\\.)*)"]""")
    private val CLK = Regex("""\[%clk\s+(\d+):(\d+):(\d+(?:\.\d+)?)]""")
    private val RESULTS = setOf("1-0", "0-1", "1/2-1/2", "*")

    /** Splits a multi-game PGN file into single games. */
    fun splitGames(text: String): List<String> {
        val games = ArrayList<String>()
        val cur = StringBuilder()
        var inMoves = false
        for (line in text.lines()) {
            val t = line.trim()
            if (t.startsWith("[") && inMoves) { games += cur.toString(); cur.clear(); inMoves = false }
            if (t.isNotEmpty() && !t.startsWith("[")) inMoves = true
            cur.append(line).append('\n')
        }
        if (cur.isNotBlank()) games += cur.toString()
        return games.filter { it.isNotBlank() }
    }

    fun parse(text: String): Game {
        val headers = LinkedHashMap<String, String>()
        HEADER.findAll(text).forEach { headers[it.groupValues[1]] = it.groupValues[2] }
        val body = text.lines().filterNot { it.trim().startsWith("[") && HEADER.containsMatchIn(it) }.joinToString(" ")
        val fen = headers["FEN"]?.takeIf { it.isNotBlank() } ?: Position.START_FEN
        var pos = Position.fromFen(fen)
        val moves = ArrayList<Move>()
        val clocks = ArrayList<Double?>()

        var i = 0
        var depth = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c == '{' -> {
                    val end = body.indexOf('}', i).let { if (it < 0) body.length else it }
                    if (depth == 0 && moves.isNotEmpty()) CLK.find(body.substring(i, end))?.let { m ->
                        val (h, mi, s) = m.destructured
                        clocks[clocks.lastIndex] = h.toInt() * 3600 + mi.toInt() * 60 + s.toDouble()
                    }
                    i = end + 1
                }
                c == ';' -> i = body.indexOf('\n', i).let { if (it < 0) body.length else it }
                c == '(' -> { depth++; i++ }
                c == ')' -> { depth--; i++ }
                c.isWhitespace() -> i++
                else -> {
                    var j = i
                    while (j < body.length && !body[j].isWhitespace() && body[j] !in "{}();") j++
                    val tok = body.substring(i, j)
                    i = j
                    if (depth > 0) continue
                    val word = tok.replace(Regex("^\\d+\\.+"), "")
                    if (word.isEmpty() || word.startsWith("$") || word in RESULTS || word.all { it.isDigit() || it == '.' }) continue
                    val m = pos.parseSan(word) ?: throw IllegalArgumentException("Illegal move \"$word\" after ${moves.size} plies")
                    moves += m
                    clocks += null
                    pos = pos.play(m)
                }
            }
        }
        return Game(headers, fen, moves, if (clocks.all { it == null }) emptyList() else clocks)
    }
}
