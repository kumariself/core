package com.maxrave.media3.carapp

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import com.google.common.util.concurrent.ListenableFuture
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.logger.Logger
import com.maxrave.media3.utils.CoilBitmapLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Generic browse screen recreating the classic Android Auto media tree on the
 * templated surface: it renders the children of [parentId] served by
 * [com.maxrave.media3.service.callback.SimpleMediaSessionCallback] through the
 * session's MediaBrowser, so browse content and click-to-play behavior stay
 * identical to the pre-template surface. Browsable children push another
 * instance of this screen; playable ones hand the item back to the session
 * and land on the playback screen.
 */
@UnstableApi
internal class MediaListCarScreen(
    carContext: CarContext,
    private val browserFuture: ListenableFuture<MediaBrowser>,
    private val parentId: String,
    private val screenTitle: String,
) : Screen(carContext),
    KoinComponent {
    private val handler: MediaPlayerHandler by inject()
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bitmapLoader = CoilBitmapLoader(carContext, screenScope)

    // null = still loading (template shows spinner); empty = loaded, nothing to show
    private var children: List<MediaItem>? = null
    private val artwork = mutableMapOf<String, CarIcon>()

    init {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    screenScope.cancel()
                }
            },
        )
        screenScope.launch {
            children =
                runCatching {
                    browserFuture
                        .await()
                        // The library callback returns whole lists (e.g. 1000 songs) and
                        // media3 rejects results larger than the requested pageSize
                        .getChildren(parentId, 0, Int.MAX_VALUE, null)
                        .await()
                        .value
                        ?.toList()
                }.onFailure {
                    Logger.e(TAG, "getChildren($parentId) failed: ${it.message}")
                }.getOrNull() ?: emptyList()
            invalidate()
            loadArtwork()
        }
        screenScope.launch {
            handler.nowPlaying.collect { invalidate() }
        }
    }

    private fun loadArtwork() {
        children.orEmpty().take(maxRows()).forEach { item ->
            val uri = item.mediaMetadata.artworkUri ?: return@forEach
            screenScope.launch {
                runCatching {
                    val bitmap = bitmapLoader.loadBitmap(uri).await()
                    artwork[item.mediaId] = CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
                    invalidate()
                }
            }
        }
    }

    private fun maxRows(): Int =
        runCatching {
            carContext
                .getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(DEFAULT_LIST_LIMIT)

    override fun onGetTemplate(): Template {
        val loaded = children
        val templateBuilder =
            ListTemplate
                .Builder()
                // Standard identity so the host draws the equalizer panel;
                // navigating on tap is the app's job via the listener
                .addAction(
                    Action
                        .Builder(Action.MEDIA_PLAYBACK)
                        .setOnClickListener {
                            screenManager.popToRoot()
                            screenManager.push(NowPlayingCarScreen(carContext))
                        }.build(),
                )
                .setHeader(
                    Header
                        .Builder()
                        .setStartHeaderAction(Action.BACK)
                        .setTitle(screenTitle)
                        .build(),
                )
        if (loaded == null) {
            return templateBuilder.setLoading(true).build()
        }
        val itemListBuilder =
            ItemList
                .Builder()
                .setNoItemsMessage("Nothing to show")
        loaded.take(maxRows()).forEach { item ->
            itemListBuilder.addItem(buildRow(item))
        }
        return templateBuilder.setSingleList(itemListBuilder.build()).build()
    }

    private fun buildRow(item: MediaItem): Row {
        val metadata = item.mediaMetadata
        val title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: item.mediaId
        val browsable = metadata.isBrowsable == true
        // Browse media ids are paths ("song/{videoId}"); nowPlaying uses the bare videoId
        val isPlaying =
            !browsable &&
                item.mediaId.substringAfterLast('/') == handler.nowPlaying.value?.mediaId
        return Row
            .Builder()
            .setTitle(title)
            .apply {
                val subtitle = metadata.subtitle?.toString().orEmpty()
                if (isPlaying) {
                    addText(carContext.nowPlayingText(subtitle))
                } else if (subtitle.isNotBlank()) {
                    addText(subtitle)
                }
                artwork[item.mediaId]?.let { setImage(it) }
                setBrowsable(browsable)
            }.setOnClickListener {
                if (browsable) {
                    // Host allows at most 5 stacked screens; the deepest classic
                    // path (library > home > shelf > playlist) plus playback fits
                    screenManager.push(MediaListCarScreen(carContext, browserFuture, item.mediaId, title))
                } else {
                    playItem(item)
                }
            }.build()
    }

    private fun playItem(item: MediaItem) {
        // Runs on the future's executor, not screenScope: navigating below pops
        // this screen and cancels its scope before a coroutine could finish
        browserFuture.addListener({
            runCatching {
                val browser = browserFuture.get()
                browser.setMediaItem(item)
                browser.prepare()
                browser.play()
            }.onFailure {
                Logger.e(TAG, "playItem(${item.mediaId}) failed: ${it.message}")
            }
        }, ContextCompat.getMainExecutor(carContext))
        // Land back on the playback screen, matching the classic surface
        screenManager.popToRoot()
        screenManager.push(NowPlayingCarScreen(carContext))
    }

    private companion object {
        private const val TAG = "MediaListCarScreen"
        private const val DEFAULT_LIST_LIMIT = 100
    }
}
