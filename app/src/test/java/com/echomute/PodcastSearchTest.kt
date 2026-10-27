package com.echomute

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastSearchTest {
    @Test
    fun parsesSearchResultsCorrectly() {
        val json = """
            {
                "resultCount": 2,
                "results": [
                    {
                        "collectionName": "Show One",
                        "artistName": "Host One",
                        "feedUrl": "https://example.com/feed1.xml",
                        "artworkUrl100": "https://example.com/art1.jpg"
                    },
                    {
                        "trackName": "Show Two",
                        "artistName": "Host Two",
                        "feedUrl": "https://example.com/feed2.xml"
                    },
                    {
                        "trackName": "Invalid",
                        "feedUrl": "not-a-valid-url"
                    }
                ]
            }
        """.trimIndent()
        val results = parseSearchResults(json)
        assertEquals(2, results.size)
        assertEquals("Show One", results[0].title)
        assertEquals("Host One", results[0].artist)
        assertEquals("https://example.com/feed1.xml", results[0].feedUrl)
        assertEquals("https://example.com/art1.jpg", results[0].artworkUrl)
        assertEquals("Show Two", results[1].title)
        assertEquals("Host Two", results[1].artist)
        assertEquals("https://example.com/feed2.xml", results[1].feedUrl)
    }

    @Test
    fun handlesEmptyOrMalformedResults() {
        assertTrue(parseSearchResults("{}").isEmpty())
        assertTrue(parseSearchResults("""{"results": []}""").isEmpty())
    }

    @Test
    fun executesSearchAgainstEndpoint() {
        MockWebServer().use { server ->
            val json = """
                {
                    "resultCount": 1,
                    "results": [
                        {
                            "collectionName": "Searched Show",
                            "artistName": "Searched Host",
                            "feedUrl": "https://example.com/search.xml"
                        }
                    ]
                }
            """.trimIndent()
            server.enqueue(MockResponse().setBody(json))
            val client = OkHttpClient()
            val request = okhttp3.Request.Builder().url(server.url("/search")).build()
            client.newCall(request).execute().use { response ->
                val results = parseSearchResults(response.body?.string().orEmpty())
                assertEquals(1, results.size)
                assertEquals("Searched Show", results.single().title)
            }
        }
    }
}
