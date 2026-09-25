package dev.anilbeesetti.nextplayer.core.common.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleSiblingsTest {

    private val video = "Movie.mkv"

    private fun languageOf(sibling: String): String? =
        SubtitleSiblings.match(video, sibling)?.languageCode

    @Test
    fun `a sibling named exactly after the video matches with no language`() {
        assertEquals(SubtitleMatch(null), SubtitleSiblings.match(video, "Movie.srt"))
    }

    @Test
    fun `an unsupported extension is not a subtitle`() {
        assertNull(SubtitleSiblings.match(video, "Movie.txt"))
        assertNull(SubtitleSiblings.match(video, "Movie"))
    }

    @Test
    fun `a language suffix matches and reports its language`() {
        assertEquals("vie", languageOf("Movie.vi.srt"))
        assertEquals("eng", languageOf("Movie.en.srt"))
        assertEquals("jpn", languageOf("Movie.ja.srt"))
    }

    @Test
    fun `matching is case insensitive on the name and the extension`() {
        assertEquals("vie", languageOf("MOVIE.VI.SRT"))
        assertEquals(SubtitleMatch(null), SubtitleSiblings.match(video, "movie.SRT"))
    }

    @Test
    fun `a language code is found anywhere among the trailing tokens`() {
        assertEquals("vie", languageOf("Movie.vi.HDTV.srt"))
        assertEquals("vie", languageOf("Movie.720p.vi.srt"))
    }

    @Test
    fun `a trailing token that is not a language leaves the file unmatched`() {
        assertNull(SubtitleSiblings.match(video, "Movie.720p.srt"))
    }

    @Test
    fun `a different basename is not a sibling`() {
        assertNull(SubtitleSiblings.match(video, "OtherMovie.vi.srt"))
        assertNull(SubtitleSiblings.match(video, "Movie2.vi.srt"))
    }

    @Test
    fun `a region or script form reports its base language`() {
        assertEquals("por", languageOf("Movie.pt-BR.srt"))
        assertEquals("zho", languageOf("Movie.zh-Hans.srt"))
        assertEquals("srp", languageOf("Movie.sr-Latn.srt"))
    }

    @Test
    fun `a three letter code is recognised and a 639-2 B form maps to its T form`() {
        assertEquals("eng", languageOf("Movie.eng.srt"))
        assertEquals("zho", languageOf("Movie.chi.srt"))
    }

    @Test
    fun `a two letter code that is also an english word counts only when it stands alone`() {
        assertEquals("nor", languageOf("Movie.no.srt"))
        assertNull(SubtitleSiblings.match(video, "Movie.no.HDTV.srt"))
        assertEquals("ita", languageOf("Movie.it.srt"))
        assertNull(SubtitleSiblings.match(video, "Movie.it.1080p.srt"))
    }

    @Test
    fun `an unguarded two letter code counts even when it is not alone`() {
        assertEquals("vie", languageOf("Movie.vi.1080p.srt"))
    }

    @Test
    fun `the longest most specific code wins and ties keep token order`() {
        assertEquals("por", languageOf("Movie.vi.pt-BR.srt"))
        assertEquals("vie", languageOf("Movie.vi.en.srt"))
    }

    @Test
    fun `a multi dot video name anchors on its whole stem`() {
        val multiDot = "Movie.2024.1080p.mkv"

        assertEquals(SubtitleMatch(null), SubtitleSiblings.match(multiDot, "Movie.2024.1080p.srt"))
        assertEquals(
            "vie",
            SubtitleSiblings.match(multiDot, "Movie.2024.1080p.vi.srt")?.languageCode,
        )
        assertNull(SubtitleSiblings.match(multiDot, "Movie.vi.srt"))
    }

    @Test
    fun `a suffix that is only an extension has no name to match`() {
        assertNull(SubtitleSiblings.match(video, ".srt"))
    }
}
