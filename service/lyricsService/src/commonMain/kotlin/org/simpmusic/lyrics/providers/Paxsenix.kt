package org.simpmusic.lyrics.providers

import io.ktor.client.call.body
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.simpmusic.lyrics.providers.models.AppleMusicLyricsResponse
import org.simpmusic.lyrics.providers.models.NeteaseSearchResponse
import org.simpmusic.lyrics.providers.models.PaxsenixSearchItem
import kotlin.math.abs

/**
 * Paxsenix multi-source lyrics provider ported from ArchiveTune (GPL-3.0, © Rukamori).
 *
 * Aggregates Apple Music (TTML word-by-word), NetEase (karaoke/LRC), Spotify,
 * Musixmatch and YouTube lyrics via the public https://lyrics.paxsenix.org API.
 * Adapted to Kotlin Multiplatform: the OkHttp engine is replaced by the shared
 * [buildLyricsHttpClient] engine, `java.util.Locale` is dropped (storefront
 * defaults to "us"), `System.err` becomes [logProvider], and `String.format`
 * becomes the multiplatform pad helpers.
 */
object Paxsenix {
    private const val BASE_URL = "https://lyrics.paxsenix.org/"
    private const val AMP_BASE_URL = "https://amp-api.music.apple.com"

    var userAgent: String = "SimpMusic"
        private set

    fun setUserAgent(
        appName: String,
        versionName: String,
    ) {
        userAgent = "$appName/$versionName"
    }

    // Apple Music AMP web-play token, as shipped by ArchiveTune.
    private var ampToken: String =
        "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6IldlYlBsYXlLaWQifQ" +
            ".eyJpc3MiOiJBTVBXZWJQbGF5IiwiaWF0IjoxNzc0NDU2MzgyLCJleHAiOjE3ODE3" +
            "MTM5ODIsInJvb3RfaHR0cHNfb3JpZ2luIjpbImFwcGxlLmNvbSJdfQ" +
            ".4n8qYF4qa18sL1E0G9A3qX35cD8wQ-IJcS9Bh8ZT8JV_yLBtVq46B-9-2ZS3EvWHuw3yK9BYFYAhAdTaDm38vQ"

    fun setAmpToken(token: String) {
        ampToken = token
    }

    private val json = ProviderDefaults.json

