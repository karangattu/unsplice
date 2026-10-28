package com.echomute

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
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

    @Test fun listsEpisodesNewestFirst() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, LibraryDatabase::class.java).build()
        try {
            val dao = db.library()
            dao.savePodcast(Podcast("p", "Show", "https://example.com/feed"))
            // Saved in feed order (newest first); the two undated ones come from before dates were stored.
            dao.saveEpisode(Episode("undated-new", "p", "Undated new", "https://example.com/a.mp3"))
            dao.saveEpisode(Episode("undated-old", "p", "Undated old", "https://example.com/b.mp3"))
            dao.saveEpisode(Episode("old", "p", "Old", "https://example.com/c.mp3", publishedAt = 1_000))
            dao.saveEpisode(Episode("new", "p", "New", "https://example.com/d.mp3", publishedAt = 2_000))
            assertEquals(listOf("new", "old", "undated-new", "undated-old"), dao.observeEpisodes("p").first().map { it.id })
        } finally { db.close() }
    }
    @Test fun migratesVersionOneDatabaseKeepingSegments() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-test.db"
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            db.execSQL("CREATE TABLE `podcasts` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `feedUrl` TEXT NOT NULL, `autoSkip` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE `episodes` (`id` TEXT NOT NULL, `podcastId` TEXT NOT NULL, `title` TEXT NOT NULL, `audioUrl` TEXT NOT NULL, `localPath` TEXT, `fingerprintPath` TEXT, `status` TEXT NOT NULL, `error` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`podcastId`) REFERENCES `podcasts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            db.execSQL("CREATE INDEX `index_episodes_podcastId` ON `episodes` (`podcastId`)")
            db.execSQL("CREATE TABLE `ad_segments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `episodeId` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, FOREIGN KEY(`episodeId`) REFERENCES `episodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            db.execSQL("CREATE INDEX `index_ad_segments_episodeId` ON `ad_segments` (`episodeId`)")
            db.execSQL("INSERT INTO podcasts VALUES ('p', 'Show', 'https://example.com/feed', 1)")
            db.execSQL("INSERT INTO episodes VALUES ('e', 'p', 'One', 'https://example.com/one.mp3', NULL, NULL, 'READY', NULL)")
            db.execSQL("INSERT INTO ad_segments (episodeId, startMs, endMs) VALUES ('e', 200000, 220000)")
            db.version = 1
        }
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(LibraryDatabase.MIGRATION_1_2, LibraryDatabase.MIGRATION_2_3, LibraryDatabase.MIGRATION_3_4).build()
        try {
            val segment = db.library().segments("e").single()
            assertEquals("repeat", segment.source)
            assertEquals(0.6f, segment.confidence, 0.0001f)
            assertNull(db.library().episode("e")?.chaptersUrl)
            assertNull(db.library().episode("e")?.publishedAt)
            assertNull(db.library().podcast("p")?.artworkUrl)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun migratesVersionThreePodcastWithoutLosingItsSettings() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "artwork-migration-test.db"
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            db.execSQL("CREATE TABLE `podcasts` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `feedUrl` TEXT NOT NULL, `autoSkip` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE `episodes` (`id` TEXT NOT NULL, `podcastId` TEXT NOT NULL, `title` TEXT NOT NULL, `audioUrl` TEXT NOT NULL, `localPath` TEXT, `fingerprintPath` TEXT, `status` TEXT NOT NULL, `error` TEXT, `chaptersUrl` TEXT, `publishedAt` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`podcastId`) REFERENCES `podcasts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            db.execSQL("CREATE INDEX `index_episodes_podcastId` ON `episodes` (`podcastId`)")
            db.execSQL("CREATE TABLE `ad_segments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `episodeId` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, `source` TEXT NOT NULL DEFAULT 'repeat', `confidence` REAL NOT NULL DEFAULT 0.6, FOREIGN KEY(`episodeId`) REFERENCES `episodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            db.execSQL("CREATE INDEX `index_ad_segments_episodeId` ON `ad_segments` (`episodeId`)")
            db.execSQL("INSERT INTO podcasts VALUES ('p', 'Show', 'https://example.com/feed', 0)")
            db.execSQL("INSERT INTO episodes VALUES ('e', 'p', 'One', 'https://example.com/one.mp3', '/saved.mp3', NULL, 'READY', NULL, NULL, 123)")
            db.version = 3
        }
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(LibraryDatabase.MIGRATION_3_4).build()
        try {
            assertEquals(false, db.library().podcast("p")?.autoSkip)
            assertNull(db.library().podcast("p")?.artworkUrl)
            assertEquals("/saved.mp3", db.library().episode("e")?.localPath)
            db.library().setArtworkIfMissing("p", "https://example.com/cover.jpg")
            assertEquals("https://example.com/cover.jpg", db.library().podcast("p")?.artworkUrl)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
