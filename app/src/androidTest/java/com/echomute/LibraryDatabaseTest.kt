package com.echomute

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryDatabaseTest {
    @Test fun persistsEpisodesAndSegmentsForTheirPodcast() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, LibraryDatabase::class.java).build()
        try {
            val dao = db.library()
            dao.savePodcast(Podcast("p", "Show", "https://example.com/feed", false))
            dao.saveEpisode(Episode("e", "p", "One", "https://example.com/one.mp3"))
            dao.saveSegments(listOf(AdSegment(episodeId = "e", startMs = 200000, endMs = 220000)))
            assertEquals("One", dao.episode("e")?.title)
            assertEquals(220000L, dao.segments("e").single().endMs)
            assertTrue(dao.episodesForPodcast("other").isEmpty())
            dao.deleteEpisode("e")
            assertNull(dao.episode("e"))
            assertTrue(dao.segments("e").isEmpty())
            dao.deletePodcast("p")
            assertNull(dao.podcast("p"))
        } finally { db.close() }
    }
}