    private val client by lazy {
        buildLyricsHttpClient(withContentNegotiation = true, expectSuccess = false) {
            defaultRequest {
                url(BASE_URL)
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Accept, "application/json, text/plain, */*")
                header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
            }
        }
    }

    private const val AMP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

    private fun resolveDurationMs(duration: Int): Long =
        when {
            duration <= 0 -> 0L
            duration > 360000 -> duration.toLong() // already ms
            else -> duration * 1000L // seconds
        }

    private val lyricsContentKeys =
        listOf("lyrics", "lrc", "content", "text", "plainLyrics", "syncedLyrics", "line", "lyric")

    private fun cleanJsonLyrics(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val payload = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: return trimmed
        return extractLyrics(payload)
    }

    private fun extractLyrics(element: JsonElement): String? =
        when (element) {
            JsonNull -> null
            is JsonPrimitive -> {
                if (!element.isString) {
                    null
                } else {
                    val value = element.content.trim()
                    if (value.isEmpty()) {
                        null
                    } else {
                        val nested = runCatching { json.parseToJsonElement(value) }.getOrNull()
                        if (nested != null && nested !is JsonPrimitive) extractLyrics(nested) else value
                    }
                }
            }
            is JsonArray ->
                element
                    .mapNotNull(::extractLyrics)
                    .joinToString("\n")
                    .trim()
                    .takeIf { it.isNotEmpty() }
            is JsonObject ->
                if (element.isErrorPayload()) {
                    null
                } else {
                    lyricsContentKeys
                        .asSequence()
                        .mapNotNull { key -> element[key]?.let(::extractLyrics) }
                        .firstOrNull()
                        ?: (element["metadata"] as? JsonObject)?.let { metadata ->
                            lyricsContentKeys
                                .asSequence()
                                .mapNotNull { key -> metadata[key]?.let(::extractLyrics) }
                                .firstOrNull()
                        }
                        ?: element["words"]?.let { words ->
                            when (words) {
                                is JsonArray ->
                                    words
                                        .mapNotNull(::extractLyrics)
                                        .joinToString(" ")
                                        .trim()
                                        .takeIf { it.isNotEmpty() }
                                else -> extractLyrics(words)
                            }
                        }
                }
        }

    private fun JsonObject.isErrorPayload(): Boolean {
        if ((this["isError"] as? JsonPrimitive)?.booleanOrNull == true) return true
        return when (val error = this["error"]) {
            null, JsonNull -> false
            is JsonPrimitive -> error.booleanOrNull ?: error.content.trim().isNotEmpty()
            is JsonArray -> error.isNotEmpty()
            is JsonObject -> error.isNotEmpty()
        }
    }

    private suspend fun searchAppleMusicId(
        title: String,
        artist: String,
        durationMs: Long,
    ): String? {
        val query = "$title $artist"
        logProvider("Paxsenix: searching Apple Music catalog for: $query")
        val storefront = "us"

        return runCatching {
            val response =
                client.get("$AMP_BASE_URL/v1/catalog/$storefront/search") {
                    header("Authorization", "Bearer $ampToken")
                    header("Origin", "https://music.apple.com")
                    header("Referer", "https://music.apple.com/")
                    header(HttpHeaders.UserAgent, AMP_USER_AGENT)
                    parameter("term", query)
                    parameter("types", "songs")
                    parameter("limit", "10")
                }

            if (response.status != HttpStatusCode.OK) {
                logProvider("Paxsenix: AMP search failed with status: ${response.status}")
                return@runCatching null
            }

            val root = response.body<JsonObject>()
            val songs =
                root["results"]
                    ?.jsonObject
                    ?.get("songs")
                    ?.jsonObject
                    ?.get("data")
                    ?.jsonArray
                    ?: return@runCatching null

            if (songs.isEmpty()) return@runCatching null

            data class ScoredSong(
                val id: String,
                val score: Int,
            )

            val scored =
                songs
                    .mapNotNull { item ->
                        val obj = item as? JsonObject ?: return@mapNotNull null
                        val attrs = obj["attributes"]?.jsonObject ?: return@mapNotNull null
                        val songId = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val name = attrs["name"]?.jsonPrimitive?.contentOrNull ?: ""
                        val artistName = attrs["artistName"]?.jsonPrimitive?.contentOrNull ?: ""
                        val dur = attrs["durationInMillis"]?.jsonPrimitive?.longOrNull ?: 0L

                        var score = 0
                        if (name.equals(title, ignoreCase = true)) {
                            score += 20
                        } else if (name.contains(title, ignoreCase = true) || title.contains(name, ignoreCase = true)) {
                            score += 10
                        }
                        if (artistName.equals(artist, ignoreCase = true)) {
                            score += 15
                        } else if (artistName.contains(artist, ignoreCase = true) || artist.contains(artistName, ignoreCase = true)) {
                            score += 5
                        }
                        if (durationMs > 0 && dur > 0) {
                            val diff = abs(dur - durationMs)
                            if (diff < 3000) score += 10 else if (diff < 10000) score += 5
                        }
                        ScoredSong(songId, score)
                    }.sortedByDescending { it.score }

            val best = scored.firstOrNull() ?: return@runCatching null
            if (best.score < 12) {
                logProvider("Paxsenix: rejecting AMP match — score ${best.score} < 12")
                return@runCatching null
            }
            best.id
        }.onFailure { e ->
            if (e is CancellationException) throw e
            logProvider("Paxsenix: AMP search error: ${e.message}")
        }.getOrNull()
    }

    suspend fun getAppleMusicLyrics(
        title: String,
        artist: String,
        durationSeconds: Int,
    ): Result<String> =
        runCatching {
            val durationMs = resolveDurationMs(durationSeconds)
            val songId =
                searchAppleMusicId(title, artist, durationMs)
                    ?: throw IllegalStateException("Apple Music lyrics unavailable")

            val lyricsResponse =
                client.get("apple-music/lyrics") {
                    parameter("id", songId)
                    parameter("ttml", "true")
                }

            if (lyricsResponse.status == HttpStatusCode.OK) {
                runCatching {
                    val rawBody = lyricsResponse.body<String>().trim()
                    if (rawBody.startsWith("<tt") || rawBody.startsWith("<?xml")) {
                        return@runCatching rawBody
                    }
                    val data = json.parseToJsonElement(rawBody).jsonObject
                    val content = data["content"]?.jsonPrimitive?.contentOrNull
                    if (content != null && (content.contains("<tt") || content.contains("<?xml"))) {
                        return@runCatching content
                    }
                }
            }

            val jsonResponse =
                client.get("apple-music/lyrics") {
                    parameter("id", songId)
                }
            if (jsonResponse.status == HttpStatusCode.OK) {
                val lyricsData = jsonResponse.body<AppleMusicLyricsResponse>()
                if (lyricsData.content.isNotEmpty()) {
                    return@runCatching convertAppleMusicToLrc(lyricsData)
                }
            }

            throw IllegalStateException("Apple Music lyrics unavailable")
        }

    suspend fun getNeteaseLyrics(
        title: String,
        artist: String,
        durationSeconds: Int,
    ): Result<String> =
        runCatching {
            val durationMs = resolveDurationMs(durationSeconds)
            val query = "$title $artist"
            val neteaseSearch = client.get("netease/search") { parameter("q", query) }

            if (neteaseSearch.status == HttpStatusCode.OK) {
                val searchResponse = neteaseSearch.body<NeteaseSearchResponse>()
                val songs = searchResponse.result?.songs ?: emptyList()
                val bestMatch =
                    if (durationMs > 0) {
                        songs.minByOrNull { abs(it.duration.toLong() - durationMs) }
                    } else {
                        songs.firstOrNull()
                    }

                if (bestMatch != null) {
                    val diff = abs(bestMatch.duration.toLong() - durationMs)
                    if (durationMs <= 0 || diff < 10000) {
                        val lyricsResponse =
                            client.get("netease/lyrics") {
                                parameter("id", bestMatch.id)
                                parameter("word", "true")
                            }
                        if (lyricsResponse.status == HttpStatusCode.OK) {
                            val lyricsData = lyricsResponse.body<JsonObject>()
                            val klyric =
                                lyricsData["klyric"]?.jsonObject?.get("lyric")?.jsonPrimitive?.contentOrNull
                            if (!klyric.isNullOrBlank()) return@runCatching klyric

                            val lrc =
                                lyricsData["lrc"]?.jsonObject?.get("lyric")?.jsonPrimitive?.contentOrNull
                            if (!lrc.isNullOrBlank()) return@runCatching lrc
                        }
                    }
                }
            }
            throw IllegalStateException("NetEase lyrics unavailable")
        }

    suspend fun getSpotifyLyrics(
        title: String,
        artist: String,
        durationSeconds: Int,
    ): Result<String> =
        runCatching {
            val durationMs = resolveDurationMs(durationSeconds)
            val query = "$title $artist"
            val spotifySearch = client.get("spotify/search") { parameter("q", query) }
            if (spotifySearch.status == HttpStatusCode.OK) {
                val items = spotifySearch.body<List<PaxsenixSearchItem>>()
                val bestMatch =
                    if (durationMs > 0) items.minByOrNull { abs(it.durationMs - durationMs) } else items.firstOrNull()
                if (bestMatch != null) {
                    val diff = abs(bestMatch.durationMs - durationMs)
                    if (durationMs <= 0 || diff < 10000) {
                        val lyricsResponse = client.get("spotify/lyrics") { parameter("id", bestMatch.realId) }
                        if (lyricsResponse.status == HttpStatusCode.OK) {
                            val data = cleanJsonLyrics(lyricsResponse.body<String>())
                            if (data != null) return@runCatching data
                        }
                    }
                }
            }
            throw IllegalStateException("Spotify lyrics unavailable")
        }

    suspend fun getYouTubeLyrics(
        title: String,
        artist: String,
        durationSeconds: Int,
    ): Result<String> =
        runCatching {
            val durationMs = resolveDurationMs(durationSeconds)
            val query = "$title $artist"
            val searchResponse = client.get("youtube/search") { parameter("q", query) }
            if (searchResponse.status != HttpStatusCode.OK) {
                throw IllegalStateException("YouTube lyrics unavailable")
            }
            val items = searchResponse.body<List<PaxsenixSearchItem>>()
            val bestMatch =
                if (durationMs > 0) items.minByOrNull { abs(it.durationMs - durationMs) } else items.firstOrNull()
            if (bestMatch != null) {
                val diff = abs(bestMatch.durationMs - durationMs)
                if (durationMs <= 0 || diff < 10000) {
                    val lyricsResponse = client.get("youtube/lyrics") { parameter("id", bestMatch.realId) }
                    if (lyricsResponse.status == HttpStatusCode.OK) {
                        val data = cleanJsonLyrics(lyricsResponse.body<String>())
                        if (data != null) return@runCatching data
                    }
                }
            }
            throw IllegalStateException("YouTube lyrics unavailable")
        }

    suspend fun getMusixmatchLyrics(
        title: String,
        artist: String,
        durationSeconds: Int,
    ): Result<String> =
        runCatching {
            val query = "$title $artist"
            val mxmWord =
                client.get("musixmatch/lyrics") {
                    parameter("q", query)
                    parameter("t", title)
                    parameter("a", artist)
                    parameter("d", durationSeconds.toString())
                    parameter("type", "word")
                }
            if (mxmWord.status == HttpStatusCode.OK) {
                val data = cleanJsonLyrics(mxmWord.body<String>())
                if (data != null) return@runCatching data
            }

            val mxmLyrics =
                client.get("musixmatch/lyrics") {
                    parameter("q", query)
                    parameter("t", title)
                    parameter("a", artist)
                    parameter("d", durationSeconds.toString())
                }
            if (mxmLyrics.status == HttpStatusCode.OK) {
                val data = cleanJsonLyrics(mxmLyrics.body<String>())
                if (data != null) return@runCatching data
            }
            throw IllegalStateException("Musixmatch lyrics unavailable")
        }

    suspend fun getLyrics(
        title: String,
        artist: String,
        durationSeconds: Int,
    ): Result<String> =
        runCatching {
            getAppleMusicLyrics(title, artist, durationSeconds).getOrNull()?.let { return@runCatching it }
            getNeteaseLyrics(title, artist, durationSeconds).getOrNull()?.let { return@runCatching it }
            getSpotifyLyrics(title, artist, durationSeconds).getOrNull()?.let { return@runCatching it }
            getMusixmatchLyrics(title, artist, durationSeconds).getOrNull()?.let { return@runCatching it }
            throw IllegalStateException("Lyrics unavailable from Paxsenix for $title")
        }

    private fun convertAppleMusicToLrc(response: AppleMusicLyricsResponse): String =
        response.content.joinToString("\n") { line ->
            val minutes = line.timestamp / 1000 / 60
            val seconds = (line.timestamp / 1000) % 60
            val hundredths = (line.timestamp % 1000) / 10
            val time = "[${minutes.pad2()}:${seconds.pad2()}.${hundredths.pad2()}]"
            val text = line.text.joinToString(" ") { it.text.trim() }
            "$time$text"
        }

    suspend fun getAllLyrics(
        title: String,
        artist: String,
        duration: Int,
        callback: (String) -> Unit,
    ) {
        getLyrics(title, artist, duration).onSuccess(callback)
    }
}
