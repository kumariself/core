package org.simpmusic.lyrics.providers

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

@Serializable
internal data class YouLyPlusTtmlResponse(
    val ttml: String? = null,
)

@Serializable
internal data class YouLyPlusLyricsResponse(
    val type: String? = null,
    val lyrics: List<YouLyPlusLine> = emptyList(),
)

@Serializable
internal data class YouLyPlusLine(
    val time: Long? = null,
    val duration: Long? = null,
    val text: String? = null,
    val syllabus: List<YouLyPlusSyllable>? = null,
)

@Serializable
internal data class YouLyPlusSyllable(
    val time: Long? = null,
    val duration: Long? = null,
    val text: String? = null,
    val isBackground: Boolean = false,
)

/**
 * YouLyPlus / LyricsPlus provider (multiple mirrors), ported from ArchiveTune to KMP.
 * Supports both TTML (v1) and word-synced JSON (v2) responses; the latter is
 * serialized to enhanced LRC so it flows through [parseProviderLyrics].
 */
object YouLyPlus {
    private const val TTML_PATH = "v1/ttml/get"
    private const val LYRICS_PATH = "v2/lyrics/get"

    private val baseUrls =
        listOf(
            "https://lyricsplus.binimum.org/",
            "https://lyricsplus.prjktla.my.id/",
            "https://lyricsplus.prjktla.workers.dev/",
            "https://lyricsplus.atomix.one/",
            "https://lyricsplus-seven.vercel.app/",
        )

    private val client by lazy {
        buildLyricsHttpClient(withContentNegotiation = false)
    }

    suspend fun getLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int = -1,
    ): Result<String> {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        val cleanAlbum = album?.trim().orEmpty()

        if (cleanTitle.isBlank() || cleanArtist.isBlank()) {
            return Result.failure(IllegalArgumentException("Song title and artist are required"))
        }

        return try {
            val lyrics =
                fetchTtml(cleanTitle, cleanArtist, cleanAlbum, durationSeconds)
                    ?: fetchLyricsAsLrc(cleanTitle, cleanArtist, cleanAlbum, durationSeconds)
                    ?: throw IllegalStateException("Lyrics unavailable")
            Result.success(lyrics)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAllLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int = -1,
        callback: (String) -> Unit,
    ) {
        getLyrics(title, artist, album, durationSeconds).onSuccess(callback)
    }

    private suspend fun fetchTtml(
        title: String,
        artist: String,
        album: String,
        durationSeconds: Int,
    ): String? =
        fetchFromMirrors(TTML_PATH, title, artist, album, durationSeconds) { body ->
            val trimmed = body.trim()
            when {
                trimmed.startsWith("<") -> trimmed
                else -> runCatching {
                    ProviderDefaults.json.decodeFromString<YouLyPlusTtmlResponse>(body).ttml?.trim()
                }.getOrNull()
            }?.takeIf { it.isNotBlank() && it.startsWith("<") }
        }

    private suspend fun fetchLyricsAsLrc(
        title: String,
        artist: String,
        album: String,
        durationSeconds: Int,
    ): String? =
        fetchFromMirrors(LYRICS_PATH, title, artist, album, durationSeconds) { body ->
            runCatching {
                ProviderDefaults.json.decodeFromString<YouLyPlusLyricsResponse>(body).toLyricsText()
            }.getOrNull()
        }

    private suspend fun fetchFromMirrors(
        path: String,
        title: String,
        artist: String,
        album: String,
        durationSeconds: Int,
        decode: (String) -> String?,
    ): String? {
        for (baseUrl in baseUrls) {
            currentCoroutineContext().ensureActive()
            val endpoint = baseUrl + path
            try {
                val response =
                    client.get(endpoint) {
                        header(HttpHeaders.Accept, "application/json")
                        header(HttpHeaders.UserAgent, "SimpMusic")
                        parameter("title", title)
                        parameter("artist", artist)
                        if (album.isNotBlank()) parameter("album", album)
                        if (durationSeconds > 0) parameter("duration", durationSeconds)
                    }
                if (!response.status.isSuccess()) continue
                val lyrics = decode(response.bodyAsText())
                if (!lyrics.isNullOrBlank()) return lyrics
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logProvider("YouLyPlus $path fetch error from $baseUrl: ${e.message}")
            }
        }
        return null
    }

    private fun YouLyPlusLyricsResponse.toLyricsText(): String? {
        if (lyrics.isEmpty()) return null

        val timedLines = lyrics.filter { it.time != null }
        if (timedLines.isNotEmpty()) {
            return timedLines
                .joinToString("\n") { line ->
                    buildString {
                        append(formatLrcTimestamp(line.time ?: 0L, bracketed = true))
                        val syllables =
                            line.syllabus.orEmpty().filter { !it.text.isNullOrBlank() && it.time != null }
                        if (type.equals("Word", ignoreCase = true) && syllables.isNotEmpty()) {
                            syllables.forEach { syllable ->
                                append(formatLrcTimestamp(syllable.time ?: 0L, bracketed = false))
                                append(syllable.text.orEmpty())
                            }
                        } else {
                            append(line.text.orEmpty())
                        }
                    }
                }.takeIf { it.isNotBlank() }
        }

        return lyrics
            .mapNotNull(YouLyPlusLine::text)
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString("\n")
            .takeIf(String::isNotBlank)
    }
}
