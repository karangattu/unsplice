package com.echomute

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class ChaptersTest {
    private fun ByteArrayOutputStream.int32(v: Int) { write(v ushr 24); write(v ushr 16 and 0xFF); write(v ushr 8 and 0xFF); write(v and 0xFF) }
    private fun ByteArrayOutputStream.syncsafe(v: Int) { write(v ushr 21 and 0x7F); write(v ushr 14 and 0x7F); write(v ushr 7 and 0x7F); write(v and 0x7F) }

    private fun frame(id: String, body: ByteArray, version: Int) = ByteArrayOutputStream().apply {
        write(id.toByteArray()); if (version == 4) syncsafe(body.size) else int32(body.size); write(0); write(0); write(body)
    }.toByteArray()

    private fun chap(id: String, start: Int, end: Int, title: String, version: Int, encoding: Int = 3): ByteArray {
        val text = when (encoding) { 1 -> title.toByteArray(Charsets.UTF_16); else -> title.toByteArray(Charsets.UTF_8) }
        val body = ByteArrayOutputStream().apply {
            write(id.toByteArray()); write(0); int32(start); int32(end); int32(-1); int32(-1)
            write(frame("TIT2", byteArrayOf(encoding.toByte()) + text, version))
        }.toByteArray()
        return frame("CHAP", body, version)
    }

    private fun tag(version: Int, vararg frames: ByteArray): ByteArray {
        val content = frames.fold(ByteArray(0)) { a, b -> a + b } + ByteArray(32) // padding
        return ByteArrayOutputStream().apply { write("ID3".toByteArray()); write(version); write(0); write(0); syncsafe(content.size); write(content) }.toByteArray() + ByteArray(100)
    }

    @Test fun readsId3v23AndV24Chapters() {
        for (version in 3..4) {
            val bytes = tag(version, frame("TIT2", byteArrayOf(3) + "Episode".toByteArray(), version), chap("c0", 0, 60_000, "Intro", version), chap("c1", 60_000, 90_000, "Sponsor: Acme", version, encoding = 1))
            assertEquals(listOf(Chapter(0, 60_000, "Intro"), Chapter(60_000, 90_000, "Sponsor: Acme")), readId3Chapters(bytes.inputStream()))
        }
    }

    @Test fun ignoresFilesWithoutId3() {
        assertTrue(readId3Chapters(ByteArray(200) { 0x55 }.inputStream()).isEmpty())
        assertTrue(readId3Chapters(ByteArray(3).inputStream()).isEmpty())
        assertTrue(readId3Chapters(tag(3).copyOf(12).inputStream()).isEmpty())
    }

    @Test fun parsesJsonChapters() {
        val json = """{"version":"1.2.0","chapters":[{"startTime":0,"title":"Intro"},{"startTime":312.5,"title":"Ad break","endTime":372},{"title":"no start"}]}"""
        assertEquals(listOf(Chapter(0, null, "Intro"), Chapter(312_500, 372_000, "Ad break")), parseJsonChapters(json))
    }

    @Test fun recognisesCommonAdChapterTitles() {
        val ads = listOf("Mid-roll", "Midroll ads", "Pre-Roll", "Post roll", "Promo: Our other show", "A word from our sponsors", "Support for this show comes from Acme", "Message from our partners", "Commercial break")
        val content = listOf("Roads ahead", "Sponsorship scandal in parliament", "The promotion ceremony", "Rolling Stones retrospective", "Opening", "Listener mail")
        for (title in ads) assertEquals(title, listOf(Segment(0, 1_000)), sponsorSegments(listOf(Chapter(0, 1_000, title)), null))
        // "Sponsorship" is ambiguous and stays flagged; the others must not be.
        for (title in content - "Sponsorship scandal in parliament") assertTrue(title, sponsorSegments(listOf(Chapter(0, 1_000, title)), null).isEmpty())
    }

    @Test fun picksSponsorChaptersOnly() {
        val chapters = listOf(
            Chapter(0, null, "Cold open"),
            Chapter(60_000, null, "This episode is brought to you by Acme"),
            Chapter(120_000, null, "Roads and loads of headlines"),
            Chapter(300_000, 330_000, "ADS"),
            Chapter(330_000, null, "Paid promotion"),
        )
        assertEquals(listOf(Segment(60_000, 120_000), Segment(300_000, 330_000), Segment(330_000, 400_000)), sponsorSegments(chapters, 400_000))
        assertEquals(listOf(Segment(60_000, 120_000), Segment(300_000, 330_000)), sponsorSegments(chapters, null))
    }

    @Test fun roundTripsChaptersAndResolvesActiveChapter() {
        val file = java.io.File.createTempFile("chapters", ".tsv")
        try {
            val chapters = listOf(Chapter(0, 60_000, "Intro"), Chapter(60_000, null, "Main Topic"), Chapter(180_000, 240_000, "Outro"))
            writeChapters(file, chapters)
            val loaded = readChapters(file)
            assertEquals(chapters, loaded)
            assertEquals("Intro", currentChapter(loaded, 30_000, 240_000)?.title)
            assertEquals("Main Topic", currentChapter(loaded, 120_000, 240_000)?.title)
            assertEquals("Outro", currentChapter(loaded, 200_000, 240_000)?.title)
        } finally {
            file.delete()
        }
    }

    @Test fun parsesJsonTocUrlAndDetectsHiddenSponsorUrls() {
        val json = """{"version":"1.2.0","chapters":[
            {"startTime":0,"title":"Intro","toc":true},
            {"startTime":60,"endTime":90,"title":"Acme","toc":false,"url":"https://acme.com/podcast?utm_source=pod"},
            {"startTime":120,"endTime":150,"title":"Bonus chart","toc":false,"url":"https://example.org/chart.png"}
        ]}"""
        val chapters = parseJsonChapters(json)
        assertEquals(3, chapters.size)
        assertFalse(chapters[1].toc)
        assertEquals("https://acme.com/podcast?utm_source=pod", chapters[1].url)
        assertEquals(listOf(Segment(60_000, 90_000)), sponsorSegments(chapters, 300_000))
        assertEquals(0.9f, sponsorConfidence(chapters[1], 0.7f), 0.0001f)
    }

    @Test fun parsesPodloveChaptersAndTrustedDurationDelta() {
        val xml = """<psc:chapters xmlns:psc="http://podlove.org/simple-chapters" version="1.2">
            <psc:chapter start="00:00:00.000" title="Opening"/>
            <psc:chapter start="00:05:30.500" title="Sponsor break" href="https://example.com/sponsor"/>
            <psc:chapter start="00:07:00" title="Main interview"/>
        </psc:chapters>"""
        val chapters = parsePodloveChapters(xml)
        assertEquals(3, chapters.size)
        assertEquals(330_500L, chapters[1].startMs)
        assertEquals("https://example.com/sponsor", chapters[1].url)
        assertEquals(listOf(Segment(330_500L, 420_000L)), sponsorSegments(chapters, 900_000L))

        assertTrue(hasTrustedJsonChapters(1_000_000L, 1_003_000L))
        assertFalse(hasTrustedJsonChapters(1_060_000L, 1_000_000L))
        assertFalse(hasTrustedJsonChapters(null, 1_000_000L))
    }

    @Test fun readsAcastTxxxAdBreakCuesFromId3Header() {
        val txxxBody = ByteArrayOutputStream().apply {
            write(3)
            write("AIS_AD_BREAK_1".toByteArray())
            write(0)
            write("368648,0;AIS_AD_BREAK_PRE=00:00:00:000".toByteArray())
        }.toByteArray()
        val bytes = tag(4, frame("TXXX", txxxBody, 4), chap("c0", 0, 30_000, "Intro", 4))
        val meta = readId3Metadata(bytes.inputStream())
        assertEquals(1, meta.chapters.size)
        assertEquals(listOf(0L, 368_648L), meta.adBreakCuesMs)
    }
}
