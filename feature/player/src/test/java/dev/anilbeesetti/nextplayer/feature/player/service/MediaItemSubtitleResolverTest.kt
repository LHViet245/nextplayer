package dev.anilbeesetti.nextplayer.feature.player.service

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.media.network.NetworkClient
import dev.anilbeesetti.nextplayer.core.media.network.NetworkClientFactory
import dev.anilbeesetti.nextplayer.core.media.network.NetworkSubtitleResolver
import dev.anilbeesetti.nextplayer.core.media.network.NetworkUri
import dev.anilbeesetti.nextplayer.core.media.network.datasource.NetworkSessions
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.NetworkConnection
import dev.anilbeesetti.nextplayer.core.model.NetworkFile
import dev.anilbeesetti.nextplayer.core.model.NetworkProtocol
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import dev.anilbeesetti.nextplayer.feature.player.extensions.audioDecoderMode
import dev.anilbeesetti.nextplayer.feature.player.extensions.audioTrackIndex
import dev.anilbeesetti.nextplayer.feature.player.extensions.playbackSpeed
import dev.anilbeesetti.nextplayer.feature.player.extensions.positionMs
import dev.anilbeesetti.nextplayer.feature.player.extensions.setExtras
import dev.anilbeesetti.nextplayer.feature.player.extensions.subtitleDelayMilliseconds
import dev.anilbeesetti.nextplayer.feature.player.extensions.subtitleSpeed
import dev.anilbeesetti.nextplayer.feature.player.extensions.subtitleTrackIndex
import dev.anilbeesetti.nextplayer.feature.player.extensions.videoDecoderMode
import dev.anilbeesetti.nextplayer.feature.player.extensions.videoZoom
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

/**
 * Which subtitles a media item gets, and in what order, decides what the user sees in the player's
 * subtitle menu and which track a stored `subtitleTrackIndex` still points at after a restart. The
 * tests below pin the three properties that matter: a local video is never looked up on a share and
 * a share video is never looked up on the filesystem; the sources are assembled in a stable order
 * with each subtitle offered once; and no single unreadable subtitle or unreachable share can take
 * the other tracks — or the video — down with it.
 *
 * The network side runs against a real [NetworkSubtitleResolver] over a real [NetworkSessions] and
 * fake clients, so "the share was listed" and "the subtitle was read through the playing client" are
 * observed rather than assumed. The local side runs against real files in a temporary folder.
 */
