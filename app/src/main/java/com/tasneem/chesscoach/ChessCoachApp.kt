package com.tasneem.chesscoach

import android.app.Application
import android.content.Context
import com.tasneem.chesscoach.analysis.ReviewManager
import com.tasneem.chesscoach.data.AppUpdater
import com.tasneem.chesscoach.data.ContentRepo
import com.tasneem.chesscoach.data.GameStore
import com.tasneem.chesscoach.data.SettingsStore
import com.tasneem.chesscoach.engine.Stockfish
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ChessCoachApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings by lazy { SettingsStore(this) }
    val games by lazy { GameStore(this) }
    val content by lazy { ContentRepo(this) }
    val updater by lazy { AppUpdater(this, scope) }
    /** One engine process for the whole app; searches are serialised inside. */
    val engine by lazy { Stockfish(this) }
    val reviews by lazy { ReviewManager(scope, engine, games, content, settings) }

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            games.load()
            content.load()
            content.refresh()
            updater.check()
        }
    }

    companion object {
        fun get(c: Context) = c.applicationContext as ChessCoachApp
    }
}
