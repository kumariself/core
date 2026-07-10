package com.maxrave.media3.carapp

import android.media.session.MediaSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Same-process bridge handing the platform session token from
 * [com.maxrave.media3.service.SimpleMediaService] to the Android Auto
 * playback screen, which must register it with the car host so the host can
 * render metadata, artwork and transport controls for the current session.
 */
internal object CarMediaSessionTokenStore {
    private val _token = MutableStateFlow<MediaSession.Token?>(null)
    val token: StateFlow<MediaSession.Token?> = _token.asStateFlow()

    fun publish(token: MediaSession.Token?) {
        _token.value = token
    }

    fun clear() {
        _token.value = null
    }
}
