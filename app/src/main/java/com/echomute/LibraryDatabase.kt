package com.echomute

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Segments at or above this confidence are skipped automatically; the rest only get a "Skip ad" button. */
const val AUTO_SKIP_CONFIDENCE = 0.5f

@Entity(tableName = "podcasts")
data class Podcast(@PrimaryKey val id: String, val title: String, val feedUrl: String, val autoSkip: Boolean = true)

@Entity(tableName = "episodes", indices = [Index("podcastId")], foreignKeys = [ForeignKey(entity = Podcast::class, parentColumns = ["id"], childColumns = ["podcastId"], onDelete = ForeignKey.CASCADE)])
data class Episode(@PrimaryKey val id: String, val podcastId: String, val title: String, val audioUrl: String, val localPath: String? = null, val fingerprintPath: String? = null, val status: String = "NOT_DOWNLOADED", val error: String? = null, val chaptersUrl: String? = null)

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
    @Upsert suspend fun saveEpisode(episode: Episode)
    @Insert suspend fun saveSegments(segments: List<AdSegment>)
    @Query("SELECT * FROM podcasts ORDER BY title") fun observePodcasts(): Flow<List<Podcast>>
    @Query("SELECT * FROM podcasts WHERE id = :id") suspend fun podcast(id: String): Podcast?
    @Query("SELECT * FROM episodes WHERE podcastId = :id ORDER BY rowid DESC") fun observeEpisodes(id: String): Flow<List<Episode>>
    @Query("SELECT * FROM episodes WHERE podcastId = :id") suspend fun episodesForPodcast(id: String): List<Episode>
    @Query("SELECT * FROM episodes WHERE id = :id") suspend fun episode(id: String): Episode?
    @Query("SELECT * FROM ad_segments WHERE episodeId = :id ORDER BY startMs") suspend fun segments(id: String): List<AdSegment>
    @Query("SELECT p.autoSkip FROM podcasts p JOIN episodes e ON e.podcastId = p.id WHERE e.id = :episodeId") fun observeAutoSkip(episodeId: String): Flow<Boolean?>
    @Query("SELECT * FROM ad_segments WHERE episodeId = :id ORDER BY startMs") fun observeSegments(id: String): Flow<List<AdSegment>>
    @Query("DELETE FROM ad_segments WHERE episodeId = :id") suspend fun deleteSegments(id: String)
    @Query("DELETE FROM ad_segments WHERE id = :id") suspend fun deleteSegment(id: Long)
    @Query("DELETE FROM podcasts WHERE id = :id") suspend fun deletePodcast(id: String)
    @Query("DELETE FROM episodes WHERE id = :id") suspend fun deleteEpisode(id: String)
}

@Database(entities = [Podcast::class, Episode::class, AdSegment::class], version = 2, exportSchema = false)
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
        @Volatile private var instance: LibraryDatabase? = null
        fun get(context: Context): LibraryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LibraryDatabase::class.java, "unsplice.db").addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
