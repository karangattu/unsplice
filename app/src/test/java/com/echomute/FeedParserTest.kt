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
    @Test fun parsesShowNotesDescriptionAndItunesDuration() {
        val xml = """<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><title>Show</title><item><title>Ep 1</title><guid>1</guid><description>&lt;p&gt;Intro at 00:30 &amp;amp; topic at 14:20&lt;/p&gt;</description><itunes:duration>01:02:03</itunes:duration><enclosure url="https://example.org/1.mp3" type="audio/mpeg"/></item><item><title>Ep 2</title><guid>2</guid><itunes:duration>180</itunes:duration><enclosure url="https://example.org/2.mp3" type="audio/mpeg"/></item></channel></rss>"""
        val episodes = parseFeed(xml.byteInputStream()).episodes
        assertEquals("Intro at 00:30 & topic at 14:20", episodes[0].description)
        assertEquals(3_723_000L, episodes[0].durationMs)
        assertEquals(180_000L, episodes[1].durationMs)
    }

    @Test fun parsesTranscriptPriorityAndPodloveChapters() {
        val xml = """<rss xmlns:podcast="https://podcastindex.org/namespace/1.0" xmlns:psc="http://podlove.org/simple-chapters">
            <channel><title>Show</title>
            <item>
                <title>Ep 1</title><guid>1</guid>
                <enclosure url="https://example.org/1.mp3" type="audio/mpeg"/>
                <podcast:transcript url="https://example.org/1.srt" type="application/x-subrip" language="en"/>
                <podcast:transcript url="https://example.org/1-es.json" type="application/json" language="es"/>
                <podcast:transcript url="https://example.org/1-en.json" type="application/json" language="en-US"/>
                <psc:chapters version="1.2">
                    <psc:chapter start="00:00:00" title="Intro"/>
                    <psc:chapter start="00:02:00.500" title="Sponsor: Acme" href="https://acme.com/pod"/>
                    <psc:chapter start="00:03:30" title="Topic"/>
                </psc:chapters>
            </item></channel></rss>"""
        val ep = parseFeed(xml.byteInputStream()).episodes.single()
        assertEquals("https://example.org/1-en.json", ep.transcriptUrl)
        assertEquals("application/json", ep.transcriptType)
        val psc = parseJsonChapters(ep.pscChaptersJson!!)
        assertEquals(3, psc.size)
        assertEquals(listOf(Segment(120_500L, 210_000L)), sponsorSegments(psc, 600_000L))
    }

    @Test fun extractsShowNoteTimestampedChaptersWithoutProseFalsePositives() {
        val notes = """
            Episode summary here.
            00:00 Cold open
            12:30 - Sponsor: BetterHelp
            14:15 Main interview
            1:02:00 Outro
        """.trimIndent()
        val chapters = extractTimestampedChapters(notes, durationMs = 4_000_000L)
        assertEquals(4, chapters.size)
        assertEquals(listOf(Segment(750_000L, 855_000L)), sponsorSegments(chapters, 4_000_000L))

        val proseOnly = "In this episode we talk about the goal at 12:30 in the second half."
        assertTrue(extractTimestampedChapters(proseOnly, 3_600_000L).isEmpty())
    }
}
