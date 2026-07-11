package org.simpmusic.lyrics.providers

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.maxOf

@Serializable
internal data class LrcLibTrack(
    val id: Int = 0,
    val trackName: String = "",
    val artistName: String = "",
    val duration: Double = 0.0,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

/**
 * LrcLib fuzzy-matching provider ported from ArchiveTune.
 */
object LrcLibProvider {
    private const val MAX_SEARCH_RESULTS = 5
    private const val MAX_DURATION_DELTA_SECONDS = 2

    private val client by lazy {
        buildLyricsHttpClient(expectSuccess = true)
    }

    private suspend fun queryLyrics(
        artist: String,
        title: String,
        album: String? = null,
    ): List<LrcLibTrack> =
        client
            .get("https://lrclib.net/api/search") {
                parameter("track_name", title)
                parameter("artist_name", artist)
                if (album != null) parameter("album_name", album)
            }.body<List<LrcLibTrack>>()
            .filter { track -> track.syncedLyrics.isUsableLyrics() || track.plainLyrics.isUsableLyrics() }

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
    ) = runCatching {
        val tracks = queryLyrics(artist, title, album)
        val lyrics =
            when {
                duration == -1 -> tracks.bestMatchingFor(duration, title, artist)?.preferredLyrics()
                else -> tracks.bestMatchingFor(duration)?.preferredLyrics()
            } ?: throw IllegalStateException("Lyrics unavailable")
        lyrics
    }

    suspend fun getAllLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
        callback: (String) -> Unit,
    ) {
        val tracks = queryLyrics(artist, title, album)
        var count = 0
        var emittedPlainLyrics = false

        val sortedTracks =
            when {
                duration == -1 -> {
                    tracks.sortedByDescending { track ->
                        var score = 0.0
                        if (track.syncedLyrics.isUsableLyrics()) score += 1.0
                        if (track.plainLyrics.isUsableLyrics()) score += 0.25
                        val titleSimilarity = calculateStringSimilarity(title, track.trackName)
                        val artistSimilarity = calculateStringSimilarity(artist, track.artistName)
                        score + (titleSimilarity + artistSimilarity) / 2.0
                    }
                }

                else -> tracks.sortedBy { track -> abs(track.duration.toInt() - duration) }
            }

        for (track in sortedTracks) {
            currentCoroutineContext().ensureActive()
            if (count >= MAX_SEARCH_RESULTS) return
            if (!track.matchesDuration(duration)) continue

            track.syncedLyrics?.takeIf(String::isNotBlank)?.let { lyrics ->
                callback(lyrics)
                count++
            }

            if (!emittedPlainLyrics && count < MAX_SEARCH_RESULTS) {
                track.plainLyrics?.takeIf(String::isNotBlank)?.let { lyrics ->
                    callback(lyrics)
                    count++
                    emittedPlainLyrics = true
                }
            }
        }
    }

    private fun LrcLibTrack.matchesDuration(duration: Int): Boolean =
        duration == -1 || abs(this.duration.toInt() - duration) <= MAX_DURATION_DELTA_SECONDS

    private fun LrcLibTrack.preferredLyrics(): String? =
        syncedLyrics.takeIf { it.isUsableLyrics() } ?: plainLyrics.takeIf { it.isUsableLyrics() }

    private fun String?.isUsableLyrics(): Boolean = !isNullOrBlank()

    private fun List<LrcLibTrack>.bestMatchingFor(duration: Int): LrcLibTrack? {
        if (isEmpty()) return null
        if (duration == -1) {
            return firstOrNull { it.syncedLyrics != null } ?: firstOrNull()
        }
        return minByOrNull { abs(it.duration.toInt() - duration) }
            ?.takeIf { abs(it.duration.toInt() - duration) <= 2 }
    }

    private fun List<LrcLibTrack>.bestMatchingFor(
        duration: Int,
        trackName: String? = null,
        artistName: String? = null,
    ): LrcLibTrack? {
        if (isEmpty()) return null
        if (duration == -1) {
            if (trackName != null && artistName != null) {
                return findBestMatch(trackName, artistName)
            }
            return firstOrNull { it.syncedLyrics != null } ?: firstOrNull()
        }
        return minByOrNull { abs(it.duration.toInt() - duration) }
            ?.takeIf { abs(it.duration.toInt() - duration) <= 2 }
    }

    private fun List<LrcLibTrack>.findBestMatch(
        trackName: String,
        artistName: String,
    ): LrcLibTrack? {
        val normalizedTrackName = trackName.trim().lowercase()
        val normalizedArtistName = artistName.trim().lowercase()

        return maxByOrNull { track ->
            val trackNameSimilarity = calculateStringSimilarity(normalizedTrackName, track.trackName.trim().lowercase())
            val artistNameSimilarity = calculateStringSimilarity(normalizedArtistName, track.artistName.trim().lowercase())
            var score = (trackNameSimilarity + artistNameSimilarity) / 2.0
            if (track.syncedLyrics != null) score += 0.1
            score
        }?.takeIf { track ->
            val trackNameSimilarity = calculateStringSimilarity(normalizedTrackName, track.trackName.trim().lowercase())
            val artistNameSimilarity = calculateStringSimilarity(normalizedArtistName, track.artistName.trim().lowercase())
            (trackNameSimilarity + artistNameSimilarity) / 2.0 > 0.6
        }
    }

    private fun calculateStringSimilarity(
        str1: String,
        str2: String,
    ): Double {
        val s1 = str1.trim().lowercase()
        val s2 = str2.trim().lowercase()
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        return when {
            s1.contains(s2) || s2.contains(s1) -> 0.8
            else -> {
                val maxLength = maxOf(s1.length, s2.length)
                val distance = levenshteinDistance(s1, s2)
                1.0 - (distance.toDouble() / maxLength)
            }
        }
    }

    private fun levenshteinDistance(
        str1: String,
        str2: String,
    ): Int {
        val len1 = str1.length
        val len2 = str2.length
        val matrix = Array(len1 + 1) { IntArray(len2 + 1) }
        for (i in 0..len1) matrix[i][0] = i
        for (j in 0..len2) matrix[0][j] = j
        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (str1[i - 1] == str2[j - 1]) 0 else 1
                matrix[i][j] =
                    minOf(
                        matrix[i - 1][j] + 1,
                        matrix[i][j - 1] + 1,
                        matrix[i - 1][j - 1] + cost,
                    )
            }
        }
        return matrix[len1][len2]
    }
}
