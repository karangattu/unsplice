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
}
