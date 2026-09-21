package dev.anilbeesetti.nextplayer.feature.player.extensions

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import dev.anilbeesetti.nextplayer.core.common.extensions.convertToUTF8
import dev.anilbeesetti.nextplayer.core.common.extensions.getFilenameFromUri
import java.io.InputStream
import java.nio.charset.Charset

fun Uri.getSubtitleMime(): String {
    // Subtitle names are matched case-insensitively: shares and document providers hand out `.SRT`
    // as readily as `.srt`.
    val path = path?.lowercase() ?: return MimeTypes.APPLICATION_SUBRIP

    return when {
        path.endsWith(".ssa") || path.endsWith(".ass") -> {
            MimeTypes.TEXT_SSA
        }

        path.endsWith(".vtt") -> {
            MimeTypes.TEXT_VTT
        }

        path.endsWith(".ttml") || path.endsWith(".xml") || path.endsWith(".dfxp") -> {
            MimeTypes.APPLICATION_TTML
        }

        else -> {
            MimeTypes.APPLICATION_SUBRIP
        }
    }
}

val Uri.isSchemaContent: Boolean
    get() = ContentResolver.SCHEME_CONTENT.equals(scheme, ignoreCase = true)

/**
 * Builds the subtitle configuration for [uri].
 *
 * [openInputStream] is for subtitles that cannot be read through the [ContentResolver]: a network
 * subtitle is read through the client that plays its video instead. It is optional, and null keeps the
 * behaviour of every existing caller: the subtitle is opened by [convertToUTF8] itself. See
 * [convertToUTF8] for the provider contract.
 */
suspend fun Context.uriToSubtitleConfiguration(
    uri: Uri,
    subtitleEncoding: String = "",
    isSelected: Boolean = false,
    openInputStream: (suspend () -> InputStream)? = null,
): MediaItem.SubtitleConfiguration {
    val charset = if (subtitleEncoding.isNotEmpty() && Charset.isSupported(subtitleEncoding)) {
        Charset.forName(subtitleEncoding)
    } else {
        null
    }
    val label = getFilenameFromUri(uri)
    val mimeType = uri.getSubtitleMime()
    val utf8ConvertedUri = if (openInputStream == null) {
        convertToUTF8(uri = uri, charset = charset)
    } else {
        convertToUTF8(uri = uri, charset = charset, openInputStream = openInputStream)
    }
    return MediaItem.SubtitleConfiguration.Builder(utf8ConvertedUri).apply {
        setId(uri.toString())
        setMimeType(mimeType)
        setLabel(label)
        if (isSelected) setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
    }.build()
}

@Suppress("DEPRECATION")
fun Bundle.getParcelableUriArray(key: String): ArrayList<out Parcelable>? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayList(key, Uri::class.java)
    } else {
        getParcelableArrayList(key)
    }
}
