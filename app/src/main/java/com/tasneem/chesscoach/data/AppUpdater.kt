package com.tasneem.chesscoach.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.tasneem.chesscoach.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Checks the repository's latest GitHub release and installs it (Android asks you to confirm). */
class AppUpdater(private val context: Context) {

    data class Update(val versionCode: Int, val versionName: String, val apkUrl: String, val notes: String)

    sealed interface State {
        data object Idle : State
        data class Available(val update: Update) : State
        data class Downloading(val progress: Float) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(Net.get("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest", "application/vnd.github+json"))
            val tag = o.getString("tag_name")                         // v1.0.<versionCode>
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return@runCatching null
            val assets = o.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name").endsWith(".apk") }
                ?: return@runCatching null
            Update(code, tag.removePrefix("v"), apk.getString("browser_download_url"), o.optString("body"))
        }.getOrNull()?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }?.also { _state.value = State.Available(it) }
    }

    suspend fun downloadAndInstall(u: Update) {
        val file = File(context.getExternalFilesDir(null) ?: context.cacheDir, "update.apk")
        try {
            _state.value = State.Downloading(0f)
            withContext(Dispatchers.IO) { Net.download(u.apkUrl, file) { _state.value = State.Downloading(it) } }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            _state.value = State.Available(u)
        } catch (t: Throwable) {
            _state.value = State.Failed(t.message ?: "Download failed")
        }
    }
}
