package dev.anilbeesetti.nextplayer.core.media.network

import android.net.Uri
import dev.anilbeesetti.nextplayer.core.media.network.datasource.NetworkSessions
import dev.anilbeesetti.nextplayer.core.model.NetworkConnection
import dev.anilbeesetti.nextplayer.core.model.NetworkFile
import dev.anilbeesetti.nextplayer.core.model.NetworkProtocol
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A subtitle next to a video has to be read through the client that is already playing the video,
 * so the session layer has to hand that client out instead of the caller opening its own
 * connection. The first tests cover that session operation; the rest cover the subtitle lookup
 * built on top of it, which lists the video's own folder and has to stay quiet when it cannot.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkSubtitleResolverTest {

    private val videoConnection = NetworkConnection(
        id = 1,
        name = "NAS",
        protocol = NetworkProtocol.SMB,
        host = "192.168.1.10",
        path = "Media",
    )
    private val otherConnection = videoConnection.copy(
        id = 2,
        name = "Other NAS",
        host = "192.168.1.20",
    )

    /** A connection that names its port, so a subtitle URI can be checked for carrying it. */
    private val portConnection = videoConnection.copy(
        id = 3,
        name = "Pinned port NAS",
        host = "nas.local",
        port = 445,
    )
    private val connections = listOf(videoConnection, otherConnection, portConnection)

    private val factory = FakeNetworkClientFactory()
    private val sessions = NetworkSessions(
        resolver = { id -> connections.firstOrNull { it.id == id } },
        clientFactory = factory,
    )
    private val resolver = NetworkSubtitleResolver(sessions)

    private val videoUri = uriOf(videoConnection, "Movies/Movie.mkv")
    private val subtitleUri = uriOf(videoConnection, "Movies/Movie.srt")
    private val otherUri = uriOf(otherConnection, "Movies/Other.mkv")

    @Test
    fun `withTarget reuses connected client for same connection`() = runTest {
        val first = sessions.withTarget(videoUri) { _, client, path -> client to path }
        val second = sessions.withTarget(subtitleUri) { _, client, path -> client to path }

        assertSame(first.first, second.first)
        assertEquals("Movies/Movie.mkv", first.second)
        assertEquals("Movies/Movie.srt", second.second)
        assertEquals(1, factory.clients.single().connectCalls)
    }

    @Test
    fun `target still hands out the connected client and its path`() = runTest {
        val video = sessions.target(videoUri)
        val subtitle = sessions.target(subtitleUri)

        assertSame(video.client, subtitle.client)
        assertEquals("Movies/Movie.mkv", video.filePath)
        assertEquals("Movies/Movie.srt", subtitle.filePath)
        assertEquals(1, factory.clients.single().connectCalls)
    }

    @Test
    fun `switching connections replaces the client and disconnects the old one`() = runTest {
        sessions.target(videoUri)
        sessions.target(otherUri)

        assertEquals(2, factory.clients.size)
        val previous = factory.clients[0]
        assertNotSame(previous, factory.clients[1])

        previous.awaitDisconnects(1)
        assertEquals(1, previous.disconnectCalls)
    }

    @Test
    fun `a connection that fails to connect does not replace the working client`() = runTest {
        sessions.target(videoUri)
        val working = factory.clients.single()
        factory.failingConnectionIds = setOf(otherConnection.id)

        val failure = runCatching { sessions.target(otherUri) }

        assertTrue("the connect failure should reach the caller", failure.isFailure)
        assertSame(working, sessions.target(videoUri).client)
        assertEquals(1, working.connectCalls)
    }

    @Test
    fun `the caller block runs outside the session lock`() = runTest {
        // A nested session call would deadlock if the block still held the lock.
        val nested = sessions.withTarget(videoUri) { _, _, _ ->
            sessions.withTarget(subtitleUri) { _, client, path -> client to path }
        }

        assertEquals("Movies/Movie.srt", nested.second)
        assertEquals(1, factory.clients.single().connectCalls)
    }

    @Test
    fun `subtitle siblings including language suffixed ones are discovered`() = runTest {
        factory.files = listOf(
            NetworkFile("Movie.srt", "Movies/Movie.srt", false),
            NetworkFile("Movie.ASS", "Movies/Movie.ASS", false),
            NetworkFile("OtherMovie.srt", "Movies/OtherMovie.srt", false),
            NetworkFile("Movie.en.srt", "Movies/Movie.en.srt", false),
            NetworkFile("Movie.720p.srt", "Movies/Movie.720p.srt", false),
            NetworkFile("Movie.vtt", "Movies/Movie.vtt", true),
        )

        val subtitles = resolver.findAdjacentSubtitles(videoUri)

        assertEquals(
            listOf(
                uriOf(videoConnection, "Movies/Movie.ASS"),
                uriOf(videoConnection, "Movies/Movie.en.srt"),
                uriOf(videoConnection, "Movies/Movie.srt"),
            ),
            subtitles,
        )
        assertEquals(
            "the video's own folder is the only one listed",
            listOf("Movies"),
            factory.clients.single().listedPaths,
        )
    }

    @Test
    fun `discovers language suffixed subtitles like vietnamese and japanese`() = runTest {
        factory.files = listOf(
            NetworkFile("SNOS-393.mp4", "Movies/SNOS-393.mp4", false),
            NetworkFile("SNOS-393.ja.srt", "Movies/SNOS-393.ja.srt", false),
            NetworkFile("SNOS-393.vi.srt", "Movies/SNOS-393.vi.srt", false),
        )

        val subtitles = resolver.findAdjacentSubtitles(uriOf(videoConnection, "Movies/SNOS-393.mp4"))

        assertEquals(
            listOf(
                uriOf(videoConnection, "Movies/SNOS-393.ja.srt"),
                uriOf(videoConnection, "Movies/SNOS-393.vi.srt"),
            ),
            subtitles,
        )
    }

    @Test
    fun `every supported subtitle extension matches, ignoring case`() = runTest {
        factory.files = listOf(
            NetworkFile("movie.srt", "Movies/movie.srt", false),
            NetworkFile("MOVIE.SSA", "Movies/MOVIE.SSA", false),
            NetworkFile("Movie.Ass", "Movies/Movie.Ass", false),
            NetworkFile("movie.vtt", "Movies/movie.vtt", false),
            NetworkFile("movie.TTML", "Movies/movie.TTML", false),
            // The video itself, a name that only starts like the video's, an extensionless file,
            // and a file whose real extension is not a subtitle one.
            NetworkFile("Movie.mkv", "Movies/Movie.mkv", false),
            NetworkFile("Movie.srt.bak", "Movies/Movie.srt.bak", false),
            NetworkFile("Movie", "Movies/Movie", false),
            NetworkFile("Movie.txt", "Movies/Movie.txt", false),
        )

        val names = resolver.findAdjacentSubtitles(videoUri).map(Uri::getLastPathSegment)

        assertEquals(
            listOf("Movie.Ass", "movie.srt", "MOVIE.SSA", "movie.TTML", "movie.vtt"),
            names,
        )
    }

    @Test
    fun `a subtitle the server lists twice is returned once`() = runTest {
        factory.files = listOf(
            NetworkFile("Movie.srt", "Movies/Movie.srt", false),
            NetworkFile("Movie.srt", "Movies/Movie.srt", false),
        )

        val subtitles = resolver.findAdjacentSubtitles(videoUri)

        assertEquals(listOf(uriOf(videoConnection, "Movies/Movie.srt")), subtitles)
    }

    @Test
    fun `a video at the root of the share lists the root folder`() = runTest {
        factory.files = listOf(
            NetworkFile("Movie.srt", "Movie.srt", false),
            NetworkFile("Other.srt", "Other.srt", false),
        )

        val subtitles = resolver.findAdjacentSubtitles(uriOf(videoConnection, "Movie.mkv"))

        assertEquals(listOf(uriOf(videoConnection, "Movie.srt")), subtitles)
        assertEquals(listOf(""), factory.clients.single().listedPaths)
    }

    @Test
    fun `a subtitle uri keeps the scheme, host, port, encoded path and connection id`() = runTest {
        factory.files = listOf(
            NetworkFile("Épisode 1.srt", "Shows/My Show/Épisode 1.srt", false),
        )

        val subtitles = resolver.findAdjacentSubtitles(uriOf(portConnection, "Shows/My Show/Épisode 1.mkv"))

        val subtitle = subtitles.single()
        assertEquals("smb", subtitle.scheme)
        assertEquals("nas.local", subtitle.host)
        assertEquals(445, subtitle.port)
        assertEquals("/Shows/My%20Show/%C3%89pisode%201.srt", subtitle.encodedPath)
        assertEquals(portConnection.id.toString(), subtitle.getQueryParameter("cid"))
        assertEquals(
            "Shows/My Show/Épisode 1.srt",
            NetworkUri.filePathOf(subtitle, NetworkProtocol.SMB),
        )
    }

    @Test
    fun `a failing listing is contained and yields no subtitles`() = runTest {
        factory.files = listOf(NetworkFile("Movie.srt", "Movies/Movie.srt", false))
        factory.listFilesFails = true

        val subtitles = resolver.findAdjacentSubtitles(videoUri)

        assertEquals(emptyList<Uri>(), subtitles)
    }

    @Test
    fun `a connection that fails to connect yields no subtitles`() = runTest {
        factory.failingConnectionIds = setOf(videoConnection.id)

        val subtitles = resolver.findAdjacentSubtitles(videoUri)

        assertEquals(emptyList<Uri>(), subtitles)
    }

    @Test
    fun `a non-network uri yields no subtitles without opening a client`() = runTest {
        val localUri = Uri.parse("content://media/external/video/media/42")

        val subtitles = resolver.findAdjacentSubtitles(localUri)

        assertEquals(emptyList<Uri>(), subtitles)
        assertTrue("no connection should be created for a local video", factory.clients.isEmpty())
    }

    @Test
    fun `openStream reads the subtitle through the client playing its video, at offset zero`() = runTest {
        factory.files = listOf(NetworkFile("Movie.srt", "Movies/Movie.srt", false))
        val subtitle = resolver.findAdjacentSubtitles(videoUri).single()

        resolver.openStream(subtitle).close()

        val client = factory.clients.single()
        assertEquals(listOf("Movies/Movie.srt" to 0L), client.openStreamRequests)
        assertEquals("the playing client is reused rather than reconnected", 1, client.connectCalls)
    }

    @Test
    fun `a lookup cancelled while its listing is in flight propagates the cancellation`() = runTest {
        sessions.target(videoUri)
        factory.clients.single().listFilesSuspendsForever = true
        var returned = false

        val lookup = launch {
            resolver.findAdjacentSubtitles(videoUri)
            returned = true
        }
        runCurrent()
        assertEquals(
            "the lookup has to be inside the listing before it is cancelled",
            listOf("Movies"),
            factory.clients.single().listedPaths,
        )
        lookup.cancelAndJoin()

        assertFalse("a cancelled lookup must not finish with an empty subtitle list", returned)
    }

    @Test
    fun `an openStream failure reaches its caller and leaves discovery working`() = runTest {
        factory.files = listOf(NetworkFile("Movie.srt", "Movies/Movie.srt", false))
        val subtitle = resolver.findAdjacentSubtitles(videoUri).single()
        factory.clients.single().openStreamFails = true

        val failure = runCatching { resolver.openStream(subtitle) }

        assertTrue(
            "the open failure must reach whoever opens the subtitle",
            failure.exceptionOrNull() is IOException,
        )
        assertEquals(1, resolver.findAdjacentSubtitles(videoUri).size)
    }

    /**
     * `disconnect()` of a replaced connection is scheduled on [Dispatchers.IO] and deliberately not
     * awaited, so a test has to wait for it in real time rather than assume it already ran.
     */
    private suspend fun FakeNetworkClient.awaitDisconnects(expected: Int) {
        withContext(Dispatchers.Default) {
            withTimeout(DISCONNECT_TIMEOUT_MILLIS) {
                while (disconnectCalls < expected) delay(DISCONNECT_POLL_MILLIS)
            }
        }
    }

    private fun uriOf(connection: NetworkConnection, filePath: String): Uri =
        NetworkUri.build(connection, filePath)

    private companion object {
        const val DISCONNECT_TIMEOUT_MILLIS = 5_000L
        const val DISCONNECT_POLL_MILLIS = 5L
    }
}

