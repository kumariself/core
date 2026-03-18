package com.maxrave.media3.exoplayer

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.maxrave.logger.Logger

private const val TAG = "DelegatingForwardingPlayer"

/**
 * A [ForwardingPlayer] that allows runtime swapping of its underlying delegate.
 *
 * Used for MediaSession integration - provides a stable [Player] reference while
 * [CrossfadeExoPlayerAdapter] cycles through different ExoPlayer instances.
 *
 * Pattern: Track all externally added listeners, then during swap:
 * 1. Remove them via [ForwardingPlayer.removeListener] (cleans up ForwardingListener wrappers)
 * 2. Swap the internal `player` field via reflection
 * 3. Re-add them via [ForwardingPlayer.addListener] (creates new ForwardingListener wrappers for new delegate)
 *
 * Additionally, since each underlying ExoPlayer only has a single MediaItem,
 * playlist navigation methods (hasNext/hasPrevious, seek, mediaItemCount, etc.)
 * are overridden to delegate to [PlaylistNavigationProvider] — which is backed by
 * [CrossfadeExoPlayerAdapter]'s internal playlist. This ensures MediaSession reports
 * correct available commands (SEEK_TO_NEXT, SEEK_TO_PREVIOUS) and shows
 * next/previous buttons in the system notification.
 */
