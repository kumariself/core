package org.simpmusic.lyrics.providers

import com.maxrave.ktorext.getEngine
import com.maxrave.logger.Logger
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.simpmusic.lyrics.domain.Lyrics
import org.simpmusic.lyrics.parser.parseRichSyncLyrics
import org.simpmusic.lyrics.parser.parseSyncedLyrics
import org.simpmusic.lyrics.parser.parseTtmlLyrics
import org.simpmusic.lyrics.parser.parseUnsyncedLyrics

/**
 * Shared helpers for the extra lyrics providers ported from ArchiveTune
 * (GPL-3.0, © Rukamori): KuGou, LrcLib (fuzzy), Paxsenix, Unison, YouLyPlus.
 *
 * The original providers were Android-only (OkHttp/CIO engines, java.util.Locale,
 * String.format, System.err). They have been adapted to Kotlin Multiplatform so
 * they run in SimpMusic's shared `lyricsService` module using the shared Ktor
 * engine ([getEngine]) and the multiplatform helpers below.
 */
internal object ProviderDefaults {
    val json: Json =
        Json {
            isLenient = true
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }

    const val TAG = "ExtraLyricsProviders"

    fun log(message: String) = Logger.d(TAG, message)
}

internal fun logProvider(message: String) = ProviderDefaults.log(message)

internal fun buildLyricsHttpClient(
    withContentNegotiation: Boolean = true,
    withContentEncoding: Boolean = false,
    expectSuccess: Boolean = false,
    followRedirects: Boolean = true,
    configure: HttpClientConfig<*>.() -> Unit = {},
): HttpClient =
    HttpClient(getEngine()) {
        this.expectSuccess = expectSuccess
        this.followRedirects = followRedirects
        if (withContentNegotiation) {
            install(ContentNegotiation) {
                json(ProviderDefaults.json)
            }
        }
        if (withContentEncoding) {
            install(ContentEncoding) {
                gzip()
                deflate()
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 20_000
        }
        configure()
    }

/** Multiplatform replacement for `String.format(Locale.US, "%02d", value)`. */
internal fun Long.pad2(): String {
    val v = this.coerceAtLeast(0L)
    return if (v < 10) "0$v" else "$v"
}

/** Multiplatform replacement for `String.format(Locale.US, "%03d", value)`. */
internal fun Long.pad3(): String {
    val v = this.coerceAtLeast(0L)
    return when {
        v < 10 -> "00$v"
        v < 100 -> "0$v"
        else -> "$v"
    }
}

/**
 * Build an LRC timestamp: `[mm:ss.mmm]` for a line (bracketed) or `<mm:ss.mmm>`
 * for a word inside an enhanced/rich-synced line.
 */
internal fun formatLrcTimestamp(
    timeMs: Long,
    bracketed: Boolean,
): String {
    val safeTime = timeMs.coerceAtLeast(0L)
    val minutes = safeTime / 60_000L
    val seconds = (safeTime % 60_000L) / 1000L
    val millis = safeTime % 1000L
    val timestamp = "${minutes.pad2()}:${seconds.pad2()}.${millis.pad3()}"
    return if (bracketed) "[$timestamp]" else "<$timestamp>"
}

private val WORD_TIMESTAMP_REGEX = Regex("""<\d{1,2}:\d{2}\.\d{2,3}>""")
private val LINE_TIMESTAMP_REGEX = Regex("""\[\d{1,2}:\d{2}\.\d{2,3}]""")

/**
 * Detect the lyric string format returned by a provider and parse it into the
 * lyricsService domain [Lyrics] model, reusing the existing parsers.
 *
 * - TTML (`<tt`/`<?xml`/`<`) -> [parseTtmlLyrics]
 * - Enhanced LRC with `<mm:ss.xx>` word markers -> [parseRichSyncLyrics]
 * - Standard LRC with `[mm:ss.xx]` line markers -> [parseSyncedLyrics]
 * - Anything else -> [parseUnsyncedLyrics]
 */
internal fun parseProviderLyrics(raw: String): Lyrics? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return when {
        trimmed.startsWith("<tt") || trimmed.startsWith("<?xml") || trimmed.startsWith("<") ->
            parseTtmlLyrics(trimmed)
        WORD_TIMESTAMP_REGEX.containsMatchIn(trimmed) ->
            parseRichSyncLyrics(trimmed)
        LINE_TIMESTAMP_REGEX.containsMatchIn(trimmed) ->
            parseSyncedLyrics(trimmed)
        else ->
            parseUnsyncedLyrics(trimmed)
    }
}
