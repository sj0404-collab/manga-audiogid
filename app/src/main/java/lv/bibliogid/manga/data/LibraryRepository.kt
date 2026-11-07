package lv.bibliogid.manga.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lv.bibliogid.manga.core.library.ArchiveFiles
import lv.bibliogid.manga.core.library.ArchiveKind
import lv.bibliogid.manga.core.library.ChapterBuilder
import lv.bibliogid.manga.core.library.ChapterEntry
import lv.bibliogid.manga.core.library.LibrarySort
import lv.bibliogid.manga.core.library.MangaEntry
import lv.bibliogid.manga.core.library.RawChild
import lv.bibliogid.manga.core.library.ReadingModeHint
import java.io.File

/** Манга + её реальные SAF-URI (чистая модель хранит только строки). */
data class MangaItem(
    val entry: MangaEntry,
    val folderUri: Uri,
    val chapterUris: Map<String, Uri>,
    val coverFileUri: Uri?
)

/**
 * Репозиторий локальной библиотеки: «папка с папками».
 * Каждая папка = манга со своей обложкой; внутри — главы (архивы CBZ/CBR или папки).
 */
class LibraryRepository(
    private val context: Context,
    private val storage: SafStorage,
    private val settings: Settings
) {

    private val coverCache = object : LruCache<String, Bitmap>(24) {}

    var items: List<MangaItem> = emptyList()
        private set

    suspend fun scan(onProgress: (done: Int, total: Int, name: String) -> Unit = { _, _, _ -> }): List<MangaItem> =
        withContext(Dispatchers.IO) {
            val treeUri = settings.libraryTreeUri?.let(Uri::parse) ?: return@withContext emptyList()
            val roots = storage.listChildren(treeUri).filter { it.isDirectory }
            val result = ArrayList<MangaItem>(roots.size)
            roots.forEachIndexed { i, dir ->
                onProgress(i, roots.size, dir.name)
                val item = runCatching { buildManga(dir) }.onFailure {
                    Log.e("LibraryRepository", "scan ${dir.name}: ${it.message}")
                }.getOrNull()
                if (item != null && item.entry.chapters.isNotEmpty()) result.add(item)
            }
            onProgress(roots.size, roots.size, "")
            items = result
            result
        }

    private fun buildManga(dir: SafStorage.Node): MangaItem {
        val children = storage.listChildrenOfDocument(dir.uri)
        val raw = children.map { RawChild(it.name, it.isDirectory, it.sizeBytes, it.lastModified) }
        val chapters = ChapterBuilder.build(dir.uri.toString(), raw)
        val uris = children.associate { it.name to it.uri }
        val title = dir.name.removeSuffix("/")
        val coverFile = children.firstOrNull {
            !it.isDirectory && ArchiveFiles.isImage(it.name) &&
                (it.name.contains("cover", true) || it.name.contains("обложк", true) || it.name.startsWith("000"))
        }
        val entry = MangaEntry(
            id = dir.uri.toString(),
            title = title,
            rootUri = dir.uri.toString(),
            coverUri = coverFile?.uri?.toString() ?: chapters.firstOrNull()?.id,
            chapters = chapters,
            readingMode = ChapterBuilder.guessReadingMode(title, raw),
            lastModified = children.maxOfOrNull { it.lastModified } ?: 0L,
            isWebtoon = title.contains("вебтун", true) || title.contains("webtoon", true) ||
                title.contains("манхва", true)
        )
        return MangaItem(entry, dir.uri, uris, coverFile?.uri)
    }

    fun find(id: String): MangaItem? = items.firstOrNull { it.entry.id == id }

    fun sort(items: List<MangaItem>, sort: LibrarySort): List<MangaItem> =
        lv.bibliogid.manga.core.library.LibraryFilter.apply(items.map { it.entry }, sort = sort)
            .mapNotNull { e -> items.firstOrNull { it.entry.id == e.id } }

    // ---------- обложки ----------

    suspend fun loadCover(item: MangaItem, targetWidth: Int = 360): Bitmap? = withContext(Dispatchers.IO) {
        coverCache.get(item.entry.id)?.let { return@withContext it }
        val bitmap = runCatching {
            val direct = item.coverFileUri?.let { decodeUri(it, targetWidth) }
            if (direct != null) return@runCatching direct
            // обложка из первой страницы первой главы (читаем прямо из архива)
            val chapter = item.entry.chapters.firstOrNull() ?: return@runCatching null
            openChapter(item, chapter).getOrNull()?.use { source ->
                if (source.pageCount == 0) null else source.decodePage(0, targetWidth)
            }
        }.getOrNull()
        if (bitmap != null) coverCache.put(item.entry.id, bitmap)
        bitmap
    }

    private fun decodeUri(uri: Uri, targetWidth: Int): Bitmap? {
        val input = storage.openStream(uri) ?: return null
        return input.use { stream ->
            val bytes = stream.readBytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = PageSource.sampleSize(bounds.outWidth, bounds.outHeight, targetWidth)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
    }

    // ---------- главы ----------

    /**
     * Открывает главу для чтения. Архив НЕ распаковывается: читается центральный каталог,
     * страницы декодируются по требованию.
     *
     * Порядок попыток (важно: на Android 10+ внешнее хранилище идёт через FUSE):
     *   1) настоящий путь файла (/storage/emulated/0/...) — самый быстрый и надёжный;
     *   2) файловый дескриптор SAF (/proc/self/fd/N);
     *   3) потоковое копирование в кеш приложения (последний рубеж, работает всегда).
     */
    suspend fun openChapter(item: MangaItem, chapter: ChapterEntry): Result<PageSource> =
        withContext(Dispatchers.IO) {
            val uri = item.chapterUris[chapter.name]
                ?: return@withContext Result.failure(IllegalStateException("нет доступа к «${chapter.name}»"))

            if (!chapter.isArchive) {
                val children = runCatching { storage.listChildrenOfDocument(uri) }.getOrDefault(emptyList())
                    .filter { !it.isDirectory }
                val pages = ArchiveFiles.pageOrder(children.map { it.name })
                if (pages.isEmpty()) {
                    return@withContext Result.failure(IllegalStateException("в папке «${chapter.name}» нет страниц"))
                }
                val byName = children.associateBy { it.name }
                return@withContext Result.success(
                    FolderPageSource(chapter.name, pages) { name -> byName[name]?.let { storage.openStream(it.uri) } }
                )
            }

            val kind = ArchiveFiles.kindOf(chapter.name)
            val errors = ArrayList<String>()

            // попытка 1 и 2: реальный путь или /proc/self/fd
            val handle = storage.openRandomAccess(uri)
            if (handle != null) {
                val opened = runCatching { openArchiveFile(handle.file, chapter, kind) }.getOrNull()
                if (opened != null) {
                    handle.close()
                    return@withContext Result.success(opened)
                }
                errors.add("прямой доступ не удался (${handle.file.absolutePath})")
                handle.close()
            } else {
                errors.add("не удалось получить дескриптор файла")
            }

            // попытка 3: копия в кеш
            val cacheDir = File(context.cacheDir, "archives")
            val local = storage.materializeToCache(uri, cacheDir, chapter.name)
            if (local != null) {
                val opened = runCatching { openArchiveFile(local, chapter, kind) }.getOrNull()
                if (opened != null) return@withContext Result.success(opened)
                errors.add("копия в кеш не открылась")
            } else {
                errors.add("не удалось скопировать файл")
            }

            Result.failure(IllegalStateException(errors.joinToString("; ")))
        }

    private fun openArchiveFile(file: File, chapter: ChapterEntry, kind: ArchiveKind): PageSource? {
        if (!file.exists()) return null
        // пустой /proc-файл: у реального файла длина > 0
        val length = runCatching { file.length() }.getOrDefault(0L)
        if (length <= 0L) return null
        return when (kind) {
            ArchiveKind.ZIP_CBZ -> {
                val names = readZipNames(file) ?: return null
                PageSourceFactory.openZip(file, chapter.name, names)
            }
            ArchiveKind.RAR_CBR -> PageSourceFactory.openRar(file, chapter.name, emptyList())
            else -> {
                val names = readZipNames(file) ?: return null
                PageSourceFactory.openZip(file, chapter.name, names)
            }
        }
    }

    private fun readZipNames(file: File): List<String>? = runCatching {
        java.util.zip.ZipFile(file).use { zip ->
            val out = ArrayList<String>()
            val e = zip.entries()
            while (e.hasMoreElements()) out.add(e.nextElement().name)
            out
        }
    }.onFailure { Log.w("LibraryRepository", "zip read failed ${file.absolutePath}: ${it.message}") }.getOrNull()

    /** Держит файловый дескриптор открытым, пока открыт источник страниц. */
    private class WrappedSource(
        private val delegate: PageSource,
        private val handle: SafStorage.RandomAccessHandle
    ) : PageSource by delegate {
        override fun close() {
            runCatching { delegate.close() }
            runCatching { handle.close() }
        }
    }
}
