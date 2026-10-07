// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.kuma3.lofi

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Keeps [Lofi]'s music playing in the background: Media3 shows the media notification (play/pause,
 * next) and the lock-screen controls while it plays.
 */
class LofiService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession.Builder(this, Lofi.player).setId("kuma3-lofi").build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    /** Swiped away from recent apps: stop unless music is playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!Lofi.isPlaying) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        Lofi.release()
        super.onDestroy()
    }
}
