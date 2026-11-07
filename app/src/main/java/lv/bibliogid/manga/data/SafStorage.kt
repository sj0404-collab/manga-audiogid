package lv.bibliogid.manga.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.InputStream

/**
 * Доступ к локальной библиотеке через SAF (Storage Access Framework).
 *
 * Важно для «глав на несколько ГБ»: файл архива НЕ копируется и НЕ распаковывается.
 * Мы получаем файловый дескриптор (ParcelFileDescriptor) и работаем с ним через
 * /proc/self/fd/<fd> — это даёт случайный доступ к записям ZIP/RAR за миллисекунды.
 */
class SafStorage(private val context: Context) {

    data class Node(
        val uri: Uri,
        val name: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val lastModified: Long,
        val mimeType: String?
    )

    fun listChildren(treeUri: Uri): List<Node> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        return query(childrenUri)
    }

    fun listChildrenOfDocument(parentUri: Uri): List<Node> {
        val docId = runCatching { DocumentsContract.getDocumentId(parentUri) }.getOrNull()
            ?: return emptyList()
        val tree = treeOf(parentUri) ?: return emptyList()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        return query(childrenUri)
    }

    fun treeOf(uri: Uri): Uri? = runCatching {
        val parts = uri.pathSegments
        val idx = parts.indexOf("tree")
        if (idx >= 0 && idx + 1 < parts.size) {
            DocumentsContract.buildTreeDocumentUri(uri.authority, parts[idx + 1])
        } else null
    }.getOrNull()

    private fun query(uri: Uri): List<Node> {
        val out = ArrayList<Node>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, null, null, null)
            if (cursor == null) return out
            while (cursor.moveToNext()) {
                val docId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: ""
                val mime = cursor.getString(2)
                val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                val modified = if (cursor.isNull(4)) 0L else cursor.getLong(4)
                val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                out.add(Node(docUri, name, mime == DocumentsContract.Document.MIME_TYPE_DIR, size, modified, mime))
            }
        } catch (e: Exception) {
            Log.e("SafStorage", "query failed: ${e.message}")
        } finally {
            cursor?.close()
        }
        return out
    }

    fun openStream(uri: Uri): InputStream? = runCatching {
        context.contentResolver.openInputStream(uri)
    }.getOrNull()

    /**
     * Случайный доступ к файлу: возвращает java.io.File, пригодный для ZipFile/RandomAccessFile.
     * Возвращает также ParcelFileDescriptor, который нужно держать открытым (close вместе с источником).
     */
    class RandomAccessHandle(val file: File, private val closeable: AutoCloseable) : AutoCloseable {
        override fun close() {
            runCatching { closeable.close() }
        }
    }

    fun openRandomAccess(uri: Uri): RandomAccessHandle? {
        // 1) обычный filesystem-путь (быстрый случай)
        filePathOf(uri)?.let { path ->
            val f = File(path)
            if (f.exists() && f.canRead()) return RandomAccessHandle(f, AutoCloseable { })
        }
        // 2) через файловый дескриптор SAF
        return runCatching {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: return@runCatching null
            val fd = pfd.fd
            val procFile = File("/proc/self/fd/$fd")
            if (!procFile.exists()) {
                pfd.close()
                return@runCatching null
            }
            RandomAccessHandle(procFile, pfd)
        }.getOrNull()
    }

    /**
     * Реальный путь к файлу по SAF-документу.
     * Android 10+ отдаёт внешнее хранилище через FUSE, поэтому открывать нужно именно
     * настоящий путь вида /storage/emulated/0/..., иначе RandomAccessFile/ZipFile падают.
     *
     * Варианты documentId:
     *   primary:Книги/Манга/Том 1.cbz          -> /storage/emulated/0/Книги/Манга/Том 1.cbz
     *   1A2B-3C4D:Манга/Том 1.cbz              -> /storage/1A2B-3C4D/Манга/Том 1.cbz
     *   raw:/storage/emulated/0/...            -> как есть
     */
    fun filePathOf(uri: Uri): String? = runCatching {
        when {
            uri.scheme == "file" -> uri.path
            DocumentsContract.isDocumentUri(context, uri) -> {
                val id = DocumentsContract.getDocumentId(uri)
                lv.bibliogid.manga.core.library.SafPaths.fromDocumentId(
                    documentId = id,
                    primaryRoot = android.os.Environment.getExternalStorageDirectory().absolutePath
                )
            }
            else -> null
        }
    }.getOrNull()

    /** Существует ли файл по реальному пути (и читается ли). */
    fun realFile(uri: Uri): File? = filePathOf(uri)?.let { File(it) }?.takeIf { it.exists() && it.canRead() }

    /**
     * Последний рубеж: потоковое копирование документа в кеш приложения.
     * Нужно, когда ни прямой путь, ни /proc/self/fd не дают случайного доступа
     * (например, документ пришёл от стороннего провайдера).
     */
    fun materializeToCache(uri: Uri, cacheRoot: File, preferredName: String): File? = runCatching {
        cacheRoot.mkdirs()
        val safeName = preferredName.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(80)
        val target = File(cacheRoot, safeName)
        if (target.exists() && target.length() > 0) return@runCatching target
        val tmp = File(cacheRoot, safeName + ".part")
        val input = openStream(uri) ?: return@runCatching null
        input.use { ins ->
            tmp.outputStream().use { outs ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val read = ins.read(buf)
                    if (read <= 0) break
                    outs.write(buf, 0, read)
                }
            }
        }
        if (tmp.length() == 0L) { tmp.delete(); return@runCatching null }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        // держим не больше трёх «материализованных» архивов
        cacheRoot.listFiles()?.filter { it.isFile && it != target }
            ?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
        target
    }.getOrNull()

    fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    companion object {
        const val PREF_TREE_URI = "library_tree_uri"
    }
}
