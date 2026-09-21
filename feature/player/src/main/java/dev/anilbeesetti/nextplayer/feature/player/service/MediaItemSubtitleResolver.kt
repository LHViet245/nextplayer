package dev.anilbeesetti.nextplayer.feature.player.service

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.anilbeesetti.nextplayer.core.common.extensions.getLocalSubtitles
import dev.anilbeesetti.nextplayer.core.common.extensions.getPath
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.media.network.NetworkSubtitleResolver
import dev.anilbeesetti.nextplayer.core.media.network.NetworkUri
import dev.anilbeesetti.nextplayer.feature.player.extensions.uriToSubtitleConfiguration
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Decides which subtitles a media item is played with, and in what order.
 *
 * Subtitles reach the player from three places: ones the caller already supplied, ones that sit
 * beside the video — in the same folder for a local file, in the same folder on the share for an
 * `smb`/`ftp`/`sftp`/`webdav` video — and the ones the user added by hand, which are persisted
 * separately. Assembling them here keeps that decision out of the service, and keeps the order
 * stable, because the player stores the chosen subtitle as an index into this list: the same item
 * resolved twice must always present its tracks in the same order, or a saved subtitle choice would
 * drift to a different track.
 *
 * The order is therefore fixed:
 * 1. the configurations the caller already supplied, in their own order;
 * 2. the subtitles discovered beside the video, in the deterministic order their lookup returns
 *    (filename order on a share, extension order locally);
 * 3. the saved external subtitles, in the order they were persisted.
 *
 * A subtitle offered by more than one source is kept once, in the earliest position it appears in,
 * matched by its original URI rather than the converted one it is played from.
 *
 * Where a video is looked up follows the video's own URI; where a subtitle is *read* follows the
 * subtitle's. A subtitle picked from this device for a video on a share is still read by the
 * ContentResolver, because that is the only thing that can open it.
 *
 * Nothing here is allowed to keep the video from playing: a share that refuses a listing, a folder
 * that cannot be read, and a subtitle that cannot be opened each yield fewer subtitles rather than a
 * failed resolve. Cancellation is the one failure that is passed on — a resolve that stopped because
 * the player moved on is not a resolve that found nothing.
 */
@Singleton
class MediaItemSubtitleResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkSubtitleResolver: NetworkSubtitleResolver,
    private val preferencesRepository: PreferencesRepository,
) {

    /**
     * Returns the subtitle configurations for [mediaItem]: the caller's own, then the ones beside the
     * video, then [savedExternalSubs].
     *
     * The subtitles beside the video are read from the filesystem for a local item and from the share
     * that plays the video for a network one. Which of the two happens is decided by the media URI
     * alone, so an item is never looked up on both sides.
     *
     * [localMediaPath] is the file the caller already resolved for the item — the path it stored for
     * the video, falling back to whatever the media URI itself resolves to. It is only consulted for
     * a local item, and only to find the folder to look in.
     */
    suspend fun resolve(
        mediaItem: MediaItem,
        savedExternalSubs: List<Uri>,
        localMediaPath: String? = null,
    ): List<MediaItem.SubtitleConfiguration> {
        val mediaUri = mediaItem.mediaId.toUri()
        val isNetwork = NetworkUri.isNetworkUri(mediaUri)
        val subtitleEncoding = preferencesRepository.playerPreferences.value.subtitleTextEncoding

        val besideTheVideo = if (isNetwork) {
            adjacentNetworkSubtitles(mediaUri)
        } else {
            adjacentLocalSubtitles(localMediaPath, mediaUri, savedExternalSubs)
        }

        val discovered = (besideTheVideo + savedExternalSubs)
            .distinctBy(Uri::toString)
            .mapNotNull { uri -> subtitleConfigurationOf(uri, subtitleEncoding) }

        val supplied = mediaItem.localConfiguration?.subtitleConfigurations ?: emptyList()
        return (supplied + discovered).distinctBy(::sourceUriOf)
    }

    /**
     * The subtitle siblings of a network video, discovered over the client already playing it.
     *
     * A lookup that fails is no subtitles, the way [NetworkSubtitleResolver] itself treats an
     * uncooperative server: a missing subtitle must never keep the video from starting.
     */
    private suspend fun adjacentNetworkSubtitles(videoUri: Uri): List<Uri> =
        runCatching { networkSubtitleResolver.findAdjacentSubtitles(videoUri) }
            .getOrElse { if (it is CancellationException) throw it else emptyList() }

    /**
     * The subtitle files next to the local video, minus the saved external ones.
     *
     * The folder is [mediaPath] when the caller already resolved one for the item, and whatever the
     * media URI resolves to otherwise. Resolving that folder can fail the same way reading it can, so
     * both happen inside the guard: a video whose folder cannot be found or read has no subtitles
     * beside it rather than no playback.
     */
    private suspend fun adjacentLocalSubtitles(
        mediaPath: String?,
        mediaUri: Uri,
        excludeSubs: List<Uri>,
    ): List<Uri> = runCatching {
        val path = mediaPath ?: context.getPath(mediaUri)
        path?.let { resolved -> File(resolved).getLocalSubtitles(context = context, excludeSubsList = excludeSubs) }
            ?: emptyList()
    }.getOrElse { if (it is CancellationException) throw it else emptyList() }

    /**
     * Converts one subtitle for playback, or null when it cannot be built at all.
     *
     * Whether the bytes are read over the network is decided by the subtitle's own URI, not by where
     * its video lives: an `smb://` subtitle is read through the client that plays the video — the only
     * thing that can open such a stream — while everything else keeps the ContentResolver or URL path
     * it has always used. A subtitle picked from this device for a video on a share is exactly that
     * case: the picker hands out a `content://` URI that only the ContentResolver can open, and reading
     * it through the network client would leave it unconverted.
     *
     * The conversion is per subtitle so a single unreadable file costs its own track and no other.
     */
    private suspend fun subtitleConfigurationOf(
        uri: Uri,
        subtitleEncoding: String,
    ): MediaItem.SubtitleConfiguration? = runCatching {
        val readThroughNetwork: (suspend () -> InputStream)? = if (NetworkUri.isNetworkUri(uri)) {
            { networkSubtitleResolver.openStream(uri) }
        } else {
            null
        }
        context.uriToSubtitleConfiguration(
            uri = uri,
            subtitleEncoding = subtitleEncoding,
            openInputStream = readThroughNetwork,
        )
    }.getOrElse { if (it is CancellationException) throw it else null }

    /**
     * The URI a configuration came from, for telling two offerings of one subtitle apart.
     *
     * A converted subtitle is played from a cache file or an unchanged URL, but keeps the URI it was
     * found at as its id; that original is what a duplicate from another source matches on.
     */
    private fun sourceUriOf(configuration: MediaItem.SubtitleConfiguration): String =
        configuration.id ?: configuration.uri.toString()
}
