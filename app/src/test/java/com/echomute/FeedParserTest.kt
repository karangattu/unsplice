package com.echomute

import org.junit.Assert.*
import org.junit.Test

class FeedParserTest {
    @Test fun parsesFeedTitleAndAudioEnclosures() {
        val xml = """<rss><channel><title>Example Podcast</title><item><title>First</title><guid>ep-1</guid><enclosure url="https://example.org/one.mp3" type="audio/mpeg"/></item><item><title>Video</title><enclosure url="https://example.org/video" type="video/mp4"/></item></channel></rss>"""
        val feed = parseFeed(xml.byteInputStream())
        assertEquals("Example Podcast", feed.title)
        assertEquals(listOf(FeedEpisode("ep-1", "First", "https://example.org/one.mp3")), feed.episodes)
    }
    @Test fun rejectsNonHttpEnclosuresAndExternalEntities() {
        val xml = """<!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><rss><channel><title>&xxe;</title><item><title>X</title><enclosure url="file:///private" type="audio/mpeg"/></item></channel></rss>"""
        try { assertTrue(parseFeed(xml.byteInputStream()).episodes.isEmpty()) } catch (_: Exception) { }
    }
}
