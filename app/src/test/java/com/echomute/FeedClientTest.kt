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
}
