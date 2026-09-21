package dev.anilbeesetti.nextplayer.core.media.network

import android.net.Uri
import dev.anilbeesetti.nextplayer.core.media.network.datasource.NetworkSessions
import dev.anilbeesetti.nextplayer.core.model.NetworkFile
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Finds the subtitle files that sit next to a network video.
 *
 * A share that stores `Movie.mkv` next to `Movie.srt` is common, and the player would otherwise
 * show no subtitles for it at all. Lookups run over the session that already plays the video, so
 * they reuse its connected client instead of opening a second connection to the same server.
 *
 * Discovery is best-effort by design: a server that refuses a listing, a connection that dropped,
 * or a video that is not on the network all mean "no subtitles found" rather than an error, because
 * nothing about a missing subtitle should keep the video itself from starting.
 */
@Singleton
class NetworkSubtitleResolver @Inject constructor(
    private val sessions: NetworkSessions,
) {

    /**
     * Returns playable URIs for the subtitle siblings of [videoUri], in deterministic
     * case-insensitive filename order, or an empty list when they cannot be discovered.
     *
     * A file counts as a sibling when it sits in the video's own folder, is a regular file, is named
     * exactly like the video ignoring case, and ends in one of [SUPPORTED_EXTENSIONS]. Language
     * suffixed names such as `Movie.en.srt` are not matched: which language the user wants is
     * decided later, by the subtitle that is actually opened.
     *
     * Only discovery failures are contained. Cancellation is rethrown: a player that stopped playing
     * this video is not a server that had no subtitles to offer, and swallowing it would leave the
     * caller running on a cancelled scope.
     */
    suspend fun findAdjacentSubtitles(videoUri: Uri): List<Uri> = runCatching {
        sessions.withTarget(videoUri) { connection, client, videoPath ->
            val folder = videoPath.substringBeforeLast('/', missingDelimiterValue = "")
            val videoName = videoPath.substringAfterLast('/').substringBeforeLast('.')

            client.listFiles(folder).getOrThrow()
                .asSequence()
                .filterNot(NetworkFile::isDirectory)
                .filter { file ->
                    file.name.substringBeforeLast('.').equals(videoName, ignoreCase = true) &&
                        file.name.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS
                }
                .sortedBy { it.name.lowercase() }
                .map { NetworkUri.build(connection, it.path) }
                .distinct()
                .toList()
        }
    }.getOrElse { if (it is CancellationException) throw it else emptyList() }

    /**
     * Opens the subtitle at [uri] through the client already playing its video.
     *
     * A failure here is the caller's to handle — it belongs to this one subtitle, not to the video
     * or to any other subtitle.
     */
    suspend fun openStream(uri: Uri): InputStream =
        sessions.withTarget(uri) { _, client, path -> client.openStream(path, 0) }

    private companion object {
        /** The subtitle formats the player can render, lowercased for case-insensitive matching. */
        val SUPPORTED_EXTENSIONS = setOf("srt", "ssa", "ass", "vtt", "ttml")
    }
}
