package com.echomute

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpisodeProcessingTest {
    @Test fun downloadsAndAnalyzesAnEpisode() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        MockWebServer().use { server ->
            try {
                val bytes = instrumentation.context.assets.open("tone.mp3").use { it.readBytes() }
                server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
                val dao = db.library()
                dao.savePodcast(Podcast("test", "Test", "https://example.com", false))
                dao.saveEpisode(Episode("test-episode", "test", "Tone", server.url("/tone.mp3").toString()))
                processEpisode(context, dao, "test-episode")
                val episode = requireNotNull(dao.episode("test-episode"))
                assertEquals("READY", episode.status)
                assertTrue(java.io.File(requireNotNull(episode.localPath)).isFile)
                assertTrue(java.io.File(requireNotNull(episode.fingerprintPath)).isFile)
            } finally { db.close(); context.filesDir.resolve("episodes/test-episode").deleteRecursively() }
        }
    }
}
