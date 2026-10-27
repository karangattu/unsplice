package com.echomute

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "podcasts")
data class Podcast(@PrimaryKey val id: String, val title: String, val feedUrl: String, val autoSkip: Boolean = true)

@Entity(tableName = "episodes", indices = [Index("podcastId")], foreignKeys = [ForeignKey(entity = Podcast::class, parentColumns = ["id"], childColumns = ["podcastId"], onDelete = ForeignKey.CASCADE)])
data class Episode(@PrimaryKey val id: String, val podcastId: String, val title: String, val audioUrl: String, val localPath: String? = null, val fingerprintPath: String? = null, val status: String = "NOT_DOWNLOADED", val error: String? = null)

@Entity(tableName = "ad_segments", indices = [Index("episodeId")], foreignKeys = [ForeignKey(entity = Episode::class, parentColumns = ["id"], childColumns = ["episodeId"], onDelete = ForeignKey.CASCADE)])
data class AdSegment(@PrimaryKey(autoGenerate = true) val id: Long = 0, val episodeId: String, val startMs: Long, val endMs: Long)

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
    @Query("SELECT * FROM ad_segments WHERE episodeId = :id ORDER BY startMs") fun observeSegments(id: String): Flow<List<AdSegment>>
    @Query("DELETE FROM ad_segments WHERE episodeId = :id") suspend fun deleteSegments(id: String)
    @Query("DELETE FROM ad_segments WHERE id = :id") suspend fun deleteSegment(id: Long)
    @Query("DELETE FROM podcasts WHERE id = :id") suspend fun deletePodcast(id: String)
    @Query("DELETE FROM episodes WHERE id = :id") suspend fun deleteEpisode(id: String)
}

@Database(entities = [Podcast::class, Episode::class, AdSegment::class], version = 1, exportSchema = false)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    companion object {
        @Volatile private var instance: LibraryDatabase? = null
        fun get(context: Context): LibraryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LibraryDatabase::class.java, "unsplice.db").build().also { instance = it }
        }
    }
}
