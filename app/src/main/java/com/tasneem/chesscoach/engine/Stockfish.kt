package com.tasneem.chesscoach.engine

import android.content.Context
import android.util.Log
import com.tasneem.chesscoach.chess.Move
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Score from the side to move's point of view: centipawns, or mate in N (negative = getting mated). */
data class Score(val cp: Int = 0, val mate: Int? = null) {
    /** Centipawns with mates mapped to large values, for arithmetic. */
    val value: Int get() = when {
        mate == null -> cp
        mate > 0 -> MATE_CP - mate * 10
        mate < 0 -> -MATE_CP - mate * 10
        else -> -MATE_CP
    }

    operator fun unaryMinus() = Score(-cp, mate?.let { -it })

    fun format(): String = when {
        mate != null -> if (mate > 0) "M$mate" else "-M${-mate}"
        cp >= MATE_CP -> "#"
        cp <= -MATE_CP -> "-#"
        else -> (if (cp >= 0) "+" else "") + "%.1f".format(cp / 100.0)
    }

    companion object { const val MATE_CP = 100_000 }
}

data class Line(val score: Score, val pv: List<Move>, val depth: Int)

data class Analysis(val lines: List<Line>, val bestMove: Move?) {
    val best: Line? get() = lines.firstOrNull()
}

/** Parses UCI "info" lines into multi-PV lines. Kept free of Android types so it can be unit-tested. */
class UciParser {
    private val lines = sortedMapOf<Int, Line>()

    fun reset() = lines.clear()

    fun feed(line: String) {
        if (!line.startsWith("info ") || " pv " !in line) return
        val t = line.split(' ')
        var depth = 0; var multipv = 1; var score: Score? = null
        var i = 1
        var pv: List<Move> = emptyList()
        while (i < t.size) {
            when (t[i]) {
                "depth" -> { depth = t.getOrNull(i + 1)?.toIntOrNull() ?: 0; i += 2 }
                "multipv" -> { multipv = t.getOrNull(i + 1)?.toIntOrNull() ?: 1; i += 2 }
                "score" -> {
                    val kind = t.getOrNull(i + 1); val v = t.getOrNull(i + 2)?.toIntOrNull() ?: 0
                    score = if (kind == "mate") Score(mate = v) else Score(cp = v)
                    i += 3
                    if (t.getOrNull(i) == "lowerbound" || t.getOrNull(i) == "upperbound") return  // partial result, ignore
                }
                "pv" -> { pv = t.subList(i + 1, t.size).mapNotNull { Move.fromUci(it) }; i = t.size }
                else -> i++
            }
        }
        if (score != null && pv.isNotEmpty()) lines[multipv] = Line(score, pv, depth)
    }

    fun result(bestMove: String?): Analysis =
        Analysis(lines.values.toList(), bestMove?.takeIf { it != "(none)" }?.let { Move.fromUci(it) })
}

/**
 * Runs the bundled Stockfish executable (installed as libstockfish.so in nativeLibraryDir) and
 * talks UCI to it. One search at a time; a cancelled search is stopped cleanly so the next one starts fresh.
 */
class Stockfish(context: Context) {
    private val binary = File(context.applicationInfo.nativeLibraryDir, "libstockfish.so")
    private val mutex = Mutex()
    private var process: Process? = null
    private val output = LinkedBlockingQueue<String>()
    private var currentMultiPv = -1

    val available: Boolean get() = binary.exists()

    private fun ensureStarted(): Process {
        process?.takeIf { it.isAlive }?.let { return it }
        output.clear()
        currentMultiPv = -1
        val p = ProcessBuilder(binary.absolutePath).redirectErrorStream(true).start()
        process = p
        Thread({
            runCatching { p.inputStream.bufferedReader().forEachLine { output.put(it) } }
            output.put(EOF)
        }, "stockfish-out").apply { isDaemon = true }.start()
        val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
        send("uci"); waitFor("uciok", 10_000)
        send("setoption name Threads value $threads")
        send("setoption name Hash value 64")
        send("isready"); waitFor("readyok", 10_000)
        Log.i(TAG, "Stockfish started with $threads threads")
        return p
    }

    private fun send(cmd: String) {
        val p = process ?: return
        p.outputStream.write((cmd + "\n").toByteArray())
        p.outputStream.flush()
    }

    private fun waitFor(prefix: String, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            val line = output.poll(left, TimeUnit.MILLISECONDS) ?: return null
            if (line == EOF) { process = null; throw IllegalStateException("Stockfish exited") }
            if (line.startsWith(prefix)) return line
        }
    }

    /** Searches [fen] for [movetimeMs] (or to [depth]) and returns the top [multiPv] lines. */
    suspend fun analyze(fen: String, movetimeMs: Int = 1000, depth: Int? = null, multiPv: Int = 1): Analysis =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                ensureStarted()
                if (multiPv != currentMultiPv) { send("setoption name MultiPV value $multiPv"); currentMultiPv = multiPv }
                send("position fen $fen")
                send(if (depth != null) "go depth $depth movetime ${movetimeMs * 4}" else "go movetime $movetimeMs")
                val parser = UciParser()
                var result: Analysis? = null
                try {
                    val deadline = System.currentTimeMillis() + movetimeMs * 4L + 10_000
                    while (result == null) {
                        currentCoroutineContext().ensureActive()
                        val line = output.poll(50, TimeUnit.MILLISECONDS)
                        when {
                            line == null -> if (System.currentTimeMillis() > deadline) error("Stockfish timed out")
                            line == EOF -> { process = null; error("Stockfish exited") }
                            line.startsWith("bestmove") -> result = parser.result(line.split(' ').getOrNull(1))
                            else -> parser.feed(line)
                        }
                    }
                } finally {
                    if (result == null && process != null) withContext(NonCancellable) { runCatching { send("stop"); waitFor("bestmove", 3_000) } }
                }
                result!!
            }
        }

    fun newGame() { runCatching { if (process != null) send("ucinewgame") } }

    fun close() {
        runCatching { send("quit") }
        process?.destroy()
        process = null
    }

    companion object {
        private const val TAG = "Stockfish"
        private const val EOF = "\u0000EOF"
    }
}
