package com.tasneem.chesscoach.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.tasneem.chesscoach.ChessCoachApp
import com.tasneem.chesscoach.MainActivity
import com.tasneem.chesscoach.R
import com.tasneem.chesscoach.analysis.GameAnalyzer
import com.tasneem.chesscoach.analysis.MoveExplainer
import com.tasneem.chesscoach.chess.Move
import com.tasneem.chesscoach.chess.Position
import com.tasneem.chesscoach.data.GameStore
import com.tasneem.chesscoach.data.SavedGame
import com.tasneem.chesscoach.engine.Analysis
import com.tasneem.chesscoach.overlay.ArrowLayer
import com.tasneem.chesscoach.overlay.CoachActions
import com.tasneem.chesscoach.overlay.CoachUi
import com.tasneem.chesscoach.overlay.Feedback
import com.tasneem.chesscoach.overlay.OverlayWindows
import com.tasneem.chesscoach.overlay.Stage
import com.tasneem.chesscoach.overlay.SuggestedLine
import com.tasneem.chesscoach.overlay.Suggestion
import com.tasneem.chesscoach.vision.BoardLocator
import com.tasneem.chesscoach.vision.BoardReader
import com.tasneem.chesscoach.vision.BoardRect
import com.tasneem.chesscoach.vision.Frame
import com.tasneem.chesscoach.vision.GameTracker
import com.tasneem.chesscoach.vision.Orientation
import com.tasneem.chesscoach.vision.PieceTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service that watches the screen (MediaProjection), reads the board, follows the game
 * and asks Stockfish for the best moves, shown in the floating coach panel and as arrows on the board.
 */
class CoachService : LifecycleService(), CoachActions {

    private val app by lazy { ChessCoachApp.get(this) }
    private val main = Handler(Looper.getMainLooper())
    private val ui = MutableStateFlow(CoachUi())
    private lateinit var windows: OverlayWindows

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var captureW = 0
    private var captureH = 0
    private var screenW = 0
    private var bitmap: Bitmap? = null
    private var pixels = IntArray(0)

    // vision state (touched only from the capture loop, or posted to it via [pending])
    @Volatile private var rect: BoardRect? = null
    private var templates: PieceTemplates? = null
    @Volatile private var orientation = Orientation(true)
    private val tracker = GameTracker()
    private var wasStart = false
    private var lastDetect = 0L
    private var implausibleSince = 0L
    private val pending = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()

    // analysis state
    private var analysisJob: Job? = null
    private var lastUserTurn: Pair<String, Analysis>? = null      // fen → analysis of your last turn
    private var pendingFeedback: Triple<Position, Move, String>? = null
    private var opponentIdeaMove: Move? = null
    private var savedId: String? = null