@RunWith(RobolectricTestRunner::class)
class MediaItemSubtitleResolverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()

    private val connection = NetworkConnection(
        id = 1,
        name = "NAS",
        protocol = NetworkProtocol.SMB,
        host = "192.168.1.10",
        path = "Media",
    )

    private val factory = FakeNetworkClientFactory()
    private val sessions = NetworkSessions(
        resolver = { id -> connection.takeIf { it.id == id } },
        clientFactory = factory,
    )
    private val networkSubtitleResolver = NetworkSubtitleResolver(sessions)

    private val resolver = MediaItemSubtitleResolver(
        context = context,
        networkSubtitleResolver = networkSubtitleResolver,
        preferencesRepository = FakePreferencesRepository(),
    )

    private val videoUri = NetworkUri.build(connection, "Movies/Movie.mkv")
    private val adjacentSrt = NetworkUri.build(connection, "Movies/Movie.srt")
    private val adjacentAss = NetworkUri.build(connection, "Movies/Movie.ass")
    private val externalEn = NetworkUri.build(connection, "Subs/Movie.en.srt")
    private val externalVi = NetworkUri.build(connection, "Subs/Movie.vi.srt")

    /** A subtitle that was added by hand, unrelated to any file beside the video. */
    private val pickedSubtitle = Uri.parse("content://media/external/video/media/7")

    /** The thumbnail a network item is handed with, which the service keeps rather than extracts. */
    private val artworkUri = Uri.parse("content://media/external/images/media/3")

    // Source selection.

    @Test
    fun `a local video finds the subtitles beside it and never opens a network client`() = runBlocking {
        val video = localMovie(subs = listOf("srt", "vtt"), folderName = "local-found")

        val configurations = resolver.resolve(
            mediaItem = mediaItemOf(Uri.fromFile(video).toString()),
            savedExternalSubs = emptyList(),
            localMediaPath = video.path,
        )

        assertEquals(
            listOf("Movie.srt", "Movie.vtt").map { Uri.fromFile(File(video.parentFile, it)).toString() },
            configurations.map(MediaItem.SubtitleConfiguration::id),
        )
        assertTrue(
            "a local video must not reach for a share",
            factory.clients.isEmpty(),
        )
    }

    @Test
    fun `an smb video lists its own folder and never looks beside a local path`() = runBlocking {
        factory.files = listOf(NetworkFile("Movie.srt", "Movies/Movie.srt", false))
        val decoy = localMovie(subs = listOf("srt"), folderName = "local-decoy")

        val configurations = resolver.resolve(
            mediaItem = mediaItemOf(videoUri.toString()),
            savedExternalSubs = emptyList(),
            // A local path that does hold a subtitle: taking it would prove the wrong branch ran.
            localMediaPath = decoy.path,
        )

        assertEquals(listOf(adjacentSrt.toString()), configurations.map(MediaItem.SubtitleConfiguration::id))
        assertEquals(
            "the video's own folder is the only one listed",
            listOf("Movies"),
            factory.clients.single().listedPaths,
        )
    }

    @Test
    fun `a network subtitle is read through the client that plays the video`() = runBlocking {
        factory.files = listOf(NetworkFile("Movie.srt", "Movies/Movie.srt", false))
        // A legacy encoded subtitle, so the resolver has to read the bytes to convert them.
        factory.content = LEGACY_SUBRIP.toByteArray(StandardCharsets.ISO_8859_1)
        val legacyPreferences = FakePreferencesRepository(subtitleTextEncoding = "windows-1252")
        val legacyResolver = resolverWith(legacyPreferences)

        val subtitle = legacyResolver.resolve(
            mediaItem = mediaItemOf(videoUri.toString()),
            savedExternalSubs = emptyList(),
        ).single()

        assertEquals(adjacentSrt.toString(), subtitle.id)
        assertEquals(
            "the converted subtitle is cached as UTF-8, keyed by its share URI",
            LEGACY_SUBRIP,
            File(subtitle.uri.path!!).readText(),
        )
        assertEquals(
            "the subtitle is read at the start of the file through the playing client",
            listOf("Movies/Movie.srt" to 0L),
            factory.clients.single().openStreamRequests,
        )
        assertEquals(
            "the client playing the video is reused rather than connected again",
            1,
            factory.clients.single().connectCalls,
        )
    }

    @Test
    fun `a local subtitle saved for a network video keeps the local conversion path`() = runBlocking {
        factory.files = listOf(NetworkFile("Movie.srt", "Movies/Movie.srt", false))
        factory.content = SUBRIP_UTF8.toByteArray(StandardCharsets.UTF_8)
        // A subtitle picked from this device for a video that lives on a share: the picker hands out a
        // content URI, and nothing but the ContentResolver can open one.
        val picked = Uri.parse("content://media/external/video/media/9")
        var resolverReads = 0
        Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(picked) {
            resolverReads++
            ByteArrayInputStream(LEGACY_SUBRIP.toByteArray(StandardCharsets.ISO_8859_1))
        }
        val legacyResolver = resolverWith(FakePreferencesRepository(subtitleTextEncoding = "windows-1252"))

        val configurations = legacyResolver.resolve(
            mediaItem = mediaItemOf(videoUri.toString()),
            savedExternalSubs = listOf(picked),
        )

        val saved = configurations.single { it.id == picked.toString() }
        assertEquals("the picked subtitle has to be converted locally", "file", saved.uri.scheme)
        assertEquals(LEGACY_SUBRIP, File(saved.uri.path!!).readText())
        assertTrue(
            "the picked subtitle has to be read through the ContentResolver",
            resolverReads > 0,
        )
        assertEquals(
            "only the subtitle that lives on the share is read through the network client",
            listOf("Movies/Movie.srt" to 0L),
            factory.clients.single().openStreamRequests,
        )
    }

    // Ordering and deduplication.

    @Test
    fun `the caller's subtitles come first, then the adjacent ones, then the saved external ones`() =
        runBlocking {
            factory.files = listOf(
                NetworkFile("Movie.ass", "Movies/Movie.ass", false),
                NetworkFile("Movie.srt", "Movies/Movie.srt", false),
            )

            val configurations = resolver.resolve(
                mediaItem = mediaItemOf(videoUri.toString(), listOf(configurationOf(pickedSubtitle))),
                savedExternalSubs = listOf(externalEn, externalVi),
            )

            assertEquals(
                listOf(
                    pickedSubtitle.toString(),
                    adjacentAss.toString(),
                    adjacentSrt.toString(),
                    externalEn.toString(),
                    externalVi.toString(),
                ),
                configurations.map(MediaItem.SubtitleConfiguration::id),
            )
        }

    @Test
    fun `a subtitle offered by two sources keeps its first position and is offered once`() = runBlocking {
        factory.files = listOf(NetworkFile("Movie.srt", "Movies/Movie.srt", false))

        val configurations = resolver.resolve(
            mediaItem = mediaItemOf(videoUri.toString(), listOf(configurationOf(adjacentSrt))),
            savedExternalSubs = listOf(adjacentSrt, externalEn),
        )

        assertEquals(
            listOf(adjacentSrt.toString(), externalEn.toString()),
            configurations.map(MediaItem.SubtitleConfiguration::id),
        )
    }

    @Test
    fun `resolving the same item twice produces the same subtitles in the same order`() = runBlocking {
        factory.files = listOf(
            NetworkFile("Movie.ass", "Movies/Movie.ass", false),
            NetworkFile("Movie.srt", "Movies/Movie.srt", false),
        )
        val item = mediaItemOf(videoUri.toString(), listOf(configurationOf(pickedSubtitle)))
        val externalSubs = listOf(externalEn, externalVi)

        val first = resolver.resolve(item, externalSubs)
        val second = resolver.resolve(item, externalSubs)

        assertEquals(first.map(MediaItem.SubtitleConfiguration::id), second.map(MediaItem.SubtitleConfiguration::id))
        assertEquals(first.map(MediaItem.SubtitleConfiguration::uri), second.map(MediaItem.SubtitleConfiguration::uri))
    }

    @Test
    fun `a saved external subtitle that also sits beside the video is not listed twice`() = runBlocking {
        val video = localMovie(subs = listOf("srt"), folderName = "local-external")
        val besideTheVideo = Uri.fromFile(File(video.parentFile, "Movie.srt"))

        val configurations = resolver.resolve(
            mediaItem = mediaItemOf(Uri.fromFile(video).toString()),
            savedExternalSubs = listOf(besideTheVideo),
            localMediaPath = video.path,
        )

        assertEquals(listOf(besideTheVideo.toString()), configurations.map(MediaItem.SubtitleConfiguration::id))
    }

    // Graceful failure.

    @Test
    fun `a video whose folder has no subtitles still keeps the caller's and the saved external ones`() =
        runBlocking {
            val video = localMovie(subs = emptyList(), folderName = "local-empty")
            val savedSubtitle = File(video.parentFile, "Picked.srt").also { it.writeText(SUBRIP_UTF8) }
            val saved = Uri.fromFile(savedSubtitle)

            val configurations = resolver.resolve(
                mediaItem = mediaItemOf(Uri.fromFile(video).toString(), listOf(configurationOf(pickedSubtitle))),
                savedExternalSubs = listOf(saved),
                localMediaPath = video.path,
            )

            assertEquals(
                listOf(pickedSubtitle.toString(), saved.toString()),
                configurations.map(MediaItem.SubtitleConfiguration::id),
            )
            assertTrue("a local video must not reach for a share", factory.clients.isEmpty())
        }

    @Test
    fun `a share that refuses the listing still keeps the other subtitle sources`() = runBlocking {
        factory.listFilesFails = true

        val configurations = resolver.resolve(
            mediaItem = mediaItemOf(videoUri.toString(), listOf(configurationOf(pickedSubtitle))),
            savedExternalSubs = listOf(externalEn),
        )

        assertEquals(
            listOf(pickedSubtitle.toString(), externalEn.toString()),
            configurations.map(MediaItem.SubtitleConfiguration::id),
        )
    }

    @Test
    fun `a share that cannot be reached still keeps the other subtitle sources`() = runBlocking {
        factory.failingConnectionIds = setOf(connection.id)

        val configurations = resolver.resolve(
            mediaItem = mediaItemOf(videoUri.toString(), listOf(configurationOf(pickedSubtitle))),
            savedExternalSubs = listOf(externalEn),
        )

        assertEquals(
            listOf(pickedSubtitle.toString(), externalEn.toString()),
            configurations.map(MediaItem.SubtitleConfiguration::id),
        )
    }

    @Test
    fun `an unreadable adjacent subtitle keeps its place and does not remove the other tracks`() = runBlocking {
        factory.files = listOf(
            NetworkFile("Movie.ass", "Movies/Movie.ass", false),
            NetworkFile("Movie.srt", "Movies/Movie.srt", false),
            NetworkFile("Movie.vtt", "Movies/Movie.vtt", false),
        )
        factory.failingOpenPaths = setOf("Movies/Movie.srt")

        val configurations = resolver.resolve(
            mediaItem = mediaItemOf(videoUri.toString(), listOf(configurationOf(pickedSubtitle))),
            savedExternalSubs = listOf(externalEn),
        )

        assertEquals(
            listOf(
                pickedSubtitle.toString(),
                adjacentAss.toString(),
                adjacentSrt.toString(),
                NetworkUri.build(connection, "Movies/Movie.vtt").toString(),
                externalEn.toString(),
            ),
            configurations.map(MediaItem.SubtitleConfiguration::id),
        )
    }

    @Test
    fun `a resolve cancelled while the listing is in flight propagates the cancellation`() = runBlocking {
        factory.listFilesSuspendsForever = true

        val outcome = runCatching {
            withTimeout(LISTING_TIMEOUT_MILLIS) {
                resolver.resolve(mediaItemOf(videoUri.toString()), savedExternalSubs = emptyList())
            }
        }

        assertTrue(
            "a cancelled resolve must not finish with a partial subtitle list",
            outcome.exceptionOrNull() is TimeoutCancellationException,
        )
    }

    // Enrichment as the service performs it.

    @Test
    fun `an smb video is enriched with the subtitles beside it and keeps everything else it came with`() =
        runBlocking {
            factory.files = listOf(NetworkFile("Movie.srt", "Movies/Movie.srt", false))
            val item = itemCarryingMetadata(listOf(configurationOf(pickedSubtitle)))

            val enriched = item.enrichedBy(
                savedExternalSubs = listOf(externalEn),
                localMediaPath = videoUri.toString(),
            )

            assertEquals(
                "the item's own subtitle stays first, so a stored subtitle index still points at it",
                listOf(pickedSubtitle.toString(), adjacentSrt.toString(), externalEn.toString()),
                enriched.localConfiguration?.subtitleConfigurations?.map(MediaItem.SubtitleConfiguration::id),
            )
            assertEquals(
                "the video's own folder is the only one looked in",
                listOf("Movies"),
                factory.clients.single().listedPaths,
            )
            assertEquals(videoUri.toString(), enriched.mediaId)
            assertEquals(videoUri.toString(), enriched.localConfiguration?.uri?.toString())
            assertMetadataUnchanged(enriched)
        }

    @Test
    fun `an smb video whose share refuses the listing is still enriched, unchanged`() = runBlocking {
        factory.listFilesFails = true
        val item = itemCarryingMetadata(listOf(configurationOf(pickedSubtitle)))

        val enriched = item.enrichedBy(
            savedExternalSubs = listOf(externalEn),
            localMediaPath = videoUri.toString(),
        )

        assertEquals(
            "the share was asked for the video's folder, and refused it",
            listOf("Movies"),
            factory.clients.single().listedPaths,
        )
        assertEquals(
            "no adjacent subtitle is added, but the item's own and the saved one survive",
            listOf(pickedSubtitle.toString(), externalEn.toString()),
            enriched.localConfiguration?.subtitleConfigurations?.map(MediaItem.SubtitleConfiguration::id),
        )
        assertEquals("the item still plays the video it came in with", videoUri.toString(), enriched.mediaId)
        assertEquals(videoUri.toString(), enriched.localConfiguration?.uri?.toString())
        assertMetadataUnchanged(enriched)
    }

    private fun resolverWith(preferencesRepository: PreferencesRepository) = MediaItemSubtitleResolver(
        context = context,
        networkSubtitleResolver = networkSubtitleResolver,
        preferencesRepository = preferencesRepository,
    )

    /**
     * A media item as the player receives one: its URI is what it plays and what identifies it, which
     * is also the only way a subtitle configuration already attached to it survives.
     */
    private fun mediaItemOf(
        mediaId: String,
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration> = emptyList(),
    ): MediaItem = MediaItem.Builder()
        .setUri(mediaId)
        .setMediaId(mediaId)
        .setSubtitleConfigurations(subtitleConfigurations)
        .build()

    private fun configurationOf(uri: Uri): MediaItem.SubtitleConfiguration =
        MediaItem.SubtitleConfiguration.Builder(uri)
            .setId(uri.toString())
            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLabel(uri.lastPathSegment.orEmpty())
            .build()

    /** Creates a folder holding a video file and, for every extension in [subs], a subtitle beside it. */
    private fun localMovie(subs: List<String>, folderName: String): File {
        val folder = tempFolder.newFolder(folderName)
        subs.forEach { extension -> File(folder, "Movie.$extension").writeText(SUBRIP_UTF8) }
        return File(folder, "Movie.mkv").also { it.writeText("video") }
    }

    /**
     * An item as the service receives one: already carrying the title, artwork and every piece of
     * saved playback state it enriched from the database, plus the subtitle configurations the caller
     * supplied. All of it has to survive enrichment untouched.
     */
    private fun itemCarryingMetadata(
        subtitleConfigurations: List<MediaItem.SubtitleConfiguration>,
    ): MediaItem = MediaItem.Builder()
        .setUri(videoUri.toString())
        .setMediaId(videoUri.toString())
        .setSubtitleConfigurations(subtitleConfigurations)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(MOVIE_TITLE)
                .setArtworkUri(artworkUri)
                .setExtras(
                    positionMs = POSITION_MILLIS,
                    videoScale = VIDEO_SCALE,
                    playbackSpeed = PLAYBACK_SPEED,
                    audioTrackIndex = AUDIO_TRACK_INDEX,
                    subtitleTrackIndex = SUBTITLE_TRACK_INDEX,
                    subtitleDelayMilliseconds = SUBTITLE_DELAY_MILLIS,
                    subtitleSpeed = SUBTITLE_SPEED,
                    videoDecoderMode = DecoderMode.FFMPEG,
                    audioDecoderMode = DecoderMode.SOFTWARE,
                )
                .build(),
        )
        .build()

    /**
     * The enrichment the service performs on every item before handing it to the player: ask for the
     * item's subtitles, put them on the item, carry everything else over.
     *
     * [localMediaPath] is what the caller stored as the item's path, which for a video on a share is
     * its own URI; it must not send a network video looking at the filesystem.
     */
    private suspend fun MediaItem.enrichedBy(
        savedExternalSubs: List<Uri>,
        localMediaPath: String,
    ): MediaItem {
        val subtitleConfigurations = resolver.resolve(
            mediaItem = this,
            savedExternalSubs = savedExternalSubs,
            localMediaPath = localMediaPath,
        )
        return buildUpon().setSubtitleConfigurations(subtitleConfigurations).build()
    }

    /** Asserts, value by value, that enrichment left every non-subtitle piece of the item alone. */
    private fun assertMetadataUnchanged(enriched: MediaItem) {
        val metadata = enriched.mediaMetadata
        assertEquals(MOVIE_TITLE, metadata.title)
        assertEquals(artworkUri, metadata.artworkUri)
        assertEquals(POSITION_MILLIS, metadata.positionMs)
        assertEquals(VIDEO_SCALE, metadata.videoZoom)
        assertEquals(PLAYBACK_SPEED, metadata.playbackSpeed)
        assertEquals(AUDIO_TRACK_INDEX, metadata.audioTrackIndex)
        assertEquals(SUBTITLE_TRACK_INDEX, metadata.subtitleTrackIndex)
        assertEquals(SUBTITLE_DELAY_MILLIS, metadata.subtitleDelayMilliseconds)
        assertEquals(SUBTITLE_SPEED, metadata.subtitleSpeed)
        assertEquals(DecoderMode.FFMPEG, metadata.videoDecoderMode)
        assertEquals(DecoderMode.SOFTWARE, metadata.audioDecoderMode)
    }

    private companion object {
        const val LISTING_TIMEOUT_MILLIS = 100L

        /** Distinguishable values, so a swapped pair of metadata fields cannot pass unnoticed. */
        const val MOVIE_TITLE = "Movie.mkv"
        const val POSITION_MILLIS = 42_000L
        const val VIDEO_SCALE = 1.25f
        const val PLAYBACK_SPEED = 1.5f
        const val AUDIO_TRACK_INDEX = 3
        const val SUBTITLE_TRACK_INDEX = 4
        const val SUBTITLE_DELAY_MILLIS = -250L
        const val SUBTITLE_SPEED = 0.75f

        /** A UTF-8 subscript the charset detector recognises without a configured encoding. */
        const val SUBRIP_UTF8: String =
            "1\n00:00:01,000 --> 00:00:04,000\nXin chào thế giới, đây là phụ đề tiếng Việt.\n"

        /** Latin-1 recoverable content, so conversion through windows-1252 is observable. */
        const val LEGACY_SUBRIP: String =
            "1\n00:00:01,000 --> 00:00:04,000\nChao cac ban, day la phu de: cà phê, résumé, ñoño.\n"
    }
}

