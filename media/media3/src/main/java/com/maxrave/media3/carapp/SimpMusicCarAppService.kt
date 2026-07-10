package com.maxrave.media3.carapp

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.maxrave.media3.service.SimpleMediaService

/**
 * Entry point for the Android Auto templated media experience (Car App
 * Library). Playback keeps flowing through [SimpleMediaService]'s
 * MediaSession as before; this service only contributes the template UI
 * (header + custom queue screen) on top of it.
 */
@UnstableApi
internal class SimpMusicCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator
                .Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = SimpMusicCarSession()
}

@UnstableApi
internal class SimpMusicCarSession : Session() {
    // Keeping a browser bound spins up SimpleMediaService (and its media
    // session) so the playback token is available while the car UI is showing.
    private var browserFuture: ListenableFuture<MediaBrowser>? = null

    init {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    browserFuture?.let { MediaController.releaseFuture(it) }
                    browserFuture = null
                }
            },
        )
    }

    override fun onCreateScreen(intent: Intent): Screen {
        browserFuture =
            MediaBrowser
                .Builder(
                    carContext,
                    SessionToken(carContext, ComponentName(carContext, SimpleMediaService::class.java)),
                ).buildAsync()
        return NowPlayingCarScreen(carContext)
    }
}
