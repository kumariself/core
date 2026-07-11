package org.simpmusic.lyrics.providers

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class UnisonEntry(
    val id: Long,
    @SerialName("videoId") val videoId: String? = null,
    val song: String = "",
    val artist: String = "",
    val lyrics: String,
    val format: String = "",
    val syncType: String = "",
    val score: Double = 0.0,
    val effectiveScore: Double = 0.0,
    val voteCount: Int = 0,
    val confidence: String = "low",
    val language: String? = null,
)

@Serializable
internal data class UnisonSearchEntry(
    val id: Long,
    @SerialName("videoId") val videoId: String? = null,
    val song: String = "",
    val artist: String = "",
    val lyrics: String? = null,
    val format: String = "",
    val syncType: String = "",
    val score: Double = 0.0,
    val effectiveScore: Double = 0.0,
    val voteCount: Int = 0,
    val confidence: String = "low",
    val language: String? = null,
) {
    fun toEntry(): UnisonEntry? {
        val nonBlankLyrics = lyrics?.takeIf { it.isNotBlank() } ?: return null
        return UnisonEntry(
            id = id,
            videoId = videoId,
            song = song,
            artist = artist,
            lyrics = nonBlankLyrics,
            format = format,
            syncType = syncType,
            score = score,
            effectiveScore = effectiveScore,
            voteCount = voteCount,
            confidence = confidence,
            language = language,
        )
    }
}

@Serializable
internal data class UnisonResponse(
    val success: Boolean = false,
    val data: UnisonEntry? = null,
)

@Serializable
internal data class UnisonSearchResponse(
    val success: Boolean = false,
    val data: List<UnisonSearchEntry>? = null,
)

/**
 * Unison lyrics provider (https://unison.boidu.dev), ported from ArchiveTune to KMP.
 */
object Unison {
    private const val API_BASE_URL = "https://unison.boidu.dev/"
    private const val MAX_SEARCH_RESULTS = 5

    private val client by lazy {
        buildLyricsHttpClient(withContentNegotiation = false)
    }

    private suspend fun fetchEntry(
        videoId: String?,
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int,
    ): UnisonEntry? {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isBlank() || cleanArtist.isBlank()) return null

        if (!videoId.isNullOrBlank()) {
            val byId = fetchByVideoId(videoId)
            if (byId != null) return byId
        }
        return fetchByMetadata(cleanTitle, cleanArtist, album?.trim(), durationSeconds)
    }

    private suspend fun fetchByVideoId(videoId: String): UnisonEntry? =
        try {
            val response =
                client.get("${API_BASE_URL}lyrics") {
                    parameter("v", videoId)
                }
            if (!response.status.isSuccess()) {
                null
            } else {
                val body = response.bodyAsText()
                runCatching { ProviderDefaults.json.decodeFromString<UnisonResponse>(body) }
                    .getOrNull()
                    ?.takeIf { it.success }
                    ?.data
                    ?.takeIf { it.lyrics.isNotBlank() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logProvider("Unison videoId fetch error: ${e.message}")
            null
        }

    private suspend fun fetchById(id: Long): UnisonEntry? =
        try {
            val response = client.get("${API_BASE_URL}lyrics/$id")
            if (!response.status.isSuccess()) {
                null
            } else {
                val body = response.bodyAsText()
                runCatching { ProviderDefaults.json.decodeFromString<UnisonResponse>(body) }
                    .getOrNull()
                    ?.takeIf { it.success }
                    ?.data
                    ?.takeIf { it.lyrics.isNotBlank() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logProvider("Unison ID fetch error: ${e.message}")
            null
        }

    private suspend fun fetchByMetadata(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int,
    ): UnisonEntry? =
        try {
            val response =
                client.get("${API_BASE_URL}lyrics") {
                    parameter("song", title)
                    parameter("artist", artist)
                    if (!album.isNullOrBlank()) parameter("album", album)
                    if (durationSeconds > 0) parameter("duration", durationSeconds)
                }
            if (!response.status.isSuccess()) {
                null
            } else {
                val body = response.bodyAsText()
                runCatching { ProviderDefaults.json.decodeFromString<UnisonResponse>(body) }
                    .getOrNull()
                    ?.takeIf { it.success }
                    ?.data
                    ?.takeIf { it.lyrics.isNotBlank() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logProvider("Unison metadata fetch error: ${e.message}")
            null
        }

    private suspend fun searchEntries(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int,
    ): List<UnisonEntry> {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isBlank() || cleanArtist.isBlank()) return emptyList()

        return try {
            val response =
                client.get("${API_BASE_URL}lyrics/search") {
                    parameter("song", cleanTitle)
                    parameter("artist", cleanArtist)
                    if (!album.isNullOrBlank()) parameter("album", album.trim())
                    if (durationSeconds > 0) parameter("duration", durationSeconds)
                }
            if (!response.status.isSuccess()) {
                emptyList()
            } else {
                val body = response.bodyAsText()
                val summaries =
                    runCatching { ProviderDefaults.json.decodeFromString<UnisonSearchResponse>(body) }
                        .getOrNull()
                        ?.takeIf { it.success }
                        ?.data
                        .orEmpty()
                val entries = mutableListOf<UnisonEntry>()
                for (summary in summaries) {
                    currentCoroutineContext().ensureActive()
                    if (entries.size >= MAX_SEARCH_RESULTS) break
                    val entry = summary.toEntry() ?: fetchById(summary.id)
                    if (entry != null) entries += entry
                }
                entries
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logProvider("Unison search fetch error: ${e.message}")
            emptyList()
        }
    }

    suspend fun getLyrics(
        videoId: String? = null,
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int = -1,
    ): Result<String> =
        runCatching {
            require(title.isNotBlank() && artist.isNotBlank()) { "Song title and artist are required" }
            val entry =
                fetchEntry(videoId, title, artist, album, durationSeconds)
                    ?: throw IllegalStateException("Lyrics unavailable")
            entry.lyrics
        }

    suspend fun getAllLyrics(
        videoId: String? = null,
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int = -1,
        callback: (String) -> Unit,
    ) {
        val results = searchEntries(title, artist, album, durationSeconds)
        var count = 0
        for (entry in results) {
            currentCoroutineContext().ensureActive()
            if (count >= MAX_SEARCH_RESULTS) break
            callback(entry.lyrics)
            count++
        }
        if (count == 0) {
            currentCoroutineContext().ensureActive()
            val single = fetchEntry(videoId, title, artist, album, durationSeconds)
            if (single != null) callback(single.lyrics)
        }
    }
}