/**
 * Records which folder the resolver listed, which file it opened and how it was reached, so a test
 * can tell a share lookup from a local one and a reused client from a fresh one.
 */
private class FakeNetworkClient(
    override val rootPath: String,
    private val connectSucceeds: Boolean,
    private val files: List<NetworkFile>,
    private val content: ByteArray,
) : NetworkClient {

    var connectCalls = 0
        private set

    /** The paths [listFiles] was asked for, in call order. */
    val listedPaths = mutableListOf<String>()

    /** The path and offset [openStream] was asked for, in call order. */
    val openStreamRequests = mutableListOf<Pair<String, Long>>()

    /** Fails every listing, the way a share that stopped answering would. */
    var listFilesFails = false

    /** Leaves the listing suspended until its caller is cancelled. */
    var listFilesSuspendsForever = false

    /** Subtitles whose stream cannot be opened, the way an unreadable file would. */
    var failingOpenPaths: Set<String> = emptySet()

    private var connected = false

    override fun isConnected(): Boolean = connected

    override suspend fun connect(): Result<Unit> {
        connectCalls++
        connected = connectSucceeds
        return if (connectSucceeds) {
            Result.success(Unit)
        } else {
            Result.failure(IOException("connect failed"))
        }
    }

    override suspend fun disconnect() {
        connected = false
    }

    override suspend fun listFiles(path: String): Result<List<NetworkFile>> {
        listedPaths += path
        if (listFilesSuspendsForever) awaitCancellation()
        return if (listFilesFails) {
            Result.failure(IOException("listing failed for $path"))
        } else {
            Result.success(files)
        }
    }

    override suspend fun fileSize(path: String): Long = content.size.toLong()

    override suspend fun openStream(path: String, offset: Long): InputStream {
        openStreamRequests += path to offset
        if (path in failingOpenPaths) throw IOException("unreadable subtitle: $path")
        return ByteArrayInputStream(content, offset.toInt(), content.size - offset.toInt())
    }
}

