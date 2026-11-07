package lv.bibliogid.manga.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import lv.bibliogid.manga.core.library.ArchiveFiles
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Источник страниц главы.
 *
 * Ключевое требование: главы на несколько ГБ читаются напрямую из архива
 * БЕЗ распаковки на диск и без кеша страниц — из архива берётся только нужная запись.
 */
interface PageSource : Closeable {
    val name: String
    val pageCount: Int
    fun pageNames(): List<String>
    fun openEntry(index: Int): InputStream?
    fun kindLabel(): String

    /**
     * Декодирование страницы с даунсемплом под экран (в память, без файлового кеша).
     *
     * ВАЖНО: поток записи архива читается РОВНО ОДИН РАЗ и целиком в байты.
     * ZipFile/RAR-потоки не поддерживают mark/reset, поэтому «два прохода»
     * (сначала границы, потом картинка) давали null и бесконечный спиннер.
     */
    fun decodePage(index: Int, targetWidth: Int): Bitmap? {
        val stream = openEntry(index) ?: return null
        val bytes = try {
            stream.use { it.readBytes() }
        } catch (e: Exception) {
            android.util.Log.e("PageSource", "read page $index failed: ${e.message}")
            return null
        }
        if (bytes.isEmpty()) return null
        return decodeBytes(bytes, targetWidth)
    }

    companion object {
        fun sampleSize(w: Int, h: Int, targetWidth: Int): Int =
            lv.bibliogid.manga.core.util.ImageScaling.sampleSize(w, h, targetWidth)

        /** Общий путь декодирования из байтов (используется и папками, и архивами). */
        fun decodeBytes(bytes: ByteArray, targetWidth: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetWidth)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
        }
    }
}

/** CBZ/ZIP: java.util.zip.ZipFile читает центральный каталог, страницы — по требованию. */
class ZipPageSource(
    private val zip: ZipFile,
    override val name: String,
    private val pages: List<String>
) : PageSource {
    override val pageCount: Int get() = pages.size
    override fun pageNames(): List<String> = pages
    override fun kindLabel(): String = "CBZ · потоково"

    override fun openEntry(index: Int): InputStream? {
        val entry = zip.getEntry(pages.getOrNull(index) ?: return null) ?: return null
        return zip.getInputStream(entry)
    }

    override fun close() {
        runCatching { zip.close() }
    }
}

/** CBR/RAR: junrar + прямой доступ к файлу (в т.ч. через /proc/self/fd для SAF). */
class RarPageSource(
    private val archive: com.github.junrar.Archive,
    override val name: String,
    private val pages: List<String>
) : PageSource {
    override val pageCount: Int get() = pages.size
    override fun pageNames(): List<String> = pages
    override fun kindLabel(): String = "CBR · потоково"

    override fun openEntry(index: Int): InputStream? {
        val target = pages.getOrNull(index) ?: return null
        val header = archive.fileHeaders.firstOrNull { it.fileName == target } ?: return null
        archive.let { }
        return RarEntryStream(archive, header)
    }

    override fun close() {
        runCatching { archive.close() }
    }
}

/** junrar отдаёт содержимое файла через extractFile(header, outputStream) — оборачиваем в поток. */
private class RarEntryStream(
    private val archive: com.github.junrar.Archive,
    private val header: com.github.junrar.rarfile.FileHeader
) : InputStream() {
    private var piped: java.io.PipedInputStream? = null
    private var thread: Thread? = null
    private var started = false

    private fun ensureStarted() {
        if (started) return
        started = true
        val out = java.io.PipedOutputStream()
        val input = java.io.PipedInputStream(out, 1 shl 16)
        piped = input
        val t = Thread {
            runCatching {
                archive.extractFile(header, out)
            }.onFailure { Log.w("RarEntryStream", "extract failed: ${it.message}") }
            runCatching { out.close() }
        }
        t.isDaemon = true
        thread = t
        t.start()
    }

    override fun read(): Int {
        ensureStarted()
        return piped?.read() ?: -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        ensureStarted()
        return piped?.read(b, off, len) ?: -1
    }

    override fun close() {
        runCatching { piped?.close() }
        thread?.interrupt()
        super.close()
    }
}

/** Глава-папка: страницы — отдельные документы. */
class FolderPageSource(
    override val name: String,
    private val pages: List<String>,
    private val opener: (String) -> InputStream?
) : PageSource {
    override val pageCount: Int get() = pages.size
    override fun pageNames(): List<String> = pages
    override fun kindLabel(): String = "Папка · потоково"
    override fun openEntry(index: Int): InputStream? {
        val page = pages.getOrNull(index) ?: return null
        return opener(page)
    }

    override fun decodePage(index: Int, targetWidth: Int): Bitmap? {
        val input = openEntry(index) ?: return null
        val bytes = runCatching { input.use { it.readBytes() } }.getOrNull() ?: return null
        return PageSource.decodeBytes(bytes, targetWidth)
    }

    override fun close() = Unit
}

/**
 * Открытие источников по SAF-URI. Используется трюк /proc/self/fd, чтобы
 * получить случайный доступ к файлу из SAF без копирования его на диск.
 */
object PageSourceFactory {

    fun openZip(file: File, name: String, entries: List<String>): ZipPageSource? {
        return runCatching {
            val zip = ZipFile(file)
            ZipPageSource(zip, name, ArchiveFiles.pageOrder(entries))
        }.onFailure { Log.e("PageSourceFactory", "zip open failed: ${it.message}") }.getOrNull()
    }

    fun openRar(file: File, name: String, entries: List<String>): RarPageSource? {
        return runCatching {
            val archive = com.github.junrar.Archive(file)
            val pages = ArchiveFiles.pageOrder(archive.fileHeaders.map { it.fileName })
            RarPageSource(archive, name, pages)
        }.onFailure { Log.e("PageSourceFactory", "rar open failed: ${it.message}") }.getOrNull()
    }
}
