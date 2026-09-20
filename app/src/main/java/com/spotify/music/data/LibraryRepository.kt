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
import com.spotify.music.util.CrashLogger

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
            .sortedByMixedKey { it.name }
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

    suspend fun rescan(): Boolean {
        if (_scanState.value == ScanState.SCANNING) return false
        return withContext(Dispatchers.IO) {
            _scanState.value = ScanState.SCANNING
            try {
                scanner.scan { p -> _scanProgress.value = p }
                _scanState.value = ScanState.DONE
                true
            } catch (t: Throwable) {
                _scanState.value = ScanState.IDLE
                CrashLogger.log(t, "library rescan")
                false
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

/** Han→拉丁（拼音）转写器，懒加载。android.icu 自 API 24 起可用；
 *  多数设备自带 Han-Latin 规则数据；不可用时为 null，回退到 Collator。 */
private val hanLatin: android.icu.text.Transliterator? by lazy {
    runCatching {
        android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII")
    }.getOrNull()
}

/** 取文本首字的拼音首字母（A-Z），用于中英混合排序与 A-Z 侧栏定位。
 *  拉丁字母/数字原样；中文用 ICU Han-Latin 转写后取首个 ASCII 字母。
 *  不依赖 java.text.Collator：实测 Locale.CHINA Collator 在当前运行时
 *  不按拼音排序，CJK 一律落在 'z' 之后，导致中文条目全部挤到末尾。 */
fun pinyinInitial(text: String): Char {
    val t = text.trim()
    val first = t.firstOrNull() ?: return '#'
    val upper = first.uppercaseChar()
    if (upper in 'A'..'Z') return upper
    if (first.isDigit()) return '#'
    val tl = hanLatin
    if (tl != null) {
        val py = runCatching { tl.transliterate(first.toString()) }.getOrNull() ?: ""
        py.firstOrNull { it.uppercaseChar() in 'A'..'Z' }?.uppercaseChar()?.let { return it }
    }
    // 回退：Collator 区间比较（部分运行时可按拼音）
    return try {
        val c = java.text.Collator.getInstance(java.util.Locale.CHINA)
        var last = 'a'
        for (l in 'a'..'z') { if (c.compare(t, l.toString()) >= 0) last = l else break }
        last.uppercaseChar()
    } catch (e: Throwable) { '#' }
}

/**
 * 中英混拼排序键：先按字母段（A-Z，数字为 '0'），同段内英文/数字在前、
 * 中文（拼音）在后，中文之间按拼音序交错。
 * 键 = (字母段, 语言组[0=拉丁/数字, 1=中文等], CollationKey)。
 */
fun mixedSortKey(s: String, collator: Collator): Triple<Char, Int, java.text.CollationKey> {
    val t = s.trim()
    if (t.isEmpty()) return Triple('0', 1, collator.getCollationKey(""))
    val c0 = t[0]
    if (c0.isDigit()) return Triple('0', 0, collator.getCollationKey("0$t")) // 数字段排最前
    val upper = c0.uppercaseChar()
    if (upper in 'A'..'Z') return Triple(upper, 0, collator.getCollationKey(t))
    // 中文等非拉丁：落到拼音首字母段，组内排在该段英文之后，按拼音排序
    val pi = pinyinInitial(t)
    return if (pi in 'A'..'Z') Triple(pi, 1, collator.getCollationKey("${pi.lowercaseChar()}$t"))
    else Triple('#', 1, collator.getCollationKey(t))
}

/** 中英混拼排序：预计算 (段, 组, CollationKey) 再排序，避免比较时重复求键 */
fun <T> List<T>.sortedByMixedKey(select: (T) -> String): List<T> {
    val c = LibraryRepository.collator
    return map { it to mixedSortKey(select(it), c) }
        .sortedWith(
            Comparator { a, b ->
                val byLetter = a.second.first.compareTo(b.second.first)
                if (byLetter != 0) byLetter
                else {
                    val byLang = a.second.second.compareTo(b.second.second)
                    if (byLang != 0) byLang else a.second.third.compareTo(b.second.third)
                }
            },
        )
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
        .sortedByMixedKey { it.name }

fun List<Song>.groupedArtists(): List<ArtistGroup> =
    groupBy { it.artist }
        .map { (artist, songs) ->
            ArtistGroup(
                name = artist,
                albumCount = songs.groupBy { it.album }.size,
                songCount = songs.size,
                coverPath = songs.firstOrNull()?.path ?: "",
                songs = songs.sortedByMixedKey { it.title },
            )
        }
        .sortedByMixedKey { it.name }

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
