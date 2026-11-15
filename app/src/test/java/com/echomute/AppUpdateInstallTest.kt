package com.echomute

import java.io.File
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AppUpdateInstallTest {
    @Test fun followsTheReleaseRedirectAndWritesTheApk() {
        MockWebServer().use { server ->
            val signedUrl = server.url("/signed/unsplice.apk").toString()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", signedUrl))
            server.enqueue(MockResponse().setBody("apk-bytes"))
            val file = File.createTempFile("unsplice-update", ".apk")
            try {
                val written = downloadUpdateApk(server.url("/releases/download/v1/unsplice.apk").toString(), file)
                assertEquals("apk-bytes", file.readText())
                assertEquals("apk-bytes".length.toLong(), written)
                assertEquals("/releases/download/v1/unsplice.apk", server.takeRequest().path)
                assertEquals("/signed/unsplice.apk", server.takeRequest().path)
            } finally { file.delete() }
        }
    }

    @Test fun reportsProgressAndReturnsTheByteCount() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("0123456789"))
            val file = File.createTempFile("unsplice-update", ".apk")
            val progress = mutableListOf<Pair<Long, Long>>()
            try {
                val written = downloadUpdateApk(server.url("/unsplice.apk").toString(), file) { done, total ->
                    progress.add(done to total)
                }
                assertEquals(10L, written)
                assertTrue(progress.isNotEmpty())
                assertEquals(10L to 10L, progress.last())
            } finally { file.delete() }
        }
    }

    @Test fun rejectsFailedEmptyAndInvalidDownloads() {
        val file = File.createTempFile("unsplice-update", ".apk")
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(500))
                assertThrows(IllegalArgumentException::class.java) {
                    downloadUpdateApk(server.url("/unsplice.apk").toString(), file)
                }
            }
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(""))
                assertThrows(IllegalArgumentException::class.java) {
                    downloadUpdateApk(server.url("/unsplice.apk").toString(), file)
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                downloadUpdateApk("ftp://example.com/unsplice.apk", file)
            }
        } finally { file.delete() }
    }
}