/** Hands out [FakeNetworkClient]s, remembering them in creation order. */
private class FakeNetworkClientFactory : NetworkClientFactory {

    val clients = mutableListOf<FakeNetworkClient>()

    /** The folder contents its clients hand back for whatever path they are asked to list. */
    var files: List<NetworkFile> = emptyList()

    /** The bytes its clients hand back for whatever subtitle they are asked to open. */
    var content: ByteArray = ByteArray(0)

    /** Makes every client it creates fail its listing. */
    var listFilesFails = false

    /** Makes every client it creates leave its listing suspended. */
    var listFilesSuspendsForever = false

    /** Makes every client it creates refuse the open of those paths. */
    var failingOpenPaths: Set<String> = emptySet()

    /** Connections whose client refuses to connect, for exercising an unreachable share. */
    var failingConnectionIds: Set<Long> = emptySet()

    override fun create(connection: NetworkConnection): NetworkClient {
        val client = FakeNetworkClient(
            rootPath = connection.path,
            connectSucceeds = connection.id !in failingConnectionIds,
            files = files,
            content = content,
        )
        client.listFilesFails = listFilesFails
        client.listFilesSuspendsForever = listFilesSuspendsForever
        client.failingOpenPaths = failingOpenPaths
        return client.also(clients::add)
    }
}

/** Supplies the subtitle encoding the resolver converts with; the write side is not exercised here. */
private class FakePreferencesRepository(
    subtitleTextEncoding: String = "",
) : PreferencesRepository {

    private val applicationPreferencesState = MutableStateFlow(ApplicationPreferences())
    private val playerPreferencesState = MutableStateFlow(PlayerPreferences(subtitleTextEncoding = subtitleTextEncoding))

    override val applicationPreferences: StateFlow<ApplicationPreferences> = applicationPreferencesState

    override val playerPreferences: StateFlow<PlayerPreferences> = playerPreferencesState

    override suspend fun updateApplicationPreferences(
        transform: suspend (ApplicationPreferences) -> ApplicationPreferences,
    ) {
        applicationPreferencesState.value = transform(applicationPreferencesState.value)
    }

    override suspend fun updatePlayerPreferences(
        transform: suspend (PlayerPreferences) -> PlayerPreferences,
    ) {
        playerPreferencesState.value = transform(playerPreferencesState.value)
    }

    override suspend fun resetPreferences() {
        applicationPreferencesState.value = ApplicationPreferences()
        playerPreferencesState.value = PlayerPreferences()
    }
}
