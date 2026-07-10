package com.maxrave.media3.carapp

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Search screen for the Android Auto templated surface, backed by the same
 * [com.maxrave.media3.service.callback.SimpleMediaSessionCallback] search
 * pipeline the classic voice/browse search used — MediaBrowser.search()
 * triggers onSearch, then getSearchResult() returns the collected tracks.
 * Searching starts on submit only: every query hits the YouTube scraper.
 */
@UnstableApi
internal class SearchCarScreen(
    carContext: CarContext,
    private val browserFuture: ListenableFuture<MediaBrowser>,
) : Screen(carContext),
    KoinComponent {
    private val handler: MediaPlayerHandler by inject()
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bitmapLoader = CoilBitmapLoader(carContext, screenScope)

    // null = nothing searched yet; empty = search finished without results
    private var results: List<MediaItem>? = null
    private var searching = false
    private var searchJob: Job? = null
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
            handler.nowPlaying.collect { invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        val builder =
            SearchTemplate
                .Builder(
                    object : SearchTemplate.SearchCallback {
                        override fun onSearchTextChanged(searchText: String) {
                            // Intentionally empty — searching only on submit
                        }

                        override fun onSearchSubmitted(searchText: String) {
                            submitSearch(searchText)
                        }
                    },
                ).setHeaderAction(Action.BACK)
                .setSearchHint("Search songs")
                .setShowKeyboardByDefault(results == null && !searching)
        if (searching) {
            return builder.setLoading(true).build()
        }
        results?.let { items ->
            val itemListBuilder = ItemList.Builder().setNoItemsMessage("No results")
            items.take(maxRows()).forEach { itemListBuilder.addItem(buildRow(it)) }
            builder.setItemList(itemListBuilder.build())
        }
        return builder.build()
    }

    private fun submitSearch(query: String) {
        if (query.isBlank()) return
        searching = true
        invalidate()
        searchJob?.cancel()
        searchJob =
            screenScope.launch {
                val items =
                    runCatching {
                        val browser = browserFuture.await()
                        browser.search(query, null).await()
                        browser
                            .getSearchResult(query, 0, Int.MAX_VALUE, null)
                            .await()
                            .value
                            ?.toList()
                    }.onFailure {
                        Logger.e(TAG, "search($query) failed: ${it.message}")
                    }.getOrNull() ?: emptyList()
                searching = false
                results = items
                invalidate()
                loadArtwork(items)
            }
    }

    private fun loadArtwork(items: List<MediaItem>) {
        items.take(maxRows()).forEach { item ->
            val uri = item.mediaMetadata.artworkUri ?: return@forEach
            if (artwork.containsKey(item.mediaId)) return@forEach
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

    private fun buildRow(item: MediaItem): Row {
        val metadata = item.mediaMetadata
        val title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: item.mediaId
        val isPlaying = item.mediaId.substringAfterLast('/') == handler.nowPlaying.value?.mediaId
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
            }.setOnClickListener {
                playItem(item)
            }.build()
    }

    private fun playItem(item: MediaItem) {
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
        private const val TAG = "SearchCarScreen"
        private const val DEFAULT_LIST_LIMIT = 100
    }
}
