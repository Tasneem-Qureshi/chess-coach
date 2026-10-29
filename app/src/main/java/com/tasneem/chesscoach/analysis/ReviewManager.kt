package com.tasneem.chesscoach.analysis

import com.tasneem.chesscoach.chess.Pgn
import com.tasneem.chesscoach.data.ContentRepo
import com.tasneem.chesscoach.data.GameStore
import com.tasneem.chesscoach.data.SettingsStore
import com.tasneem.chesscoach.engine.Stockfish
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Runs post-game reviews in the background (one at a time) and stores the result with the game. */
class ReviewManager(
    private val scope: CoroutineScope,
    private val engine: Stockfish,
    private val games: GameStore,
    private val content: ContentRepo,
    private val settings: SettingsStore,
) {
    data class Progress(val gameId: String, val done: Int, val total: Int, val error: String? = null)

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress
    private var job: Job? = null
    private val queue = ArrayDeque<String>()

    fun review(gameId: String) {
        if (gameId in queue || _progress.value[gameId]?.let { it.error == null } == true) return
        queue += gameId
        _progress.value = _progress.value + (gameId to Progress(gameId, 0, 1))
        if (job?.isActive != true) job = scope.launch { drain() }
    }

    private suspend fun drain() {
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            val g = games.get(id) ?: continue
            try {
                if (!engine.available) error("Chess engine missing from this build")
                val game = Pgn.parse(g.pgn)
                val opening = content.openingFor(game.sans)?.let { "${it.eco} ${it.name}" }
                val ms = settings.value.reviewMs
                engine.newGame()
                val report = GameAnalyzer.analyze(
                    game, g.userIsWhite, opening,
                    evaluate = { p -> engine.analyze(p.toFen(), movetimeMs = ms, multiPv = 2) },
                    onProgress = { d, t -> _progress.value = _progress.value + (id to Progress(id, d, t)) },
                )
                games.save(g.copy(report = report))
                _progress.value = _progress.value - id
            } catch (t: Throwable) {
                _progress.value = _progress.value + (id to Progress(id, 0, 1, t.message ?: "Review failed"))
            }
        }
    }
}
