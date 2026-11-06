package com.nextscreen.app.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import com.nextscreen.app.dlna.Didl
import java.net.URLDecoder
import java.util.Locale

enum class MediaKind { VIDEO, AUDIO, IMAGE }

enum class TransportState(val upnp: String) {
    NO_MEDIA_PRESENT("NO_MEDIA_PRESENT"),
    STOPPED("STOPPED"),
    PLAYING("PLAYING"),
    PAUSED("PAUSED_PLAYBACK"),
    TRANSITIONING("TRANSITIONING"),
}

/** Callbacks are always delivered on the main thread. */
interface CastListener {
    fun onMediaChanged(media: CastMedia?) {}
    fun onStateChanged() {}
    fun onStopRequested() {}
    fun onError(message: String) {}
    fun onTracksInfoChanged() {}
    fun onVolumeChanged() {}
}

data class QualityOption(
    val label: String,
    val detail: String?,
    val group: TrackGroup,
    val index: Int,
    val shortSide: Int,
    val bitrate: Int,
    val selected: Boolean,
)

data class CastMedia(
    val uri: String,
    val metadata: String,
    val title: String,
    val kind: MediaKind,
    val mime: String?,
    val albumArt: String?,
    val artist: String?,
    val durationHintMs: Long,
) {
    fun toMediaItem(): MediaItem {
        val md = MediaMetadata.Builder().setTitle(title)
        artist?.let { md.setArtist(it) }
        albumArt?.let { runCatching { md.setArtworkUri(Uri.parse(it)) } }
        val b = MediaItem.Builder().setUri(uri).setMediaMetadata(md.build())
        mime?.let { b.setMimeType(it) }
        return b.build()
    }

    companion object {
        private val IMAGE_EXT = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic")
        private val AUDIO_EXT = listOf(".mp3", ".flac", ".m4a", ".aac", ".wav", ".ogg", ".opus", ".ape", ".wma")

        fun create(uri: String, metadata: String?): CastMedia {
            val meta = Didl.parse(metadata)
            val lowerUrl = uri.lowercase(Locale.ROOT)
            val path = runCatching { Uri.parse(uri).path }.getOrNull()?.lowercase(Locale.ROOT).orEmpty()
            val cls = meta.upnpClass?.lowercase(Locale.ROOT).orEmpty()
            val mimeHint = meta.mime?.lowercase(Locale.ROOT).orEmpty()
            val kind = when {
                cls.contains("videoitem") || mimeHint.startsWith("video/") -> MediaKind.VIDEO
                cls.contains("imageitem") || mimeHint.startsWith("image/") || IMAGE_EXT.any { path.endsWith(it) } -> MediaKind.IMAGE
                cls.contains("audioitem") || mimeHint.startsWith("audio/") || AUDIO_EXT.any { path.endsWith(it) } -> MediaKind.AUDIO
                else -> MediaKind.VIDEO
            }
            val mime = when {
                mimeHint.contains("mpegurl") || path.endsWith(".m3u8") || lowerUrl.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
                mimeHint.contains("dash") || path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
                else -> null
            }
            val title = meta.title?.takeIf { it.isNotBlank() } ?: fallbackTitle(uri, kind)
            return CastMedia(uri, metadata.orEmpty(), title, kind, mime, meta.albumArt, meta.artist, meta.durationMs)
        }

        private fun fallbackTitle(uri: String, kind: MediaKind): String {
            val seg = runCatching { Uri.parse(uri).lastPathSegment }.getOrNull()
                ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            if (!seg.isNullOrBlank() && seg.length in 3..80 && seg.contains('.')) return seg
            return when (kind) {
                MediaKind.VIDEO -> "网络视频"
                MediaKind.AUDIO -> "网络音频"
                MediaKind.IMAGE -> "图片"
            }
        }
    }
}