@UnstableApi
internal class DelegatingForwardingPlayer(
    initialDelegate: Player,
) : ForwardingPlayer(initialDelegate) {
    companion object {
        private val PLAYER_FIELD: java.lang.reflect.Field? =
            try {
                ForwardingPlayer::class.java.getDeclaredField("player").apply {
                    isAccessible = true
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to access ForwardingPlayer.player field", e)
                null
            }
    }

    // ========== Playlist Navigation Provider ==========

    /**
     * Provides playlist-level navigation information to the ForwardingPlayer.
     *
     * Each underlying ExoPlayer only has 1 MediaItem, so ExoPlayer's own
     * hasNextMediaItem()/hasPreviousMediaItem() always return false.
     * This provider bridges the gap, letting MediaSession see the full playlist state.
     */
    interface PlaylistNavigationProvider {
        fun hasNextMediaItem(): Boolean

        fun hasPreviousMediaItem(): Boolean

        fun seekToNext()

        fun seekToPrevious()

        /** Returns the full playlist as Media3 MediaItems for timeline/queue display. */
        fun getPlaylistMediaItems(): List<MediaItem>

        /** Returns the current item index within the playlist. */
        fun getCurrentPlaylistIndex(): Int
    }

    /**
     * Set by [CrossfadeExoPlayerAdapter] to provide playlist navigation.
     * When null, all navigation methods fall back to the underlying ExoPlayer (single-item behavior).
     */
    var playlistNavigationProvider: PlaylistNavigationProvider? = null

    /**
     * Handler for intercepting setMediaItems() calls from MediaSession (e.g., Android Auto).
     * When set, media items are redirected to [CrossfadeExoPlayerAdapter] instead of the
     * underlying single-item ExoPlayer, keeping the adapter's internal playlist in sync.
     */
    var setMediaItemsHandler: ((items: List<MediaItem>, startIndex: Int, startPositionMs: Long) -> Unit)? = null

    // ========== Playlist Timeline for MediaSession/Android Auto ==========

    /**
     * A [Timeline] that represents the full playlist from [CrossfadeExoPlayerAdapter].
     *
     * Each playlist item becomes a window in the timeline. The current item's window
     * uses real playback data (duration, seekability) from the underlying ExoPlayer.
     * Other items get placeholder data.
     *
     * This allows MediaSession to expose the full queue to Android Auto and other controllers,
     * even though each underlying ExoPlayer only has a single MediaItem.
     */
    private class PlaylistTimeline(
        private val mediaItems: List<MediaItem>,
        private val currentIndex: Int,
        private val underlyingTimeline: Timeline,
    ) : Timeline() {
        override fun getWindowCount(): Int = mediaItems.size

        override fun getWindow(
            windowIndex: Int,
            window: Window,
            defaultPositionProjectionUs: Long,
        ): Window {
            val isCurrentWindow = windowIndex == currentIndex
            val mediaItem = mediaItems.getOrElse(windowIndex) { MediaItem.EMPTY }

            if (isCurrentWindow && underlyingTimeline.windowCount > 0) {
                // Get real playback data from the underlying single-item ExoPlayer
                val tempWindow = Window()
                underlyingTimeline.getWindow(0, tempWindow, defaultPositionProjectionUs)

                window.set(
                    /* uid = */ windowIndex,
                    /* mediaItem = */ mediaItem,
                    /* manifest = */ null,
                    /* presentationStartTimeMs = */ C.TIME_UNSET,
                    /* windowStartTimeMs = */ C.TIME_UNSET,
                    /* elapsedRealtimeEpochOffsetMs = */ C.TIME_UNSET,
                    /* isSeekable = */ tempWindow.isSeekable,
                    /* isDynamic = */ false,
                    /* liveConfiguration = */ null,
                    /* defaultPositionUs = */ tempWindow.defaultPositionUs,
                    /* durationUs = */ tempWindow.durationUs,
                    /* firstPeriodIndex = */ windowIndex,
                    /* lastPeriodIndex = */ windowIndex,
                    /* positionInFirstPeriodUs = */ 0L,
                )
            } else {
                window.set(
                    /* uid = */ windowIndex,
                    /* mediaItem = */ mediaItem,
                    /* manifest = */ null,
                    /* presentationStartTimeMs = */ C.TIME_UNSET,
                    /* windowStartTimeMs = */ C.TIME_UNSET,
                    /* elapsedRealtimeEpochOffsetMs = */ C.TIME_UNSET,
                    /* isSeekable = */ true,
                    /* isDynamic = */ false,
                    /* liveConfiguration = */ null,
                    /* defaultPositionUs = */ 0L,
                    /* durationUs = */ C.TIME_UNSET,
                    /* firstPeriodIndex = */ windowIndex,
                    /* lastPeriodIndex = */ windowIndex,
                    /* positionInFirstPeriodUs = */ 0L,
                )
            }

            return window
        }

        override fun getPeriodCount(): Int = mediaItems.size

        override fun getPeriod(
            periodIndex: Int,
            period: Period,
            setIds: Boolean,
        ): Period {
            val isCurrentPeriod = periodIndex == currentIndex
            val durationUs =
                if (isCurrentPeriod && underlyingTimeline.windowCount > 0) {
                    val tempPeriod = Period()
                    underlyingTimeline.getPeriod(0, tempPeriod, false)
                    tempPeriod.durationUs
                } else {
                    C.TIME_UNSET
                }

            period.set(
                /* id = */ if (setIds) periodIndex else null,
                /* uid = */ if (setIds) periodIndex else null,
                /* windowIndex = */ periodIndex,
                /* durationUs = */ durationUs,
                /* positionInWindowUs = */ 0L,
            )
            return period
        }

        override fun getIndexOfPeriod(uid: Any): Int =
            if (uid is Int && uid in mediaItems.indices) uid else C.INDEX_UNSET

        override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex

        override fun getFirstWindowIndex(shuffleModeEnabled: Boolean): Int =
            if (mediaItems.isEmpty()) C.INDEX_UNSET else 0

        override fun getLastWindowIndex(shuffleModeEnabled: Boolean): Int =
            if (mediaItems.isEmpty()) C.INDEX_UNSET else mediaItems.size - 1
    }

    // ========== Listener Tracking ==========

    // Track all externally registered listeners so we can re-register them after delegate swap
    private val trackedListeners = mutableListOf<Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        trackedListeners.add(listener)
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        trackedListeners.remove(listener)
        super.removeListener(listener)
    }

    // ========== Video Surface Tracking ==========
    // Track the current video output so it can be re-attached when the delegate is swapped.
    // Without this, video stops rendering after a delegate swap because the new ExoPlayer
    // instance never receives setVideoSurfaceView/setVideoSurface/etc.

    private sealed class VideoOutput {
        data class SurfaceViewOutput(val surfaceView: SurfaceView) : VideoOutput()

        data class TextureViewOutput(val textureView: TextureView) : VideoOutput()

        data class SurfaceOutput(val surface: Surface) : VideoOutput()

        data class SurfaceHolderOutput(val surfaceHolder: SurfaceHolder) : VideoOutput()
    }

    private var currentVideoOutput: VideoOutput? = null

    override fun setVideoSurfaceView(surfaceView: SurfaceView?) {
        currentVideoOutput = surfaceView?.let { VideoOutput.SurfaceViewOutput(it) }
        super.setVideoSurfaceView(surfaceView)
    }

    override fun setVideoTextureView(textureView: TextureView?) {
        currentVideoOutput = textureView?.let { VideoOutput.TextureViewOutput(it) }
        super.setVideoTextureView(textureView)
    }

    override fun setVideoSurface(surface: Surface?) {
        currentVideoOutput = surface?.let { VideoOutput.SurfaceOutput(it) }
        super.setVideoSurface(surface)
    }

    override fun setVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        currentVideoOutput = surfaceHolder?.let { VideoOutput.SurfaceHolderOutput(it) }
        super.setVideoSurfaceHolder(surfaceHolder)
    }

    override fun clearVideoSurface() {
        currentVideoOutput = null
        super.clearVideoSurface()
    }

    override fun clearVideoSurface(surface: Surface?) {
        if (currentVideoOutput is VideoOutput.SurfaceOutput &&
            (currentVideoOutput as VideoOutput.SurfaceOutput).surface === surface
        ) {
            currentVideoOutput = null
        }
        super.clearVideoSurface(surface)
    }

    override fun clearVideoSurfaceView(surfaceView: SurfaceView?) {
        if (currentVideoOutput is VideoOutput.SurfaceViewOutput &&
            (currentVideoOutput as VideoOutput.SurfaceViewOutput).surfaceView === surfaceView
        ) {
            currentVideoOutput = null
        }
        super.clearVideoSurfaceView(surfaceView)
    }

    override fun clearVideoTextureView(textureView: TextureView?) {
        if (currentVideoOutput is VideoOutput.TextureViewOutput &&
            (currentVideoOutput as VideoOutput.TextureViewOutput).textureView === textureView
        ) {
            currentVideoOutput = null
        }
        super.clearVideoTextureView(textureView)
    }

    override fun clearVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        if (currentVideoOutput is VideoOutput.SurfaceHolderOutput &&
            (currentVideoOutput as VideoOutput.SurfaceHolderOutput).surfaceHolder === surfaceHolder
        ) {
            currentVideoOutput = null
        }
        super.clearVideoSurfaceHolder(surfaceHolder)
    }

    /**
     * Clear video output from a specific player instance.
     * Must be called on the OLD delegate before swapping, so the native surface
     * is disconnected from the old MediaCodec before the new player tries to connect.
     */
    private fun clearVideoOutputFromPlayer(player: Player) {
        if (currentVideoOutput != null) {
            Logger.d(TAG, "Clearing video surface from old delegate before swap")
            try {
                player.clearVideoSurface()
            } catch (e: Exception) {
                Logger.w(TAG, "Error clearing video surface from old delegate: ${e.message}")
            }
        }
    }

    /**
     * Re-attach the tracked video output to the current delegate.
     * Called after [swapDelegate] to ensure video continues rendering on the new ExoPlayer.
     */
    private fun reAttachVideoOutput() {
        when (val output = currentVideoOutput) {
            is VideoOutput.SurfaceViewOutput -> {
                Logger.d(TAG, "Re-attaching SurfaceView to new delegate")
                wrappedPlayer.setVideoSurfaceView(output.surfaceView)
            }
            is VideoOutput.TextureViewOutput -> {
                Logger.d(TAG, "Re-attaching TextureView to new delegate")
                wrappedPlayer.setVideoTextureView(output.textureView)
            }
            is VideoOutput.SurfaceOutput -> {
                Logger.d(TAG, "Re-attaching Surface to new delegate")
                wrappedPlayer.setVideoSurface(output.surface)
            }
            is VideoOutput.SurfaceHolderOutput -> {
                Logger.d(TAG, "Re-attaching SurfaceHolder to new delegate")
                wrappedPlayer.setVideoSurfaceHolder(output.surfaceHolder)
            }
            null -> {
                // No video output to re-attach
            }
        }
    }

    // ========== Playlist Navigation Overrides ==========

    override fun getAvailableCommands(): Player.Commands {
        val baseCommands = super.getAvailableCommands()
        val nav = playlistNavigationProvider ?: return baseCommands

        val builder = baseCommands.buildUpon()

        // Always add seek-to-previous (allows seek to start of track even if no previous item)
        builder.add(Player.COMMAND_SEEK_TO_PREVIOUS)

        if (nav.hasNextMediaItem()) {
            builder.add(Player.COMMAND_SEEK_TO_NEXT)
            builder.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }
        if (nav.hasPreviousMediaItem()) {
            builder.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        }

        return builder.build()
    }

    override fun isCommandAvailable(command: Int): Boolean {
        val nav = playlistNavigationProvider
        if (nav != null) {
            when (command) {
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
                    return nav.hasNextMediaItem()
                Player.COMMAND_SEEK_TO_PREVIOUS ->
                    return true // Always allow seeking to start of current track
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                    return nav.hasPreviousMediaItem()
            }
        }
        return super.isCommandAvailable(command)
    }

    override fun hasNextMediaItem(): Boolean =
        playlistNavigationProvider?.hasNextMediaItem() ?: super.hasNextMediaItem()

    override fun hasPreviousMediaItem(): Boolean =
        playlistNavigationProvider?.hasPreviousMediaItem() ?: super.hasPreviousMediaItem()

    override fun seekToNext() {
        val nav = playlistNavigationProvider
        if (nav != null) {
            nav.seekToNext()
        } else {
            super.seekToNext()
        }
    }

    override fun seekToPrevious() {
        val nav = playlistNavigationProvider
        if (nav != null) {
            nav.seekToPrevious()
        } else {
            super.seekToPrevious()
        }
    }

    override fun seekToNextMediaItem() {
        val nav = playlistNavigationProvider
        if (nav != null) {
            nav.seekToNext()
        } else {
            super.seekToNextMediaItem()
        }
    }

    override fun seekToPreviousMediaItem() {
        val nav = playlistNavigationProvider
        if (nav != null) {
            nav.seekToPrevious()
        } else {
            super.seekToPreviousMediaItem()
        }
    }

    // ========== Timeline Overrides for Android Auto Queue ==========
    // With PlaylistTimeline, we CAN now override these because the timeline window count
    // matches the playlist size, so currentMediaItemIndex < timeline.windowCount is always true.

    override fun getCurrentTimeline(): Timeline {
        val nav = playlistNavigationProvider ?: return super.getCurrentTimeline()
        val items = nav.getPlaylistMediaItems()
        if (items.isEmpty()) return super.getCurrentTimeline()

        val currentIndex = nav.getCurrentPlaylistIndex().coerceIn(0, items.size - 1)
        return PlaylistTimeline(items, currentIndex, super.getCurrentTimeline())
    }

    override fun getMediaItemCount(): Int =
        playlistNavigationProvider?.getPlaylistMediaItems()?.size
            ?: super.getMediaItemCount()

    override fun getCurrentMediaItemIndex(): Int {
        val nav = playlistNavigationProvider ?: return super.getCurrentMediaItemIndex()
        val items = nav.getPlaylistMediaItems()
        if (items.isEmpty()) return super.getCurrentMediaItemIndex()
        return nav.getCurrentPlaylistIndex().coerceIn(0, items.size - 1)
    }

    override fun getCurrentPeriodIndex(): Int = getCurrentMediaItemIndex()

    override fun getMediaItemAt(index: Int): MediaItem {
        val nav = playlistNavigationProvider ?: return super.getMediaItemAt(index)
        val items = nav.getPlaylistMediaItems()
        return if (index in items.indices) items[index] else super.getMediaItemAt(index)
    }

    // ========== setMediaItems Interception for Android Auto ==========
    // When MediaSession (Android Auto) calls setMediaItems(), redirect to the adapter
    // so its internal playlist stays in sync with what Android Auto expects.

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startMediaItemIndex: Int, startPositionMs: Long) {
        val handler = setMediaItemsHandler
        if (handler != null) {
            handler(mediaItems, startMediaItemIndex, startPositionMs)
        } else {
            super.setMediaItems(mediaItems, startMediaItemIndex, startPositionMs)
        }
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
        setMediaItems(mediaItems, 0, C.TIME_UNSET)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        if (resetPosition) {
            setMediaItems(mediaItems, 0, C.TIME_UNSET)
        } else {
            setMediaItems(mediaItems, getCurrentMediaItemIndex(), contentPosition)
        }
    }

    override fun setMediaItem(mediaItem: MediaItem) {
        setMediaItems(mutableListOf(mediaItem), 0, C.TIME_UNSET)
    }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        setMediaItems(mutableListOf(mediaItem), 0, startPositionMs)
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        if (resetPosition) {
            setMediaItems(mutableListOf(mediaItem), 0, C.TIME_UNSET)
        } else {
            setMediaItems(mutableListOf(mediaItem), 0, contentPosition)
        }
    }

    // ========== Delegate Swap ==========

    /**
     * Swap the underlying delegate player.
     *
     * This properly migrates all registered listeners (e.g., MediaSession's listener)
     * from the old delegate to the new one.
     */
    fun swapDelegate(newDelegate: Player) {
        if (wrappedPlayer === newDelegate) return

        val field = PLAYER_FIELD
            ?: throw IllegalStateException("Cannot swap delegate - reflection on ForwardingPlayer.player field failed")

        // 1. Snapshot current listeners
        val listenersToReAdd = trackedListeners.toList()

        // 2. Clear video surface from OLD delegate BEFORE swapping.
        //    The native surface can only be connected to one MediaCodec at a time.
        //    If we don't clear it here, the new player's MediaCodec.setSurface() will fail
        //    with "already connected" → IllegalArgumentException crash.
        clearVideoOutputFromPlayer(wrappedPlayer)

        // 3. Remove all listeners from old delegate (ForwardingPlayer removes ForwardingListener wrappers)
        listenersToReAdd.forEach { listener ->
            try {
                super.removeListener(listener)
            } catch (e: Exception) {
                Logger.w(TAG, "Error removing listener during swap: ${e.message}")
            }
        }

        // 4. Swap the private final `player` field
        field.set(this, newDelegate)

        // 5. Re-add all listeners (ForwardingPlayer creates new ForwardingListener wrappers for new delegate)
        listenersToReAdd.forEach { listener ->
            super.addListener(listener)
        }

        // 6. Re-attach video surface to the new delegate
        //    Without this, video stops rendering because the new ExoPlayer
        //    never received setVideoSurfaceView/setVideoSurface/etc.
        reAttachVideoOutput()

        // 6. Verify
        if (wrappedPlayer !== newDelegate) {
            Logger.e(TAG, "Delegate swap verification FAILED - wrappedPlayer is not the new delegate!")
        } else {
            Logger.d(TAG, "Delegate swapped successfully")
        }
    }

    // ========== Manual Event Dispatch ==========

    /**
     * Manually notify all tracked listeners about a media item change.
     *
     * This is needed after [swapDelegate] because the new delegate may already have
     * a MediaItem set and be playing — meaning listeners missed the initial
     * [Player.Listener.onMediaItemTransition] and [Player.Listener.onMediaMetadataChanged] events.
     *
     * Also dispatches [Player.Listener.onAvailableCommandsChanged] so MediaSession
     * re-evaluates which notification buttons to show (next/previous).
     *
     * Primary use case: crossfade transitions, where the secondary player is prepared
     * (with MediaItem + prepare()) before the ForwardingPlayer is swapped to it.
     * MediaSession uses these events to update the system notification metadata.
     */
    fun notifyMediaItemChanged() {
        val player = wrappedPlayer
        val mediaItem = player.currentMediaItem ?: MediaItem.EMPTY
        val metadata = player.mediaMetadata
        val commands = getAvailableCommands()
        val timeline = getCurrentTimeline()

        Logger.d(TAG, "Manually notifying ${trackedListeners.size} listeners about media item change: ${metadata.title}")

        trackedListeners.forEach { listener ->
            try {
                listener.onTimelineChanged(timeline, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
                listener.onMediaItemTransition(mediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                listener.onMediaMetadataChanged(metadata)
                listener.onAvailableCommandsChanged(commands)
            } catch (e: Exception) {
                Logger.w(TAG, "Error notifying listener about media item change: ${e.message}")
            }
        }
    }

    /**
     * Notify all tracked listeners that the playlist structure has changed.
     * Called when items are added, removed, or reordered in [CrossfadeExoPlayerAdapter]'s playlist.
     * This updates the queue display in Android Auto and other MediaSession controllers.
     */
    fun notifyPlaylistChanged() {
        val timeline = getCurrentTimeline()
        val commands = getAvailableCommands()

        Logger.d(TAG, "Notifying ${trackedListeners.size} listeners about playlist change (${timeline.windowCount} items)")

        trackedListeners.forEach { listener ->
            try {
                listener.onTimelineChanged(timeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
                listener.onAvailableCommandsChanged(commands)
            } catch (e: Exception) {
                Logger.w(TAG, "Error notifying listener about playlist change: ${e.message}")
            }
        }
    }
}
