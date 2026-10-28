package com.echomute

import org.junit.Assert.*
import org.junit.Test

class FeedParserTest {
    @Test fun readsChannelArtworkAndPrefersItunesOverRss() {
        val xml = """<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><title>Show</title><image><url>https://example.org/rss.jpg</url></image><itunes:image href="https://example.org/cover.jpg"/><item><itunes:image href="https://example.org/episode.jpg"/><enclosure url="https://example.org/1.mp3"/></item></channel></rss>"""
        assertEquals("https://example.org/cover.jpg", parseFeed(xml.byteInputStream()).artworkUrl)
    }
    @Test fun usesValidRssImageWhenItunesImageIsInvalidAndIgnoresItemImages() {
        val xml = """<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><title>Show</title><itunes:image href="file:///private"/><image><url>https://example.org/rss.jpg</url></image><item><itunes:image href="https://example.org/episode.jpg"/><image><url>https://example.org/item.jpg</url></image><enclosure url="https://example.org/1.mp3"/></item></channel></rss>"""
        assertEquals("https://example.org/rss.jpg", parseFeed(xml.byteInputStream()).artworkUrl)
        val noChannelImage = """<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><title>Show</title><item><itunes:image href="https://example.org/episode.jpg"/></item></channel></rss>"""
        assertNull(parseFeed(noChannelImage.byteInputStream()).artworkUrl)
    }
    @Test fun parsesFeedTitleAndAudioEnclosures() {
        val xml = """<rss><channel><title>Example Podcast</title><item><title>First</title><guid>ep-1</guid><enclosure url="https://example.org/one.mp3" type="audio/mpeg"/></item><item><title>Video</title><enclosure url="https://example.org/video" type="video/mp4"/></item></channel></rss>"""
        val feed = parseFeed(xml.byteInputStream())
        assertEquals("Example Podcast", feed.title)
        assertEquals(listOf(FeedEpisode("ep-1", "First", "https://example.org/one.mp3")), feed.episodes)
    }
    @Test fun readsPodcastingChaptersUrl() {
        val xml = """<rss xmlns:podcast="https://podcastindex.org/namespace/1.0"><channel><title>Show</title><item><title>One</title><guid>1</guid><enclosure url="https://example.org/1.mp3" type="audio/mpeg"/><podcast:chapters url="https://example.org/1.json" type="application/json+chapters"/></item><item><title>Two</title><guid>2</guid><enclosure url="https://example.org/2.mp3" type="audio/mpeg"/></item></channel></rss>"""
        val episodes = parseFeed(xml.byteInputStream()).episodes
        assertEquals("https://example.org/1.json", episodes[0].chaptersUrl)
        assertNull(episodes[1].chaptersUrl)
    }
    @Test fun readsPublishDates() {
        val xml = """<rss><channel><title>Show</title><item><title>One</title><pubDate>Tue, 27 Oct 2026 19:42:17 +0000</pubDate><enclosure url="https://example.org/1.mp3" type="audio/mpeg"/></item><item><title>Two</title><pubDate>sometime</pubDate><enclosure url="https://example.org/2.mp3" type="audio/mpeg"/></item></channel></rss>"""
        val episodes = parseFeed(xml.byteInputStream()).episodes
        assertEquals(java.time.Instant.parse("2026-10-27T19:42:17Z").toEpochMilli(), episodes[0].publishedAt)
        assertNull(episodes[1].publishedAt)
    }
    @Test fun rejectsNonHttpEnclosuresAndExternalEntities() {
        val xml = """<!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><rss><channel><title>&xxe;</title><item><title>X</title><enclosure url="file:///private" type="audio/mpeg"/></item></channel></rss>"""
        try { assertTrue(parseFeed(xml.byteInputStream()).episodes.isEmpty()) } catch (_: Exception) { }
    }
}
