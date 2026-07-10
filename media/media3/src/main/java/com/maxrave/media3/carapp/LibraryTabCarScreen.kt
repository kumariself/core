package com.maxrave.media3.carapp

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import com.google.common.util.concurrent.ListenableFuture
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.logger.Logger
import com.maxrave.media3.R
import com.maxrave.media3.service.callback.SimpleMediaSessionCallback
import com.maxrave.media3.utils.CoilBitmapLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Library root recreating the classic Android Auto tab bar (Home / Downloaded /
 * Favorites / Playlists). Each tab renders the same children the classic
 * browse tree served through [SimpleMediaSessionCallback]; browsable rows
 * push [MediaListCarScreen] for deeper levels.
 */
@UnstableApi
internal class LibraryTabCarScreen(
    carContext: CarContext,
    private val browserProvider: () -> ListenableFuture<MediaBrowser>,
) : Screen(carContext),
    KoinComponent {
    private val handler: MediaPlayerHandler by inject()
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bitmapLoader = CoilBitmapLoader(carContext, screenScope)

    private var activeTabId: String = SimpleMediaSessionCallback.HOME

    // key absent = not requested yet; null value = loading; list = loaded
    private val tabChildren = mutableMapOf<String, List<MediaItem>?>()

    // This screen lives for the whole car session — a transient failure must
    // not leave a tab empty forever, so re-selecting a failed tab retries
    private val failedTabs = mutableSetOf<String>()
    private val artwork = mutableMapOf<String, CarIcon>()

    init {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    screenScope.cancel()
                }
            },
        )
        loadTab(activeTabId)
        screenScope.launch {
            handler.nowPlaying.collect { invalidate() }
        }
    }

    private fun loadTab(tabId: String) {
        if (tabChildren.containsKey(tabId)) return
        tabChildren[tabId] = null
        screenScope.launch {
            val items =
                try {
                    browserProvider()
                        .await()
                        // The library callback returns whole lists and media3 rejects
                        // results larger than the requested pageSize
                        .getChildren(tabId, 0, Int.MAX_VALUE, null)
                        .await()
                        .value
                        ?.toList()
                        ?: emptyList()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e(TAG, "getChildren($tabId) failed: ${e.message}")
                    failedTabs.add(tabId)
                    emptyList()
                }
            tabChildren[tabId] = items
            invalidate()
            loadArtworkInto(screenScope, bitmapLoader, items, carContext.listContentLimit(), artwork, TAG) {
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val templateBuilder =
            TabTemplate
                .Builder(
                    object : TabTemplate.TabCallback {
                        override fun onTabSelected(tabContentId: String) {
                            if (failedTabs.remove(tabContentId)) {
                                tabChildren.remove(tabContentId)
                            }
                            activeTabId = tabContentId
                            loadTab(tabContentId)
                            invalidate()
                        }
                    },
                ).setHeaderAction(Action.APP_ICON)
        TABS.forEach { tab ->
            templateBuilder.addTab(
                Tab
                    .Builder()
                    .setContentId(tab.contentId)
                    .setTitle(carContext.getString(tab.titleRes))
                    .setIcon(
                        CarIcon
                            .Builder(IconCompat.createWithResource(carContext, tab.iconRes))
                            .build(),
                    ).build(),
            )
        }
        return templateBuilder
            .setTabContents(TabContents.Builder(buildActiveTabList()).build())
            .setActiveTabContentId(activeTabId)
            .build()
    }

    private fun buildActiveTabList(): ListTemplate {
        val listBuilder =
            ListTemplate
                .Builder()
                // List templates allow up to 2 floating actions: search on top
                // of the equalizer panel (MFT-1)
                .addAction(
                    Action
                        .Builder()
                        .setIcon(
                            CarIcon
                                .Builder(IconCompat.createWithResource(carContext, R.drawable.ic_car_search))
                                .build(),
                        )
                        // FAB constraint: custom actions must declare a background color
                        .setBackgroundColor(CarColor.PRIMARY)
                        .setOnClickListener {
                            screenManager.push(SearchCarScreen(carContext, browserProvider))
                        }.build(),
                ).addAction(
                    // Standard identity so the host draws the equalizer panel;
                    // navigating on tap is the app's job via the listener
                    Action
                        .Builder(Action.MEDIA_PLAYBACK)
                        .setOnClickListener {
                            screenManager.push(NowPlayingCarScreen(carContext))
                        }.build(),
                )
        val children = tabChildren[activeTabId] ?: return listBuilder.setLoading(true).build()
        val nowPlayingId = handler.nowPlaying.value?.mediaId
        val itemListBuilder = ItemList.Builder().setNoItemsMessage("Nothing to show")
        children.take(carContext.listContentLimit()).forEach { item ->
            val browsable = item.mediaMetadata.isBrowsable == true
            // Browse media ids are paths ("song/{videoId}"); nowPlaying uses the bare videoId
            val isPlaying = !browsable && item.mediaId.substringAfterLast('/') == nowPlayingId
            itemListBuilder.addItem(
                carContext.mediaRow(item, artwork[item.mediaId], isPlaying) {
                    if (browsable) {
                        val title = item.mediaMetadata.title?.toString().orEmpty().ifBlank { item.mediaId }
                        screenManager.push(MediaListCarScreen(carContext, browserProvider, item.mediaId, title))
                    } else {
                        playViaBrowser(carContext, browserProvider(), item, TAG)
                        // This screen is the stack root, so the playback screen goes on top
                        screenManager.push(NowPlayingCarScreen(carContext))
                    }
                },
            )
        }
        return listBuilder.setSingleList(itemListBuilder.build()).build()
    }

    private data class LibraryTab(
        val contentId: String,
        val titleRes: Int,
        val iconRes: Int,
    )

    private companion object {
        private const val TAG = "LibraryTabCarScreen"
        private val TABS =
            listOf(
                LibraryTab(SimpleMediaSessionCallback.HOME, R.string.home, R.drawable.home_android_auto),
                LibraryTab(SimpleMediaSessionCallback.DOWNLOADED, R.string.downloaded, R.drawable.baseline_downloaded),
                LibraryTab(SimpleMediaSessionCallback.FAVORITE, R.string.favorites, R.drawable.baseline_favorite_24),
                LibraryTab(SimpleMediaSessionCallback.PLAYLIST, R.string.playlists, R.drawable.baseline_playlist_add_24),
            )
    }
}
