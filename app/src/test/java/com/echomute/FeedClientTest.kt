package com.echomute

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FeedClientTest {
    @Test fun fetchesAndParsesAudioFeed() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""<rss><channel><title>Radio</title><item><title>Episode</title><enclosure url="https://example.com/audio.mp3" type="audio/mpeg"/></item></channel></rss>"""))
            val feed = fetchFeed(server.url("/feed").toString())
            assertEquals("Radio", feed.title)
            assertEquals("Episode", feed.episodes.single().title)
        }
    }
    @Test fun failsOnUnsuccessfulFeedResponse() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            assertThrows(Exception::class.java) { fetchFeed(server.url("/missing").toString()) }
        }
    }
    @Test fun discoversRssFromHtmlWebpage() {
        MockWebServer().use { server ->
            val rssUrl = server.url("/feed.xml").toString()
            val html = """<!DOCTYPE html><html><head><link rel="alternate" type="application/rss+xml" href="$rssUrl"></head><body>Page</body></html>"""
            server.enqueue(MockResponse().setBody(html))
            server.enqueue(MockResponse().setBody("""<rss><channel><title>Discovered</title><item><title>Item</title><enclosure url="https://example.com/audio.mp3" type="audio/mpeg"/></item></channel></rss>"""))
            val feed = fetchFeed(server.url("/website").toString())
            assertEquals("Discovered", feed.title)
        }
    }

    @Test fun acceptsFeedsLargerThanTheOldFiveMegabyteCap() {
        MockWebServer().use { server ->
            val padding = "x".repeat(6 * 1024 * 1024)
            val xml = """<rss><channel><title>Big Show</title><notes>$padding</notes><item><title>Episode 1</title><enclosure url="https://example.com/audio.mp3" type="audio/mpeg"/></item></channel></rss>"""
            server.enqueue(MockResponse().setBody(xml))
            val feed = fetchFeed(server.url("/feed").toString())
            assertEquals("Big Show", feed.title)
            assertEquals("Episode 1", feed.episodes.single().title)
        }
    }

    @Test fun stillRejectsFeedsOverTheSafetyCap() {
        MockWebServer().use { server ->
            val padding = "x".repeat(4096)
            val xml = """<rss><channel><title>Big Show</title><notes>$padding</notes></channel></rss>"""
            server.enqueue(MockResponse().setBody(xml))
            val error = assertThrows(IllegalArgumentException::class.java) {
                fetchFeed(server.url("/feed").toString(), maxBytes = 1024L)
            }
            assertEquals("Feed is too large", error.message)
        }
    }

    @Test fun fetchesTranscriptText() {
        MockWebServer().use { server ->
            val vtt = "WEBVTT\n\n00:00.000 --> 00:02.000\nHello world"
            server.enqueue(MockResponse().setBody(vtt))
            assertEquals(vtt, fetchTranscript(server.url("/transcript.vtt").toString()))
        }
    }
}
