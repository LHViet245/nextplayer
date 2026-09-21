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
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A subtitle next to a video has to be read through the client that is already playing the video,
 * so the session layer has to hand that client out instead of the caller opening its own
 * connection. These cover the session operation itself; the subtitle lookup built on top arrives
 * with the tests that follow this one.
 */
@RunWith(RobolectricTestRunner::class)
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
    private val connections = listOf(videoConnection, otherConnection)

    private val factory = FakeNetworkClientFactory()
    private val sessions = NetworkSessions(
        resolver = { id -> connections.firstOrNull { it.id == id } },
        clientFactory = factory,
    )

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
 * one and see that a replaced connection is really disconnected.
 */
class FakeNetworkClient(
    override val rootPath: String = "",
    private val connectSucceeds: Boolean = true,
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
        return Result.success(files)
    }

    override suspend fun fileSize(path: String): Long = content.size.toLong()

    override suspend fun openStream(path: String, offset: Long): InputStream {
        openStreamCalls++
        return ByteArrayInputStream(content, offset.toInt(), content.size - offset.toInt())
    }
}

/** Hands out [FakeNetworkClient]s, remembering them in creation order. */
class FakeNetworkClientFactory(
    /** Connections whose client refuses to connect, for exercising a broken switch. */
    var failingConnectionIds: Set<Long> = emptySet(),
) : NetworkClientFactory {

    val clients = mutableListOf<FakeNetworkClient>()

    override fun create(connection: NetworkConnection): NetworkClient = FakeNetworkClient(
        rootPath = connection.path,
        connectSucceeds = connection.id !in failingConnectionIds,
    ).also(clients::add)
}
