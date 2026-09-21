package dev.anilbeesetti.nextplayer.feature.player.extensions

import android.net.Uri
import androidx.media3.common.MimeTypes
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class SubtitleConfigurationTest {

    @Test
    fun networkUtf8SubtitleKeepsOriginalUriAndId() = runBlocking {
        val uri = Uri.parse("smb://server/share/Movie.vi.srt")
        val streams = mutableListOf<TrackingInputStream>()

        val configuration = RuntimeEnvironment.getApplication().uriToSubtitleConfiguration(
            uri = uri,
            isSelected = true,
        ) {
            TrackingInputStream(VIETNAMESE_UTF8.toByteArray(StandardCharsets.UTF_8)).also(streams::add)
        }

        assertEquals(uri, configuration.uri)
        assertEquals(uri.toString(), configuration.id)
        assertEquals(1, streams.size)
        assertTrue("the detection stream must be closed", streams.single().isClosed)
    }

    @Test
    fun legacyEncodedNetworkSubtitleIsStoredAsUtf8CacheFile() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("smb://server/share/Movie.srt")
        val streams = mutableListOf<TrackingInputStream>()

        val configuration = context.uriToSubtitleConfiguration(
            uri = uri,
            subtitleEncoding = "windows-1252",
        ) {
            TrackingInputStream(LEGACY_TEXT.toByteArray(StandardCharsets.ISO_8859_1)).also(streams::add)
        }

        val cached = File(configuration.uri.path!!)
        assertNotEquals(uri, configuration.uri)
        assertEquals("file", configuration.uri.scheme)
        assertTrue("the cache file must exist", cached.isFile)
        assertTrue("the cache name must keep the subtitle name", cached.name.endsWith("-Movie.srt"))
        assertEquals(LEGACY_TEXT, cached.readText(StandardCharsets.UTF_8))
        assertEquals(uri.toString(), configuration.id)
        assertEquals(MimeTypes.APPLICATION_SUBRIP, configuration.mimeType)
        assertTrue("the conversion stream must be closed", streams.single().isClosed)
    }

    @Test
    fun legacyEncodingIsDetectedFromStreamAndProviderIsReopened() = runBlocking {
        val uri = Uri.parse("smb://server/share/Movie.srt")
        val streams = mutableListOf<TrackingInputStream>()

        val configuration = RuntimeEnvironment.getApplication().uriToSubtitleConfiguration(uri = uri) {
            TrackingInputStream(LEGACY_TEXT.toByteArray(StandardCharsets.ISO_8859_1)).also(streams::add)
        }

        val cached = File(configuration.uri.path!!)
        assertEquals(2, streams.size)
        assertTrue("the detection stream must be closed", streams[0].isClosed)
        assertTrue("the conversion stream must be closed", streams[1].isClosed)
        assertEquals(LEGACY_TEXT, cached.readText(StandardCharsets.UTF_8))
        assertEquals(uri.toString(), configuration.id)
    }

    @Test
    fun sameFilenameOnDifferentSharesUsesDistinctCacheFiles() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val first = Uri.parse("smb://server-a/share/Movie.srt")
        val second = Uri.parse("smb://server-b/share/Movie.srt")

        val firstConfiguration = context.uriToSubtitleConfiguration(first, "windows-1252") {
            TrackingInputStream("first subtitle\n".toByteArray(StandardCharsets.ISO_8859_1))
        }
        val secondConfiguration = context.uriToSubtitleConfiguration(second, "windows-1252") {
            TrackingInputStream("second subtitle\n".toByteArray(StandardCharsets.ISO_8859_1))
        }

        assertNotEquals(firstConfiguration.uri, secondConfiguration.uri)
        assertEquals("first subtitle\n", File(firstConfiguration.uri.path!!).readText())
        assertEquals("second subtitle\n", File(secondConfiguration.uri.path!!).readText())
    }

    @Test
    fun failingProviderFallsBackToOriginalUri() = runBlocking {
        val uri = Uri.parse("smb://server/share/Movie.srt")

        val configuration = RuntimeEnvironment.getApplication().uriToSubtitleConfiguration(uri = uri) {
            throw IOException("share went away")
        }

        assertEquals(uri, configuration.uri)
        assertEquals(uri.toString(), configuration.id)
    }

    @Test
    fun failingConversionStreamFallsBackToOriginalUri() = runBlocking {
        val uri = Uri.parse("smb://server/share/Movie.srt")
        var invocations = 0

        val configuration = RuntimeEnvironment.getApplication().uriToSubtitleConfiguration(uri = uri) {
            invocations++
            if (invocations == 1) {
                TrackingInputStream(LEGACY_TEXT.toByteArray(StandardCharsets.ISO_8859_1))
            } else {
                throw IOException("share went away")
            }
        }

        assertEquals(uri, configuration.uri)
        assertEquals(uri.toString(), configuration.id)
    }

    @Test
    fun withoutProviderSubtitleIsStillReadThroughTheContentResolver() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("smb://server/share/Movie.srt")
        val streams = mutableListOf<TrackingInputStream>()
        Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            TrackingInputStream(VIETNAMESE_UTF8.toByteArray(StandardCharsets.UTF_8)).also(streams::add)
        }

        val configuration = context.uriToSubtitleConfiguration(uri = uri)

        assertEquals(uri, configuration.uri)
        assertEquals(uri.toString(), configuration.id)
        assertEquals(1, streams.size)
        assertTrue("the resolver stream must be closed", streams.single().isClosed)
    }

    @Test
    fun subtitleMimeMappingIsPreserved() {
        assertEquals(MimeTypes.APPLICATION_SUBRIP, Uri.parse("file:///movie.srt").getSubtitleMime())
        assertEquals(MimeTypes.TEXT_SSA, Uri.parse("file:///movie.ssa").getSubtitleMime())
        assertEquals(MimeTypes.TEXT_SSA, Uri.parse("file:///movie.ass").getSubtitleMime())
        assertEquals(MimeTypes.TEXT_VTT, Uri.parse("file:///movie.vtt").getSubtitleMime())
        assertEquals(MimeTypes.APPLICATION_TTML, Uri.parse("file:///movie.ttml").getSubtitleMime())
        assertEquals(MimeTypes.APPLICATION_TTML, Uri.parse("file:///movie.xml").getSubtitleMime())
        assertEquals(MimeTypes.APPLICATION_TTML, Uri.parse("file:///movie.dfxp").getSubtitleMime())
        assertEquals(MimeTypes.APPLICATION_SUBRIP, Uri.parse("file:///movie.txt").getSubtitleMime())
    }

    @Test
    fun subtitleMimeMappingIgnoresCase() {
        assertEquals(MimeTypes.TEXT_SSA, Uri.parse("smb://server/share/MOVIE.SSA").getSubtitleMime())
        assertEquals(MimeTypes.TEXT_SSA, Uri.parse("smb://server/share/MOVIE.ASS").getSubtitleMime())
        assertEquals(MimeTypes.TEXT_VTT, Uri.parse("smb://server/share/MOVIE.VTT").getSubtitleMime())
        assertEquals(MimeTypes.APPLICATION_TTML, Uri.parse("smb://server/share/MOVIE.TTML").getSubtitleMime())
        assertEquals(MimeTypes.APPLICATION_TTML, Uri.parse("smb://server/share/MOVIE.XML").getSubtitleMime())
        assertEquals(MimeTypes.APPLICATION_TTML, Uri.parse("smb://server/share/MOVIE.DFXP").getSubtitleMime())
        assertEquals(MimeTypes.APPLICATION_SUBRIP, Uri.parse("smb://server/share/MOVIE.SRT").getSubtitleMime())
        assertEquals(MimeTypes.TEXT_VTT, Uri.parse("smb://server/share/Movie.VtT").getSubtitleMime())
    }

    /**
     * A stream that reports whether it was closed, so the tests can assert that the conversion path
     * does not leak the streams the provider hands out.
     */
    private class TrackingInputStream(bytes: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)

        var isClosed: Boolean = false
            private set

        override fun read(): Int = delegate.read()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length)

        override fun close() {
            isClosed = true
        }
    }

    private companion object {
        /** Multi byte UTF-8 payload, so detection cannot mistake it for a single byte encoding. */
        const val VIETNAMESE_UTF8: String =
            "1\n00:00:01,000 --> 00:00:04,000\nXin chào thế giới, đây là phụ đề tiếng Việt.\n"

        /** Latin-1 recoverable payload: every character round trips through windows-1252. */
        const val LEGACY_TEXT: String =
            "1\n00:00:01,000 --> 00:00:04,000\nChao cac ban, day la phu de: cà phê, résumé, ñoño, üben.\n"
    }
}