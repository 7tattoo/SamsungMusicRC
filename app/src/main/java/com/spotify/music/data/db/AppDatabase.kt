package com.spotify.music.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val path: String,
    val fileName: String,
    val folderPath: String,
    val folderName: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val lastModified: Long,
    val hasEmbeddedLyrics: Boolean,
    val trackNumber: Int,
    val year: Int,
)

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs")
    suspend fun getAll(): List<SongEntity>

    @Query("SELECT COUNT(*) FROM songs")
    fun observeCount(): Flow<Int>

    @Upsert
    suspend fun upsertAll(songs: List<SongEntity>)

    @Query("DELETE FROM songs WHERE path NOT IN (:paths)")
    suspend fun deleteNotIn(paths: List<String>)

    @Query("DELETE FROM songs WHERE folderPath = :folder OR folderPath LIKE :folderPrefix || '%'")
    suspend fun deleteByFolder(folder: String, folderPrefix: String)

    @Query("SELECT * FROM songs WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): SongEntity?
}

@Database(entities = [SongEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
}
