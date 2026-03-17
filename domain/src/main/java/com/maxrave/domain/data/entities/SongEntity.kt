package com.maxrave.domain.data.entities

import android.os.Parcel
import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.maxrave.domain.data.entities.DownloadState.STATE_NOT_DOWNLOADED
import com.maxrave.domain.data.type.RecentlyType
import java.time.LocalDateTime
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith

private object NullableLocalDateTimeParceler : kotlinx.parcelize.Parceler<LocalDateTime?> {
    override fun create(parcel: Parcel): LocalDateTime? = parcel.readString()?.let { LocalDateTime.parse(it) }

    override fun LocalDateTime?.write(parcel: Parcel, flags: Int) {
        parcel.writeString(this?.toString())
    }
}

private object NonNullLocalDateTimeParceler : kotlinx.parcelize.Parceler<LocalDateTime> {
    override fun create(parcel: Parcel): LocalDateTime =
        parcel.readString()?.let { LocalDateTime.parse(it) } ?: LocalDateTime.now()

    override fun LocalDateTime.write(parcel: Parcel, flags: Int) {
        parcel.writeString(toString())
    }
}

@Parcelize
@Entity(tableName = "song")
data class SongEntity(
    @PrimaryKey(autoGenerate = false) val videoId: String = "",
    val albumId: String? = null,
    val albumName: String? = null,
    val artistId: List<String>? = null,
    val artistName: List<String>? = null,
    val duration: String,
    val durationSeconds: Int,
    val isAvailable: Boolean,
    val isExplicit: Boolean,
    val likeStatus: String,
    val thumbnails: String? = null,
    val title: String,
    val videoType: String,
    val category: String?,
    val resultType: String?,
    val liked: Boolean = false,
    val totalPlayTime: Long = 0,
    val downloadState: Int = STATE_NOT_DOWNLOADED,
    val favoriteAt: @WriteWith<NullableLocalDateTimeParceler> LocalDateTime? = LocalDateTime.now(),
    val downloadedAt: @WriteWith<NullableLocalDateTimeParceler> LocalDateTime? = LocalDateTime.now(),
    val inLibrary: @WriteWith<NonNullLocalDateTimeParceler> LocalDateTime = LocalDateTime.now(),
    val canvasUrl: String? = null,
    val canvasThumbUrl: String? = null,
) : RecentlyType,
    Parcelable {
    override fun objectType(): RecentlyType.Type = RecentlyType.Type.SONG
}
