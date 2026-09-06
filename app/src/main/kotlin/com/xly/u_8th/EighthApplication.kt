package com.xly.u_8th

import android.app.Application
import com.xly.u_8th.data.ArtCache
import com.xly.u_8th.data.LibraryRepository
import com.xly.u_8th.data.LyricsRepository
import com.xly.u_8th.data.Prefs
import com.xly.u_8th.data.SharedPreferencesLrcPrefs
import com.xly.u_8th.playback.PlaybackGraph
import com.xly.u_8th.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class EighthApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val prefs: Prefs by lazy { Prefs(this) }
    val artCache: ArtCache by lazy { ArtCache(this) }
    val libraryRepository: LibraryRepository by lazy { LibraryRepository(this, prefs) }
    val lyricsRepository: LyricsRepository by lazy {
        LyricsRepository(this, SharedPreferencesLrcPrefs(this))
    }
    val playerConnection: PlayerConnection by lazy {
        PlayerConnection(applicationContext, appScope)
    }

    override fun onCreate() {
        super.onCreate()
        // Car "Aleatorio total" on a cold start: the service shuffles whatever
        // the persisted library source provides.
        PlaybackGraph.shuffleAllLibrary = {
            libraryRepository.restoreSongs() ?: emptyList()
        }
    }

    companion object {
        fun get(context: android.content.Context): EighthApplication =
            context.applicationContext as EighthApplication
    }
}