    private val templateFile by lazy { File(filesDir, "piece-templates.bin") }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { main.post { stopSelf() } }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        windows = OverlayWindows(this, ui, this)
        templates = runCatching { templateFile.inputStream().use { PieceTemplates.read(it) } }.getOrNull()
        orientation = Orientation(app.settings.whiteAtBottom)
        tracker.userIsWhite = orientation.whiteAtBottom
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { close(); return START_NOT_STICKY }
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_CODE, Int.MIN_VALUE)
                @Suppress("DEPRECATION")
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java) else intent.getParcelableExtra(EXTRA_DATA)
                // Android 14+: the mediaProjection foreground type must be active before getMediaProjection().
                ServiceCompat.startForeground(this, NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                if (code == Int.MIN_VALUE || data == null || !startCapture(code, data)) { stopSelf(); return START_NOT_STICKY }
                _running.value = true
                windows.show()
                setUi { it.copy(stage = Stage.FINDING_BOARD, message = "Looking for a chessboard… open your game.") }
                lifecycleScope.launch(Dispatchers.Default) { loop() }
            }
        }
        return START_NOT_STICKY
    }

    // ---- capture ---------------------------------------------------------------------------

    private fun startCapture(code: Int, data: Intent): Boolean {
        val p = runCatching { getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data) }
            .onFailure { Log.e(TAG, "getMediaProjection failed", it) }.getOrNull() ?: return false
        p.registerCallback(projectionCallback, main)
        projection = p
        val (sw, sh) = screenSize()
        screenW = sw
        val scale = (CAPTURE_LONG_EDGE.toFloat() / maxOf(sw, sh)).coerceAtMost(1f)
        captureW = (sw * scale).toInt() / 2 * 2
        captureH = (sh * scale).toInt() / 2 * 2
        val dpi = (resources.configuration.densityDpi * scale).toInt().coerceAtLeast(120)
        val r = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
        reader = r
        display = p.createVirtualDisplay("chess-coach", captureW, captureH, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, main)
        return true
    }

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.let { it.width() to it.height() }
        else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            getSystemService(DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }

    private fun grabFrame(): Frame? {
        val image = reader?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val strideW = plane.rowStride / plane.pixelStride
            var bmp = bitmap
            if (bmp == null || bmp.width != strideW || bmp.height != image.height) {
                bmp?.recycle(); bmp = Bitmap.createBitmap(strideW, image.height, Bitmap.Config.ARGB_8888); bitmap = bmp
            }
            bmp.copyPixelsFromBuffer(plane.buffer)
            val w = image.width; val h = image.height
            if (pixels.size != w * h) pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            Frame(pixels, w, h)
        } catch (t: Throwable) {
            Log.w(TAG, "grabFrame failed", t); null
        } finally { image.close() }
    }

    private val screenScale get() = if (captureW == 0) 1f else screenW.toFloat() / captureW

    // ---- main loop -------------------------------------------------------------------------

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val t0 = System.currentTimeMillis()
            while (true) pending.poll()?.invoke() ?: break
            grabFrame()?.let { f -> runCatching { process(f, t0) }.onFailure { Log.w(TAG, "frame failed", it) } }
            delay((FRAME_MS - (System.currentTimeMillis() - t0)).coerceAtLeast(60))
        }
    }

    private fun process(f: Frame, now: Long) {
        val board = rect ?: run {
            if (now - lastDetect < 1500) return
            lastDetect = now
            val found = app.settings.boardRect(f.width) ?: BoardLocator.detect(f)?.also { app.settings.saveBoardRect(it, f.width) }
            if (found == null) {
                setUi { it.copy(stage = Stage.FINDING_BOARD, message = "Looking for a chessboard… open your game (share the entire screen).") }
                return
            }
            rect = found
            refreshArrows()
            found
        }
        val samples = BoardReader.sampleAll(f, board)
        val start = BoardReader.looksLikeStart(samples)
        if (start && !wasStart) {
            val o = BoardReader.orientationFromStart(samples)
            val t = BoardReader.learn(samples, o)
            templates = t
            runCatching { templateFile.outputStream().use { t.write(it) } }
            setOrientation(o)
        }
        wasStart = start
        val t = templates ?: run {
            setUi { it.copy(stage = Stage.LEARNING_PIECES, message = "Start a new game: I learn your board and pieces from the starting position (only needed once).") }
            return
        }
        if (tracker.position == null && !start) {
            // started mid-game: work out which colour is at the bottom (= your colour) from the pieces
            BoardReader.guessOrientation(samples, t)?.let { if (it != orientation) setOrientation(it) }
        }
        val raw = BoardReader.read(samples, t, orientation, blockedCells(board))
        val kingsOk = raw.count { it == 6 } <= 1 && raw.count { it == -6 } <= 1 && raw.any { it == 6 || it == -6 || it == BoardReader.UNKNOWN }
        if (!kingsOk) {
            if (implausibleSince == 0L) implausibleSince = now
            if (now - implausibleSince > 8_000 && !ui.value.adjusting) {
                implausibleSince = 0; rect = null; app.settings.saveBoardRect(null, f.width)
                setUi { it.copy(stage = Stage.FINDING_BOARD, message = "Lost the board — searching again…") }
            }
            return
        }
        implausibleSince = 0
        val events = tracker.onBoard(raw, now)
        events.forEach(::onEvent)
        if (events.isNotEmpty()) analyzeCurrent()
    }

    /** Board cells (screen order) hidden behind our own panel. */
    private fun blockedCells(b: BoardRect): Set<Int> {
        val p = windows.panelBounds ?: return emptySet()
        val s = screenScale
        val out = HashSet<Int>()
        for (row in 0 until 8) for (col in 0 until 8) {
            val x0 = (b.x + col * b.cell) * s; val y0 = (b.y + row * b.cell) * s
            val x1 = x0 + b.cell * s; val y1 = y0 + b.cell * s
            if (x1 > p.left && x0 < p.right && y1 > p.top && y0 < p.bottom) out += row * 8 + col
        }
        return out
    }

    private fun setOrientation(o: Orientation) {
        orientation = o
        app.settings.whiteAtBottom = o.whiteAtBottom
        tracker.userIsWhite = o.whiteAtBottom
        setUi { it.copy(userIsWhite = o.whiteAtBottom) }
    }

    // ---- game events -----------------------------------------------------------------------

    private fun onEvent(e: GameTracker.Event) {
        when (e) {
            is GameTracker.Event.NewGame -> {
                e.previous?.let { autoSave(it) }
                savedId = null
                app.engine.newGame()
                lastUserTurn = null; pendingFeedback = null
                setUi { it.copy(feedback = null, result = null) }
            }
            is GameTracker.Event.Moved -> {
                if (e.byWhite == tracker.userIsWhite) pendingFeedback = Triple(e.before, e.move, e.san)
            }
            is GameTracker.Event.Resynced -> {
                // keep what was tracked before the jump as its own game; the new segment gets a new id
                e.previous?.let { if (autoSave(it) != null) savedId = null }
                lastUserTurn = null; pendingFeedback = null
                setUi { it.copy(feedback = null) }
            }
        }
    }

    private fun analyzeCurrent() {
        analysisJob?.cancel()
        val pos = tracker.position ?: return
        val userTurn = pos.whiteToMove == tracker.userIsWhite
        val s = app.settings.value
        opponentIdeaMove = null
        setUi {
            it.copy(
                stage = Stage.TRACKING, yourTurn = userTurn, plies = tracker.plies, thinking = true, suggestion = null,
                note = if (tracker.sideGuessed) "Joined mid-game: guessing it's ${if (userTurn) "your" else "their"} move. I'll correct it after the next move, or tap Side ⇄." else null,
                opponentIdea = null, revealed = !s.tapToReveal, hint = false, userIsWhite = tracker.userIsWhite,
            )
        }
        refreshArrows()
        if (pos.legalMoves().isEmpty()) { gameOver(pos); return }
        if (!app.engine.available) { setUi { it.copy(thinking = false, message = "Engine missing from this build.") }; return }

        analysisJob = lifecycleScope.launch(Dispatchers.Default) {
            try { analyze(pos, userTurn, s) } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (t: Throwable) {
                Log.w(TAG, "analysis failed", t)
                // the engine restarts itself on the next request; tell the user instead of hanging
                setUi { it.copy(thinking = false, note = "Engine hiccup — retrying on the next move. Tap Find board if it persists.") }
            }
            refreshArrows()
        }
    }

    private suspend fun analyze(pos: Position, userTurn: Boolean, s: com.tasneem.chesscoach.data.CoachSettings) {
        run {
            val fen = pos.toFen()
            if (userTurn) {
                val a = app.engine.analyze(fen, movetimeMs = s.thinkMs, multiPv = s.lines.coerceIn(1, 3))
                val threat = if (!pos.inCheck()) threatOf(pos) else null
                lastUserTurn = fen to a
                val best = a.best
                val lines = a.lines.mapNotNull { l ->
                    val m = l.pv.firstOrNull()?.takeIf { pos.isLegal(it) } ?: return@mapNotNull null
                    SuggestedLine(m, pos.san(m), l.score.format(), MoveExplainer.pvSan(pos, l.pv))
                }
                val why = best?.let { MoveExplainer.explain(pos, it, a.lines.getOrNull(1), threat?.first) } ?: ""
                setUi {
                    it.copy(
                        thinking = false, suggestion = Suggestion(lines, why, threat?.second, best?.depth ?: 0),
                        evalCp = whiteCp(pos, a), evalText = whiteText(pos, a),
                    )
                }
                // your move and their reply arrived together: grade your move now
                pendingFeedback?.let { (before, move, san) ->
                    pendingFeedback = null
                    val after = app.engine.analyze(before.play(move).toFen(), movetimeMs = 500, multiPv = 1)
                    feedbackFor(before, move, san, after)?.let { fb -> setUi { it.copy(feedback = fb) } }
                }
            } else {
                val a = app.engine.analyze(fen, movetimeMs = minOf(s.thinkMs, 900), multiPv = 1)
                val fb = pendingFeedback?.let { (before, move, san) -> feedbackFor(before, move, san, a) }
                pendingFeedback = null
                val idea = if (s.showOpponentIdea) a.bestMove?.takeIf { pos.isLegal(it) } else null
                opponentIdeaMove = idea
                setUi {
                    it.copy(
                        thinking = false, evalCp = whiteCp(pos, a), evalText = whiteText(pos, a),
                        feedback = fb ?: it.feedback, opponentIdea = idea?.let { m -> pos.san(m) },
                    )
                }
            }
        }
    }

    private suspend fun feedbackFor(before: Position, move: Move, san: String, afterAnalysis: Analysis): Feedback? {
        val fen = before.toFen()
        val beforeAnalysis = lastUserTurn?.takeIf { it.first == fen }?.second
            ?: app.engine.analyze(fen, movetimeMs = 500, multiPv = 1)
        val scoreBefore = beforeAnalysis.best?.score ?: return null
        val scoreAfter = afterAnalysis.best?.score?.let { -it } ?: return null
        val best = beforeAnalysis.bestMove ?: beforeAnalysis.best?.pv?.firstOrNull()
        val cls = GameAnalyzer.quickClass(scoreBefore, scoreAfter, move, best)
        return Feedback(san, cls, best?.takeIf { before.isLegal(it) }?.let { before.san(it) } ?: "")
    }

    /** What the opponent would do if it were their move: a "null move" search. */
    private suspend fun threatOf(pos: Position): Pair<Move, String>? {
        val flipped = Position(pos.board, !pos.whiteToMove, pos.castling, -1, 0, pos.fullmove)
        if (flipped.inCheck().not() && flipped.kingSquare(pos.whiteToMove) >= 0) {
            val a = app.engine.analyze(flipped.toFen(), movetimeMs = 300, multiPv = 1)
            val line = a.best ?: return null
            val m = line.pv.firstOrNull()?.takeIf { flipped.isLegal(it) } ?: return null
            val mate = line.score.mate
            if ((mate != null && mate > 0) || MoveExplainer.materialSwing(flipped, line.pv) >= 2) {
                val what = if (mate != null && mate > 0) "checkmate in $mate starting with ${flipped.san(m)}" else flipped.san(m)
                return m to what
            }
        }
        return null
    }

    private fun whiteCp(p: Position, a: Analysis): Int {
        val v = a.best?.score?.value ?: 0
        return if (p.whiteToMove) v else -v
    }

    private fun whiteText(p: Position, a: Analysis): String {
        val sc = a.best?.score ?: return ""
        return (if (p.whiteToMove) sc else -sc).format()
    }

    private fun gameOver(pos: Position) {
        val result = when {
            pos.isCheckmate() -> if (pos.whiteToMove) "0-1" else "1-0"
            else -> "1/2-1/2"
        }
        val userWon = (result == "1-0") == tracker.userIsWhite && result != "1/2-1/2"
        val text = when {
            result == "1/2-1/2" -> "Stalemate — draw."
            userWon -> "Checkmate — you won! Review it to see what went right."
            else -> "Checkmate. Review the game to learn from it."
        }
        setUi { it.copy(thinking = false, result = text, yourTurn = false) }
        autoSave(result = result)
    }

    // ---- saving ----------------------------------------------------------------------------

    private fun autoSave(game: com.tasneem.chesscoach.chess.Game = tracker.game(), result: String? = null): String? {
        if (game.moves.size < 6) return savedId
        val final = game.positions.last()
        val res = result ?: when {
            final.isCheckmate() -> if (final.whiteToMove) "0-1" else "1-0"
            final.isStalemate() -> "1/2-1/2"
            else -> "*"
        }
        val white = if (tracker.userIsWhite) "You" else "Opponent"
        val black = if (tracker.userIsWhite) "Opponent" else "You"
        val pgn = game.copy(headers = linkedMapOf("Event" to "Coach overlay", "White" to white, "Black" to black, "Result" to res)).toPgn()
        val id = savedId ?: GameStore.newId()
        savedId = id
        val saved = SavedGame(id, "overlay", white, black, tracker.userIsWhite, res, System.currentTimeMillis(), pgn)
        app.scope.launch { app.games.save(saved) }
        return id
    }

    // ---- arrows ----------------------------------------------------------------------------

    private fun refreshArrows() {
        val u = ui.value
        val b = rect?.scaled(screenScale)
        val list = ArrayList<ArrowLayer.Arrow>()
        val s = u.suggestion
        if (u.stage == Stage.TRACKING && u.result == null) {
            if (u.yourTurn && u.revealed && s != null) s.lines.forEachIndexed { i, l -> list += ArrowLayer.Arrow(l.move, i == 0) }
            if (!u.yourTurn) opponentIdeaMove?.let { list += ArrowLayer.Arrow(it, false) }
        }
        val hint = if (u.yourTurn && u.hint && !u.revealed) s?.lines?.firstOrNull()?.move?.from else null
        val o = orientation
        windows.onMain { windows.arrows?.update(b, o, list, hint, u.adjusting) }
    }

    private inline fun setUi(f: (CoachUi) -> CoachUi) = ui.update(f)

    // ---- panel actions ---------------------------------------------------------------------

    override fun reveal() { setUi { it.copy(revealed = true) }; refreshArrows() }
    override fun hint() { setUi { it.copy(hint = true) }; refreshArrows() }
    override fun toggleCollapsed() = setUi { it.copy(collapsed = !it.collapsed) }

    override fun flipBoard() {
        pending += {
            setOrientation(Orientation(!orientation.whiteAtBottom))
            tracker.reset()
            refreshArrows()
        }
    }

    override fun switchSideToMove() {
        pending += {
            tracker.position?.let { p ->
                tracker.setPosition(Position(p.board, !p.whiteToMove, p.castling, -1, 0, p.fullmove))
                lastUserTurn = null; pendingFeedback = null
                analyzeCurrent()
            }
        }
    }

    override fun redetect() {
        pending += {
            rect = null; lastDetect = 0
            app.settings.saveBoardRect(null, captureW)
            tracker.reset()
            setUi { it.copy(stage = Stage.FINDING_BOARD, message = "Looking for the board…") }
            refreshArrows()
        }
    }

    override fun toggleAdjust() {
        setUi { it.copy(adjusting = !it.adjusting) }
        if (rect == null) rect = BoardRect(0f, captureH / 4f, captureW.toFloat())
        refreshArrows()
    }

    override fun nudge(dx: Int, dy: Int, ds: Int) {
        pending += {
            rect?.let { r ->
                val n = BoardRect(r.x + dx, r.y + dy, (r.size + ds).coerceAtLeast(80f))
                rect = n
                app.settings.saveBoardRect(n, captureW)
                tracker.reset()
                refreshArrows()
            }
        }
    }

    override fun finishAndReview() {
        val id = runCatching { autoSave() }.getOrNull()
        if (id != null) {
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_GAME_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            stopSelf()
        } else {
            setUi { it.copy(result = "Play at least 3 moves each before reviewing.") }
        }
    }

    override fun close() {
        runCatching { autoSave() }
        stopSelf()
    }

    // ---- lifecycle -------------------------------------------------------------------------

    override fun onDestroy() {
        _running.value = false
        analysisJob?.cancel()
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop(); projection = null
        windows.destroy()
        bitmap?.recycle(); bitmap = null
        super.onDestroy()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, CoachService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_knight)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true).setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "CoachService"
        private const val CHANNEL = "coach"
        private const val NOTIF_ID = 7
        private const val CAPTURE_LONG_EDGE = 1280
        private const val FRAME_MS = 250L
        private const val ACTION_START = "com.tasneem.chesscoach.START"
        private const val ACTION_STOP = "com.tasneem.chesscoach.STOP"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        private val _running = MutableStateFlow(false)
        val running: kotlinx.coroutines.flow.StateFlow<Boolean> = _running

        fun start(c: Context, code: Int, data: Intent) = c.startForegroundService(
            Intent(c, CoachService::class.java).setAction(ACTION_START).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data),
        )

        fun stop(c: Context) = c.startService(Intent(c, CoachService::class.java).setAction(ACTION_STOP))
    }
}
