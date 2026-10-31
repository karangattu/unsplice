package com.echomute

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Segments at or above this confidence are skipped automatically; the rest only get a "Skip ad" button. */
const val AUTO_SKIP_CONFIDENCE = 0.5f

@Entity(tableName = "podcasts")
data class Podcast(@PrimaryKey val id: String, val title: String, val feedUrl: String, val autoSkip: Boolean = true, val artworkUrl: String? = null)

@Entity(tableName = "episodes", indices = [Index("podcastId")], foreignKeys = [ForeignKey(entity = Podcast::class, parentColumns = ["id"], childColumns = ["podcastId"], onDelete = ForeignKey.CASCADE)])
data class Episode(
    @PrimaryKey val id: String,
    val podcastId: String,
    val title: String,
    val audioUrl: String,
    val localPath: String? = null,
    val fingerprintPath: String? = null,
    val status: String = "NOT_DOWNLOADED",
    val error: String? = null,
    val chaptersUrl: String? = null,
    val publishedAt: Long? = null,
    /** Where playback left off, so the episode resumes from here. 0 once finished. */
    @ColumnInfo(defaultValue = "0") val positionMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val finished: Boolean = false,
    val durationMs: Long? = null,
)

/** An episode whose audio has been downloaded. */
data class EpisodeFile(val id: String, val localPath: String)

@Entity(tableName = "ad_segments", indices = [Index("episodeId")], foreignKeys = [ForeignKey(entity = Episode::class, parentColumns = ["id"], childColumns = ["episodeId"], onDelete = ForeignKey.CASCADE)])
data class AdSegment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeId: String,
    val startMs: Long,
    val endMs: Long,
    /** How the segment was found: "repeat" (audio fingerprint match) or "chapter" (publisher chapter marker). */
    @ColumnInfo(defaultValue = "'repeat'") val source: String = "repeat",
    @ColumnInfo(defaultValue = "0.6") val confidence: Float = 0.6f
)

@Dao
interface LibraryDao {
    @Upsert suspend fun savePodcast(podcast: Podcast)
    @Query("UPDATE podcasts SET artworkUrl = :url WHERE id = :id AND artworkUrl IS NULL") suspend fun setArtworkIfMissing(id: String, url: String)
    @Upsert suspend fun saveEpisode(episode: Episode)
    @Insert suspend fun saveSegments(segments: List<AdSegment>)
    @Query("SELECT * FROM podcasts ORDER BY title") fun observePodcasts(): Flow<List<Podcast>>
    @Query("SELECT * FROM podcasts WHERE id = :id") suspend fun podcast(id: String): Podcast?
    // Newest first. Episodes saved before publish dates were stored fall back to feed order, which is newest first.
    @Query("SELECT * FROM episodes WHERE podcastId = :id ORDER BY publishedAt IS NULL, publishedAt DESC, rowid ASC") fun observeEpisodes(id: String): Flow<List<Episode>>
    @Query("SELECT * FROM episodes WHERE podcastId = :id") suspend fun episodesForPodcast(id: String): List<Episode>
    @Query("SELECT * FROM episodes WHERE fingerprintPath IS NOT NULL") suspend fun episodesWithFingerprints(): List<Episode>
    @Query("SELECT id, localPath FROM episodes WHERE localPath IS NOT NULL") fun observeDownloads(): Flow<List<EpisodeFile>>
    @Query("UPDATE episodes SET positionMs = :positionMs, durationMs = :durationMs, finished = 0 WHERE id = :id") suspend fun savePosition(id: String, positionMs: Long, durationMs: Long?)
    @Query("UPDATE episodes SET positionMs = 0, finished = 1 WHERE id = :id") suspend fun markFinished(id: String)
    @Query("SELECT * FROM episodes WHERE id = :id") suspend fun episode(id: String): Episode?
    @Query("SELECT * FROM ad_segments WHERE episodeId = :id ORDER BY startMs") suspend fun segments(id: String): List<AdSegment>
    @Query("SELECT p.autoSkip FROM podcasts p JOIN episodes e ON e.podcastId = p.id WHERE e.id = :episodeId") fun observeAutoSkip(episodeId: String): Flow<Boolean?>
    @Query("SELECT * FROM ad_segments WHERE episodeId = :id ORDER BY startMs") fun observeSegments(id: String): Flow<List<AdSegment>>
    @Query("DELETE FROM ad_segments WHERE episodeId = :id") suspend fun deleteSegments(id: String)
    @Query("DELETE FROM ad_segments WHERE id = :id") suspend fun deleteSegment(id: Long)
    @Query("DELETE FROM podcasts WHERE id = :id") suspend fun deletePodcast(id: String)
    @Query("DELETE FROM episodes WHERE id = :id") suspend fun deleteEpisode(id: String)
}

@Database(entities = [Podcast::class, Episode::class, AdSegment::class], version = 5, exportSchema = false)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ad_segments ADD COLUMN source TEXT NOT NULL DEFAULT 'repeat'")
                db.execSQL("ALTER TABLE ad_segments ADD COLUMN confidence REAL NOT NULL DEFAULT 0.6")
                db.execSQL("ALTER TABLE episodes ADD COLUMN chaptersUrl TEXT")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN publishedAt INTEGER")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcasts ADD COLUMN artworkUrl TEXT")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN positionMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodes ADD COLUMN finished INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodes ADD COLUMN durationMs INTEGER")
            }
        }
        @Volatile private var instance: LibraryDatabase? = null
        fun get(context: Context): LibraryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LibraryDatabase::class.java, "unsplice.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { instance = it }
        }
    }
}
