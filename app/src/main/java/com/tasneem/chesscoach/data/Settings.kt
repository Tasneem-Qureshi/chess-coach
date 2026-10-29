package com.tasneem.chesscoach.data

import android.content.Context
import com.tasneem.chesscoach.vision.BoardRect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class CoachSettings(
    /** Hide the suggestion until you tap, so you think first. */
    val tapToReveal: Boolean = true,
    /** Number of candidate moves listed (1–3). */
    val lines: Int = 3,
    /** Engine think time per suggestion. */
    val thinkMs: Int = 1500,
    /** Also show what the opponent is likely to play on their turn. */
    val showOpponentIdea: Boolean = false,
    /** Engine time per position in post-game review. */
    val reviewMs: Int = 400,
    val chessComUser: String = "",
)

/** SharedPreferences-backed settings exposed as a StateFlow. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<CoachSettings> = _flow
    val value get() = _flow.value

    private fun load() = CoachSettings(
        tapToReveal = prefs.getBoolean("tapToReveal", true),
        lines = prefs.getInt("lines", 3),
        thinkMs = prefs.getInt("thinkMs", 1500),
        showOpponentIdea = prefs.getBoolean("showOpponentIdea", false),
        reviewMs = prefs.getInt("reviewMs", 400),
        chessComUser = prefs.getString("chessComUser", "") ?: "",
    )

    fun update(f: (CoachSettings) -> CoachSettings) {
        val s = f(_flow.value)
        prefs.edit()
            .putBoolean("tapToReveal", s.tapToReveal).putInt("lines", s.lines).putInt("thinkMs", s.thinkMs)
            .putBoolean("showOpponentIdea", s.showOpponentIdea).putInt("reviewMs", s.reviewMs)
            .putString("chessComUser", s.chessComUser.trim())
            .apply()
        _flow.value = s
    }

    /** Board position on the captured frame, stored as fractions of the frame width so it survives resolution changes. */
    fun boardRect(frameWidth: Int): BoardRect? {
        if (!prefs.contains("boardX")) return null
        val w = frameWidth.toFloat()
        return BoardRect(prefs.getFloat("boardX", 0f) * w, prefs.getFloat("boardY", 0f) * w, prefs.getFloat("boardS", 0f) * w)
    }

    fun saveBoardRect(r: BoardRect?, frameWidth: Int) {
        val e = prefs.edit()
        if (r == null) e.remove("boardX").remove("boardY").remove("boardS")
        else { val w = frameWidth.toFloat(); e.putFloat("boardX", r.x / w).putFloat("boardY", r.y / w).putFloat("boardS", r.size / w) }
        e.apply()
    }

    var whiteAtBottom: Boolean
        get() = prefs.getBoolean("whiteAtBottom", true)
        set(v) = prefs.edit().putBoolean("whiteAtBottom", v).apply()
}
