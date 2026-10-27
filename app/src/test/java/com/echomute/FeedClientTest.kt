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
}