/**
 * Records what the session layer asks of a client, so a test can tell a reused client from a fresh
 * one, see that a replaced connection is really disconnected, and see which folder a subtitle
 * lookup listed or which file it opened.
 */
class FakeNetworkClient(
    override val rootPath: String = "",
    private val connectSucceeds: Boolean = true,
    /** What [listFiles] hands back, unless [listFilesFails] is set. */
    private val files: List<NetworkFile> = emptyList(),
    private val content: ByteArray = ByteArray(0),
) : NetworkClient {

    var connectCalls = 0
        private set
    var disconnectCalls = 0
        private set
    var listFilesCalls = 0
        private set
    var openStreamCalls = 0
        private set

    /** The paths [listFiles] was asked for, in call order. */
    val listedPaths = mutableListOf<String>()

    /** The path and offset [openStream] was asked for, in call order. */
    val openStreamRequests = mutableListOf<Pair<String, Long>>()

    /** Fails every listing, the way a connection that dropped mid-playback would. */
    var listFilesFails = false

    /** Fails every [openStream], the way an unreadable subtitle would. */
    var openStreamFails = false

    /** Leaves the listing suspended until its caller is cancelled, for pinning cancellation. */
    var listFilesSuspendsForever = false

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
        disconnectCalls++
        connected = false
    }

    override suspend fun listFiles(path: String): Result<List<NetworkFile>> {
        listFilesCalls++
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
        openStreamCalls++
        openStreamRequests += path to offset
        if (openStreamFails) throw IOException("open failed for $path")
        return ByteArrayInputStream(content, offset.toInt(), content.size - offset.toInt())
    }
}

/** Hands out [FakeNetworkClient]s, remembering them in creation order. */
class FakeNetworkClientFactory(
    /** Connections whose client refuses to connect, for exercising a broken switch. */
    var failingConnectionIds: Set<Long> = emptySet(),
) : NetworkClientFactory {

    val clients = mutableListOf<FakeNetworkClient>()

    /** The folder contents its clients hand back for whatever path they are asked to list. */
    var files: List<NetworkFile> = emptyList()

    /** Makes every client it creates fail its listing. */
    var listFilesFails: Boolean = false

    override fun create(connection: NetworkConnection): NetworkClient {
        val client = FakeNetworkClient(
            rootPath = connection.path,
            connectSucceeds = connection.id !in failingConnectionIds,
            files = files,
        )
        client.listFilesFails = listFilesFails
        return client.also(clients::add)
    }
}
