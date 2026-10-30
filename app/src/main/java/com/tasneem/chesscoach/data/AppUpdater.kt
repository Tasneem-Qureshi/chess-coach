package com.tasneem.chesscoach.data

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.tasneem.chesscoach.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Checks the repository's latest GitHub release and installs it (Android asks you to confirm).
 * The APK is fetched by the system DownloadManager, so the ~80 MB download keeps going when the app is
 * in the background or the screen is off, resumes after a dropped connection, and shows in notifications.
 */
class AppUpdater(private val context: Context, private val scope: CoroutineScope) {

    data class Update(val versionCode: Int, val versionName: String, val apkUrl: String, val notes: String)

    sealed interface State {
        data object Idle : State
        data class Available(val update: Update) : State
        data class Downloading(val update: Update, val progress: Float) : State
        data class Failed(val update: Update, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private val dm get() = context.getSystemService(DownloadManager::class.java)

    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(Net.get("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest", "application/vnd.github+json"))
            val tag = o.getString("tag_name")                         // v1.0.<versionCode>
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return@runCatching null
            val assets = o.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name").endsWith(".apk") }
                ?: return@runCatching null
            Update(code, tag.removePrefix("v"), apk.getString("browser_download_url"), o.optString("body"))
        }.getOrNull()?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }?.also { u ->
            if (_state.value !is State.Downloading) _state.value = State.Available(u)
            // a download started earlier (app closed meanwhile) is picked up again
            if (prefs.getInt("code", 0) == u.versionCode && prefs.getLong("id", -1) >= 0 && _state.value !is State.Downloading) {
                val id = prefs.getLong("id", -1)
                scope.launch { watch(u, id) }
            }
        }
    }

    private fun fileFor(u: Update) = File(context.getExternalFilesDir(null), "ChessCoach-${u.versionCode}.apk")

    /** Starts (or resumes) the download on the app-wide scope, so leaving the screen doesn't stop it. */
    fun start(u: Update) { scope.launch { downloadAndInstall(u) } }

    private suspend fun downloadAndInstall(u: Update) {
        if (_state.value is State.Downloading) return
        val file = fileFor(u)
        if (prefs.getInt("done", 0) == u.versionCode && file.exists()) { install(u, file); return }
        // drop leftovers from older updates
        context.getExternalFilesDir(null)?.listFiles { f -> f.name.endsWith(".apk") }?.forEach { it.delete() }
        prefs.getLong("id", -1).takeIf { it >= 0 }?.let { runCatching { dm.remove(it) } }
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(u.apkUrl))
                .setTitle("Chess Coach ${u.versionName}")
                .setDescription("Downloading update")
                .setMimeType(APK_MIME)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, null, file.name),
        )
        prefs.edit().putLong("id", id).putInt("code", u.versionCode).remove("done").apply()
        watch(u, id)
    }

    private suspend fun watch(u: Update, id: Long) {
        _state.value = State.Downloading(u, 0f)
        while (true) {
            val (status, done, total, reason) = withContext(Dispatchers.IO) { query(id) } ?: run {
                _state.value = State.Failed(u, "Download was cancelled"); prefs.edit().remove("id").apply(); return
            }
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    prefs.edit().putInt("done", u.versionCode).remove("id").apply()
                    install(u, fileFor(u))
                    return
                }
                DownloadManager.STATUS_FAILED -> {
                    prefs.edit().remove("id").apply()
                    _state.value = State.Failed(u, "Download failed (code $reason)")
                    return
                }
                else -> _state.value = State.Downloading(u, if (total > 0) done.toFloat() / total else 0f)
            }
            delay(500)
        }
    }

    private data class Status(val status: Int, val done: Long, val total: Long, val reason: Int)

    private fun query(id: Long): Status? = dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
        if (!c.moveToFirst()) return null
        Status(
            c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
            c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
            c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
        )
    }

    private fun install(u: Update, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            _state.value = State.Available(u)
        } catch (t: Throwable) {
            _state.value = State.Failed(u, "Couldn't open the installer: ${t.message}")
        }
    }

    /** Fallback: let the browser download it. */
    fun openInBrowser() {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.GITHUB_REPO}/releases/latest/download/ChessCoach.apk"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    companion object { private const val APK_MIME = "application/vnd.android.package-archive" }
}
