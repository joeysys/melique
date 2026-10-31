package com.hiwtata.melique.player

import android.content.Intent
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.hiwtata.melique.MusicApp
import com.hiwtata.melique.R

class PlaybackService : MediaSessionService() {
    private val engine: PlaybackEngine
        get() = (application as MusicApp).engine

    override fun onCreate() {
        super.onCreate()
        val provider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.playback_channel)
            .build()
        provider.setSmallIcon(R.drawable.ic_stat)
        setMediaNotificationProvider(provider)
        addSession(engine.session)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = engine.session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!engine.player.isPlaying) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        val session = engine.session
        if (isSessionAdded(session)) {
            removeSession(session)
        }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "sona_playback"
    }
}
