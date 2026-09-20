package com.spotify.music.data

import android.content.Context
import androidx.room.Room
import com.spotify.music.core.model.Song
import com.spotify.music.data.db.AppDatabase
import com.spotify.music.data.db.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Collator
import java.util.Locale

/** 资料库仓库：为 UI 与播放层提供统一数据入口 */
class LibraryRepository private constructor(context: Context) {

    private val settings = SettingsRepository.get(context)
    private val db = Room.databaseBuilder(context, AppDatabase::class.java, "samsung_music.db")
        .fallbackToDestructiveMigration()
        .build()

    private val scanner = MediaScanner(context.applicationContext, db, settings)

    private val _scanState = MutableStateFlow(ScanState.IDLE)
    val scanState: StateFlow<ScanState> = _scanState

    private val _scanProgress = MutableStateFlow(MediaScanner.Progress(0, 0, 0, ""))
    val scanProgress: StateFlow<MediaScanner.Progress> = _scanProgress

    enum class ScanState { IDLE, SCANNING, DONE }

    val allSongs: Flow<List<Song>> = db.songDao().observeAll().map { list -> list.map { it.toSong() } }

    val folders: Flow<List<FolderGroup>> = db.songDao().observeAll().map { list ->
        list.map { it.toSong() }
            .groupBy { it.folderPath }
            .map { (path, songs) ->
                FolderGroup(
                    path = path,
                    name = songs.firstOrNull()?.folderName ?: File(path).name,
                    songs = songs,
                )
            }
            .sortedWith(compareBy(collator) { it.name })
    }

    fun observeFolder(path: String): Flow<List<Song>> =
        db.songDao().observeAll().map { list ->
            list.map { it.toSong() }.filter { it.folderPath == path || it.folderPath.startsWith("$path/") }
        }

    suspend fun songByPath(path: String): Song? = db.songDao().getByPath(path)?.toSong()

    suspend fun songsByPaths(paths: List<String>): List<Song> {
        val all = db.songDao().getAll().associateBy { it.path }
        return paths.mapNotNull { all[it]?.toSong() }
    }

    suspend fun rescan() {
        if (_scanState.value == ScanState.SCANNING) return
        withContext(Dispatchers.IO) {
            _scanState.value = ScanState.SCANNING
            try {
                scanner.scan { p -> _scanProgress.value = p }
                _scanState.value = ScanState.DONE
            } catch (t: Throwable) {
                _scanState.value = ScanState.IDLE
            }
        }
    }

    suspend fun hideFolder(folderPath: String) {
        val cur = settings.hiddenFolders.toMutableSet()
        cur.add(folderPath)
        settings.hiddenFolders = cur
        scanner.removeFolder(folderPath)
    }

    suspend fun unhideFolder(folderPath: String) {
        val cur = settings.hiddenFolders.toMutableSet()
        cur.remove(folderPath)
        settings.hiddenFolders = cur
    }

    companion object {
        @Volatile
        private var instance: LibraryRepository? = null

        fun get(context: Context): LibraryRepository =
            instance ?: synchronized(this) {
                instance ?: LibraryRepository(context.applicationContext).also { instance = it }
            }

        /** 中文拼音序比较器（用于 A-Z 侧栏排序） */
        val collator: Collator = Collator.getInstance(Locale.CHINA)
    }
}

data class FolderGroup(
    val path: String,
    val name: String,
    val songs: List<Song>,
)

fun SongEntity.toSong() = Song(
    id = path.hashCode().toLong(),
    path = path,
    fileName = fileName,
    folderPath = folderPath,
    folderName = folderName,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    lastModified = lastModified,
    hasEmbeddedLyrics = hasEmbeddedLyrics,
    trackNumber = trackNumber,
    year = year,
)

/**
 * 中英文混拼排序键（拼音字母段交错）：
 * 中文条目把首字拼音首字母前缀到排序键（ai Ren → "aai Ren"的键序），
 * 使 哀人 排进 A 段、与 animal 交错；拉丁/数字条目保持原样。
 * 拼音首字母用 Locale.CHINA Collator 与 a-z 逐一区间比较求得（与
 * letterOfText 同一套规则，字母条定位与排序永远一致）。
 */
fun mixedSortKey(s: String, collator: Collator): java.text.CollationKey {
    val t = s.trim()
    if (t.isEmpty()) return collator.getCollationKey("")
    val c0 = t[0]
    if (c0.isDigit()) return collator.getCollationKey("0$t") // 数字段排最前
    val upper = c0.uppercaseChar()
    if (upper in 'A'..'Z') return collator.getCollationKey(t)
    // 中文（或其他非拉丁）：求拼音首字母 l，前缀后参与统一字母序
    var last = 'a'
    for (l in 'a'..'z') {
        if (collator.compare(t, l.toString()) >= 0) last = l else break
    }
    return collator.getCollationKey(last + t)
}

/** 中英混拼排序：预计算 CollationKey 再排序，避免比较时重复求键 */
fun <T> List<T>.sortedByMixedKey(select: (T) -> String): List<T> {
    val c = LibraryRepository.collator
    return map { it to mixedSortKey(select(it), c) }
        .sortedWith(Comparator { a, b -> a.second.compareTo(b.second) })
        .map { it.first }
}

fun List<Song>.sortedByMode(mode: String): List<Song> = when (mode) {
    "artist" -> sortedByMixedKey { it.artist }.let { list ->
        // 同歌手内按歌名混拼
        list.groupBy { it.artist }
            .toList()
            .sortedByMixedKey { it.first }
            .flatMap { (_, songs) -> songs.sortedByMixedKey { it.title } }
    }
    "album" -> sortedByMixedKey { it.album }.let { list ->
        list.groupBy { it.album }
            .toList()
            .sortedByMixedKey { it.first }
            .flatMap { (_, songs) -> songs.sortedBy { it.trackNumber } }
    }
    "added" -> sortedByDescending { it.lastModified }
    "duration" -> sortedBy { it.durationMs }
    else -> sortedByMixedKey { it.title }
}

fun List<Song>.groupedAlbums(): List<AlbumGroup> =
    groupBy { it.album + "|" + it.artist }
        .map { (_, songs) ->
            AlbumGroup(
                name = songs.first().album,
                artist = songs.first().artist,
                coverPath = songs.sortedBy { it.trackNumber }.firstOrNull()?.path ?: "",
                songs = songs.sortedBy { it.trackNumber },
            )
        }
        .sortedWith(compareBy(LibraryRepository.collator) { it.name })

fun List<Song>.groupedArtists(): List<ArtistGroup> =
    groupBy { it.artist }
        .map { (artist, songs) ->
            ArtistGroup(
                name = artist,
                albumCount = songs.groupBy { it.album }.size,
                songCount = songs.size,
                coverPath = songs.firstOrNull()?.path ?: "",
                songs = songs.sortedWith(compareBy(LibraryRepository.collator) { it.title }),
            )
        }
        .sortedWith(compareBy(LibraryRepository.collator) { it.name })

data class AlbumGroup(
    val name: String,
    val artist: String,
    val coverPath: String,
    val songs: List<Song>,
)

data class ArtistGroup(
    val name: String,
    val albumCount: Int,
    val songCount: Int,
    val coverPath: String,
    val songs: List<Song>,
)
