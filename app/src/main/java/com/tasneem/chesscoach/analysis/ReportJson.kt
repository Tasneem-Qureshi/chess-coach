package com.tasneem.chesscoach.analysis

import com.tasneem.chesscoach.chess.Move
import org.json.JSONArray
import org.json.JSONObject

/** Stores a finished review so it never has to be recomputed. */
object ReportJson {

    fun write(r: GameReport): JSONObject = JSONObject().apply {
        put("userIsWhite", r.userIsWhite)
        put("accuracy", r.accuracy)
        put("opponentAccuracy", r.opponentAccuracy)
        put("opening", r.opening ?: JSONObject.NULL)
        put("phaseAccuracy", JSONObject().apply { r.phaseAccuracy.forEach { (k, v) -> put(k.name, v) } })
        put("counts", JSONObject().apply { r.counts.forEach { (k, v) -> put(k.name, v) } })
        put("tagCounts", JSONObject().apply { r.tagCounts.forEach { (k, v) -> put(k.name, v) } })
        put("strengths", insights(r.strengths))
        put("weaknesses", insights(r.weaknesses))
        put("keyMoments", JSONArray(r.keyMoments.map { it.ply }))
        put("plies", JSONArray(r.plies.map { p ->
            JSONObject().apply {
                put("ply", p.ply); put("w", p.whiteMoved); put("san", p.san); put("move", p.move.uci())
                put("bestSan", p.bestSan); put("best", p.bestMove?.uci() ?: "")
                put("eval", p.evalAfterWhite); put("evalText", p.evalAfterText)
                put("wb", p.winBefore); put("wa", p.winAfter); put("acc", p.accuracy)
                put("cls", p.cls.name); put("phase", p.phase.name)
                put("tags", JSONArray(p.tags.map { it.name }))
                put("why", p.explanation); put("line", p.bestLine)
                p.clock?.let { put("clock", it) }
            }
        }))
    }

    fun read(o: JSONObject): GameReport {
        val plies = o.getJSONArray("plies").let { a ->
            (0 until a.length()).map { i ->
                val p = a.getJSONObject(i)
                PlyReview(
                    ply = p.getInt("ply"), whiteMoved = p.getBoolean("w"), san = p.getString("san"),
                    move = Move.fromUci(p.getString("move"))!!, bestSan = p.optString("bestSan"),
                    bestMove = Move.fromUci(p.optString("best")),
                    evalAfterWhite = p.getInt("eval"), evalAfterText = p.optString("evalText"),
                    winBefore = p.getDouble("wb"), winAfter = p.getDouble("wa"), accuracy = p.getDouble("acc"),
                    cls = MoveClass.valueOf(p.getString("cls")), phase = Phase.valueOf(p.getString("phase")),
                    tags = p.optJSONArray("tags")?.let { t -> (0 until t.length()).mapNotNull { runCatching { Tag.valueOf(t.getString(it)) }.getOrNull() } } ?: emptyList(),
                    explanation = p.optString("why"), bestLine = p.optString("line"),
                    clock = if (p.has("clock")) p.getDouble("clock") else null,
                )
            }
        }
        val key = o.optJSONArray("keyMoments")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet()
        return GameReport(
            userIsWhite = o.getBoolean("userIsWhite"),
            plies = plies,
            accuracy = o.getDouble("accuracy"),
            opponentAccuracy = o.optDouble("opponentAccuracy", 0.0),
            phaseAccuracy = enumMap(o.optJSONObject("phaseAccuracy")) { k, v -> Phase.valueOf(k) to (v as Number).toDouble() },
            counts = enumMap(o.optJSONObject("counts")) { k, v -> MoveClass.valueOf(k) to (v as Number).toInt() },
            tagCounts = enumMap(o.optJSONObject("tagCounts")) { k, v -> Tag.valueOf(k) to (v as Number).toInt() },
            strengths = readInsights(o.optJSONArray("strengths")),
            weaknesses = readInsights(o.optJSONArray("weaknesses")),
            keyMoments = plies.filter { it.ply in key },
            opening = o.optString("opening").takeIf { o.has("opening") && !o.isNull("opening") && it.isNotEmpty() },
        )
    }

    private fun insights(l: List<Insight>) = JSONArray(l.map { JSONObject().put("t", it.title).put("d", it.detail) })

    private fun readInsights(a: JSONArray?): List<Insight> =
        if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it).let { o -> Insight(o.getString("t"), o.getString("d")) } }

    private fun <K, V> enumMap(o: JSONObject?, f: (String, Any) -> Pair<K, V>): Map<K, V> {
        if (o == null) return emptyMap()
        return o.keys().asSequence().mapNotNull { k -> runCatching { f(k, o.get(k)) }.getOrNull() }.toMap()
    }
}
