package com.example.b2musicplayer

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val dataSourceFactory = B2Utils.getDataSourceFactory(this)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                20_000,
                60_000,
                10_000,
                10_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
