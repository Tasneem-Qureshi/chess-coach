package com.tasneem.chesscoach.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.tasneem.chesscoach.ui.theme.ChessCoachTheme
import kotlinx.coroutines.flow.StateFlow

/**
 * Two overlay windows: a touch-through arrow layer covering the whole screen, and the draggable
 * coach panel. Neither takes focus, so the chess app keeps receiving your taps.
 */
class OverlayWindows(private val context: Context, private val state: StateFlow<CoachUi>, private val actions: CoachActions) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var panel: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null
    var arrows: ArrowLayer? = null
        private set

    private val arrowParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private val panelParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 120 }

    /** Panel bounds on screen, read by the capture loop to skip squares the panel covers. */
    @Volatile var panelBounds: Rect? = null
        private set

    fun show() = main.post {
        if (arrows == null) {
            val a = ArrowLayer(context)
            runCatching { wm.addView(a, arrowParams) }.onSuccess { arrows = a }.onFailure { Log.e(TAG, "arrow layer failed", it) }
        }
        if (panel == null) {
            val o = OverlayLifecycleOwner().also { it.create() }
            val v = ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                o.attachTo(this)
                setContent { ChessCoachTheme { CoachPanel(state, actions, ::moveBy) } }
                addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> updateBounds(view) }
            }
            runCatching { wm.addView(v, panelParams) }
                .onSuccess { panel = v; owner = o; o.resume() }
                .onFailure { Log.e(TAG, "panel failed (overlay permission?)", it); o.destroy() }
        }
    }

    private fun updateBounds(v: android.view.View) {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        panelBounds = Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    private fun moveBy(dx: Float, dy: Float) {
        val v = panel ?: return
        panelParams.x += dx.toInt(); panelParams.y += dy.toInt()
        runCatching { wm.updateViewLayout(v, panelParams) }
        v.post { updateBounds(v) }
    }

    fun onMain(block: () -> Unit) = main.post(block)

    fun destroy() = main.post {
        panel?.let { runCatching { wm.removeViewImmediate(it) } }
        arrows?.let { runCatching { wm.removeViewImmediate(it) } }
        owner?.destroy()
        panel = null; arrows = null; owner = null; panelBounds = null
    }

    companion object { private const val TAG = "OverlayWindows" }
}
