package com.echomute

import java.io.File
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DownloadTest {
    @Test fun resumesPartialDownloadWhenServerHonorsRange() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/6").setBody("def"))
            val file = File.createTempFile("unsplice", ".part")
            try {
                file.writeText("abc")
                File(file.path + ".etag").writeText("\"v1\"")

                downloadAudio(server.url("/audio").toString(), file)
                assertEquals("abcdef", file.readText())
                assertEquals("bytes=3-", server.takeRequest().getHeader("Range"))
            } finally { file.delete() }
        }
    }
    @Test fun replacesPartialDownloadIfServerIgnoresRange() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("abcdef"))
            val file = File.createTempFile("unsplice", ".part")
            try { file.writeText("abc"); File(file.path + ".etag").writeText("\"v1\""); downloadAudio(server.url("/audio").toString(), file); assertEquals("abcdef", file.readText()) }
            finally { file.delete(); File(file.path + ".etag").delete() }
        }
    }
}

class ResumeSafetyTest {
    @Test fun restartsWithoutValidatorToAvoidMixingChangedFiles() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("new audio"))
            val file = File.createTempFile("unsplice", ".part")
            try {
                file.writeText("old")
                downloadAudio(server.url("/audio").toString(), file)
                assertNull(server.takeRequest().getHeader("Range"))
                assertEquals("new audio", file.readText())
            } finally { file.delete(); File(file.path + ".etag").delete() }
        }
    }
}
