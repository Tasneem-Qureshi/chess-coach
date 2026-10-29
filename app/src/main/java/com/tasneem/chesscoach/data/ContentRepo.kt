package com.tasneem.chesscoach.data

import android.content.Context
import android.util.Log
import com.tasneem.chesscoach.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Puzzle(val id: String, val fen: String, val moves: List<String>, val rating: Int, val themes: List<String>, val source: String = "weekly")

data class Opening(val eco: String, val name: String, val sans: List<String>)

data class WeeklyContent(
    val version: Long = 0,
    val week: String = "",
    val updated: String = "",
    val engine: String = "",
    val puzzles: List<Puzzle> = emptyList(),
    val focus: List<Opening> = emptyList(),
    val theme: String = "",
)

/**
 * Weekly puzzles and opening focus. Bundled copies ship in the APK (assets) so the app works offline;
 * newer copies are pulled from the repository's /content folder, which a GitHub Action refreshes every week.
 */
class ContentRepo(private val context: Context) {
    private val dir = File(context.filesDir, "content").apply { mkdirs() }
    private val _content = MutableStateFlow(WeeklyContent())
    val content: StateFlow<WeeklyContent> = _content
    private var openings: List<Opening> = emptyList()

    suspend fun load() = withContext(Dispatchers.IO) {
        _content.value = runCatching { parse(read("manifest.json"), read("puzzles.json")) }
            .onFailure { Log.w(TAG, "content load failed", it) }.getOrDefault(WeeklyContent())
        openings = runCatching { parseOpenings(JSONArray(read("openings.json"))) }.getOrDefault(emptyList())
    }

    /** Downloads this week's content when the published version is newer. Returns true if something changed. */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val manifest = JSONObject(Net.get("${BuildConfig.CONTENT_URL}/manifest.json"))
            if (manifest.optLong("version") <= _content.value.version) return@runCatching false
            for (name in listOf("puzzles.json", "openings.json")) File(dir, name).writeText(Net.get("${BuildConfig.CONTENT_URL}/$name"))
            File(dir, "manifest.json").writeText(manifest.toString())
            load()
            true
        }.onFailure { Log.i(TAG, "content refresh skipped: ${it.message}") }.getOrDefault(false)
    }

    /** Longest opening line that the game's moves start with. */
    fun openingFor(sans: List<String>): Opening? {
        val clean = sans.map { it.trimEnd('+', '#') }
        return openings.filter { o -> o.sans.size <= clean.size && o.sans.indices.all { o.sans[it] == clean[it] } }
            .maxByOrNull { it.sans.size }
    }

    private fun read(name: String): String {
        val f = File(dir, name)
        val local = if (f.exists()) f.readText() else null
        val bundled = runCatching { context.assets.open(name).bufferedReader().readText() }.getOrNull()
        if (local == null) return bundled ?: error("missing $name")
        if (name == "manifest.json" && bundled != null) {
            // a fresh APK may bundle newer content than an old download
            if (JSONObject(bundled).optLong("version") > JSONObject(local).optLong("version")) { dir.listFiles()?.forEach { it.delete() }; return bundled }
        }
        return local
    }

    private fun parse(manifest: String, puzzles: String): WeeklyContent {
        val m = JSONObject(manifest)
        val p = JSONObject(puzzles).getJSONArray("puzzles")
        return WeeklyContent(
            version = m.optLong("version"), week = m.optString("week"), updated = m.optString("updated"),
            engine = m.optString("engine"), theme = m.optString("theme"),
            puzzles = (0 until p.length()).map { i ->
                val o = p.getJSONObject(i)
                Puzzle(
                    id = o.getString("id"), fen = o.getString("fen"),
                    moves = o.getJSONArray("moves").let { a -> (0 until a.length()).map { a.getString(it) } },
                    rating = o.optInt("rating"),
                    themes = o.optJSONArray("themes")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                )
            },
            focus = m.optJSONArray("focus")?.let { parseOpenings(it) } ?: emptyList(),
        )
    }

    private fun parseOpenings(a: JSONArray) = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        Opening(o.optString("eco"), o.getString("name"), o.getString("moves").split(' ').filter { it.isNotBlank() })
    }

    companion object { private const val TAG = "ContentRepo" }
}
