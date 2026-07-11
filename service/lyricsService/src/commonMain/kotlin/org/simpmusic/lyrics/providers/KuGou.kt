package org.simpmusic.lyrics.providers

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlin.math.min

@Serializable
internal data class KuGouDownloadLyricsResponse(
    val content: String,
)

internal data class KuGouKeyword(
    val title: String,
    val artist: String,
)

@Serializable
internal data class KuGouSearchLyricsResponse(
    val status: Int = 0,
    val info: String = "",
    val errcode: Int = 0,
    val errmsg: String = "",
    val expire: Int = 0,
    val candidates: List<Candidate> = emptyList(),
) {
    @Serializable
    data class Candidate(
        val id: Long,
        @SerialName("product_from")
        val productFrom: String = "",
        val duration: Long = 0,
        val accesskey: String,
    )
}

@Serializable
internal data class KuGouSearchSongResponse(
    val status: Int = 0,
    val errcode: Int = 0,
    val error: String = "",
    val data: Data = Data(),
) {
    @Serializable
    data class Data(
        val info: List<Info> = emptyList(),
    ) {
        @Serializable
        data class Info(
            val duration: Int = 0,
            val hash: String = "",
        )
    }
}

/**
 * KuGou Lyrics provider, ported from ArchiveTune (originally modified from ViMusic).
 */
@OptIn(ExperimentalEncodingApi::class)
object KuGou {
    private const val PAGE_SIZE = 8
    private const val HEAD_CUT_LIMIT = 30
    private const val DURATION_TOLERANCE = 8

    private val client by lazy {
        buildLyricsHttpClient(withContentEncoding = true, expectSuccess = true)
    }

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Int,
    ): Result<String> =
        runCatching {
            val keyword = generateKeyword(title, artist)
            getLyricsCandidate(keyword, duration)?.let { candidate ->
                Base64.Default
                    .decode(downloadLyrics(candidate.id, candidate.accesskey).content)
                    .decodeToString()
                    .normalize()
            } ?: throw IllegalStateException("No lyrics candidate")
        }

    suspend fun getAllPossibleLyricsOptions(
        title: String,
        artist: String,
        duration: Int,
        callback: (String) -> Unit,
    ) {
        val keyword = generateKeyword(title, artist)
        searchSongs(keyword).data.info.forEach {
            if (duration == -1 || abs(it.duration - duration) <= DURATION_TOLERANCE) {
                searchLyricsByHash(it.hash).candidates.firstOrNull()?.let { candidate ->
                    Base64.Default
                        .decode(downloadLyrics(candidate.id, candidate.accesskey).content)
                        .decodeToString()
                        .normalize()
                        .let(callback)
                }
            }
        }
        searchLyricsByKeyword(keyword, duration).candidates.forEach { candidate ->
            Base64.Default
                .decode(downloadLyrics(candidate.id, candidate.accesskey).content)
                .decodeToString()
                .normalize()
                .let(callback)
        }
    }

    private suspend fun getLyricsCandidate(
        keyword: KuGouKeyword,
        duration: Int,
    ): KuGouSearchLyricsResponse.Candidate? {
        searchSongs(keyword).data.info.forEach { song ->
            if (duration == -1 || abs(song.duration - duration) <= DURATION_TOLERANCE) {
                val candidate = searchLyricsByHash(song.hash).candidates.firstOrNull()
                if (candidate != null) return candidate
            }
        }
        return searchLyricsByKeyword(keyword, duration).candidates.firstOrNull()
    }

    private suspend fun searchSongs(keyword: KuGouKeyword) =
        client
            .get("https://mobileservice.kugou.com/api/v3/search/song") {
                parameter("version", 9108)
                parameter("plat", 0)
                parameter("pagesize", PAGE_SIZE)
                parameter("showtype", 0)
                url.encodedParameters.append(
                    "keyword",
                    "${keyword.title} - ${keyword.artist}".encodeURLParameter(spaceToPlus = false),
                )
            }.body<KuGouSearchSongResponse>()

    private suspend fun searchLyricsByKeyword(
        keyword: KuGouKeyword,
        duration: Int,
    ) = client
        .get("https://lyrics.kugou.com/search") {
            parameter("ver", 1)
            parameter("man", "yes")
            parameter("client", "pc")
            parameter("duration", duration.takeIf { it != -1 }?.times(1000))
            url.encodedParameters.append(
                "keyword",
                "${keyword.title} - ${keyword.artist}".encodeURLParameter(spaceToPlus = false),
            )
        }.body<KuGouSearchLyricsResponse>()

    private suspend fun searchLyricsByHash(hash: String) =
        client
            .get("https://lyrics.kugou.com/search") {
                parameter("ver", 1)
                parameter("man", "yes")
                parameter("client", "pc")
                parameter("hash", hash)
            }.body<KuGouSearchLyricsResponse>()

    private suspend fun downloadLyrics(
        id: Long,
        accessKey: String,
    ) = client
        .get("https://lyrics.kugou.com/download") {
            parameter("fmt", "lrc")
            parameter("charset", "utf8")
            parameter("client", "pc")
            parameter("ver", 1)
            parameter("id", id)
            parameter("accesskey", accessKey)
        }.body<KuGouDownloadLyricsResponse>()

    private fun normalizeTitle(title: String) =
        title
            .replace("\\(.*\\)".toRegex(), "")
            .replace("（.*）".toRegex(), "")
            .replace("「.*」".toRegex(), "")
            .replace("『.*』".toRegex(), "")
            .replace("<.*>".toRegex(), "")
            .replace("《.*》".toRegex(), "")
            .replace("〈.*〉".toRegex(), "")
            .replace("＜.*＞".toRegex(), "")

    private fun normalizeArtist(artist: String) =
        artist
            .replace(", ", "、")
            .replace(" & ", "、")
            .replace(".", "")
            .replace("和", "、")
            .replace("\\(.*\\)".toRegex(), "")
            .replace("（.*）".toRegex(), "")

    private fun generateKeyword(
        title: String,
        artist: String,
    ) = KuGouKeyword(normalizeTitle(title), normalizeArtist(artist))

    private fun String.normalize(): String =
        replace("&apos;", "'")
            .lines()
            .filter { line -> line.matches(ACCEPTED_REGEX) }
            .let { lines ->
                var headCutLine = 0
                for (i in min(HEAD_CUT_LIMIT, lines.lastIndex) downTo 0) {
                    if (lines[i].matches(BANNED_REGEX)) {
                        headCutLine = i + 1
                        break
                    }
                }
                val filteredLines = lines.drop(headCutLine)

                var tailCutLine = 0
                for (i in min(lines.size - HEAD_CUT_LIMIT, lines.lastIndex) downTo 0) {
                    if (lines[lines.lastIndex - i].matches(BANNED_REGEX)) {
                        tailCutLine = i + 1
                        break
                    }
                }
                filteredLines.dropLast(tailCutLine).joinToString("\n")
            }

    private val ACCEPTED_REGEX = "\\[(\\d\\d):(\\d\\d)\\.(\\d{2,3})\\].*".toRegex()
    private val BANNED_REGEX = ".+].+[:：].+".toRegex()
}
