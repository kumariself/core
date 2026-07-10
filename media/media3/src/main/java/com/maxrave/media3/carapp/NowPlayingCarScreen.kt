package com.maxrave.media3.carapp

import android.support.v4.media.session.MediaSessionCompat
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.media.MediaPlaybackManager
import androidx.car.app.media.model.MediaPlaybackTemplate
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.logger.Logger
import com.maxrave.media3.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Now-playing screen for Android Auto. The host renders artwork, seek bar and
 * transport controls from the registered MediaSession; the app only owns the
 * header — queue name as title plus a queue button, which the classic media
 * surface never allowed us to customize.
 */
internal class NowPlayingCarScreen(
    carContext: CarContext,
) : Screen(carContext),
    KoinComponent {
    private val handler: MediaPlayerHandler by inject()
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    screenScope.cancel()
                }
            },
        )
        screenScope.launch {
            CarMediaSessionTokenStore.token.collect { token ->
                if (token != null) {
                    runCatching {
                        (carContext.getCarService(CarContext.MEDIA_PLAYBACK_SERVICE) as MediaPlaybackManager)
                            .registerMediaPlaybackToken(MediaSessionCompat.Token.fromToken(token))
                    }.onFailure {
                        Logger.e(TAG, "registerMediaPlaybackToken failed: ${it.message}")
                    }
                }
            }
        }
        screenScope.launch {
            handler.queueData.collect { invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        val queueTitle =
            handler.queueData.value
                ?.data
                ?.playlistName
                ?.takeIf { it.isNotBlank() }
        return MediaPlaybackTemplate
            .Builder()
            .setHeader(
                Header
                    .Builder()
                    .setTitle(queueTitle ?: "SimpMusic")
                    .addEndHeaderAction(
                        Action
                            .Builder()
                            .setIcon(
                                CarIcon
                                    .Builder(
                                        IconCompat.createWithResource(carContext, R.drawable.ic_car_queue_music),
                                    ).build(),
                            ).setOnClickListener {
                                screenManager.push(QueueCarScreen(carContext))
                            }.build(),
                    ).build(),
            ).build()
    }

    private companion object {
        private const val TAG = "NowPlayingCarScreen"
    }
}
