package lv.bibliogid.manga.ocr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Скачивание моделей локального OCR (один раз, ~13 МБ) в filesDir.
 * Дальше распознавание работает полностью офлайн.
 */
class ModelManager(private val context: Context) {

    private val dir: File get() = File(context.filesDir, "paddle").apply { mkdirs() }

    // Имена с версией: старые PP-OCRv3/v4 файлы не должны подхватываться как «готовые».
    val detFile: File get() = File(dir, "det5.onnx")
    val recFile: File get() = File(dir, "rec5.onnx")
    val dictFile: File get() = File(dir, "dict5.txt")

    val isReady: Boolean get() = detFile.exists() && recFile.exists() && dictFile.exists()

    fun sizeBytes(): Long = detFile.length() + recFile.length() + dictFile.length()

    /** Минимальные размеры: словарь — это ~400 байт, модели — мегабайты. */
    private val minSizes = mapOf(
        "dict5.txt" to 1_000L,
        "det5.onnx" to 3_000_000L,
        "rec5.onnx" to 5_000_000L
    )

    /** Старые файлы PP-OCRv3/v4 — удаляем, чтобы не занимали место. */
    private val legacyNames = listOf("det.onnx", "rec.onnx", "dict.txt")

    var lastError: String? = null
        private set

    suspend fun ensureModels(
        onProgress: (file: String, downloaded: Long, total: Long) -> Unit = { _, _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        lastError = null
        try {
            legacyNames.forEach { name -> runCatching { File(dir, name).delete() } }
            if (!ok(dictFile)) downloadWithMirrors(DICT_MIRRORS, dictFile, onProgress)
            if (!ok(detFile)) downloadWithMirrors(DET_MIRRORS, detFile, onProgress)
            if (!ok(recFile)) downloadWithMirrors(REC_MIRRORS, recFile, onProgress)
            if (!isReady) {
                lastError = "модели неполные: det=${detFile.length() / 1024} КБ, rec=${recFile.length() / 1024} КБ"
                false
            } else true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            false
        }
    }

    private fun ok(file: File): Boolean =
        file.exists() && file.length() >= (minSizes[file.name] ?: 1000L)

    /** Пробуем зеркала по очереди: если одно недоступно, берём следующее. */
    private fun downloadWithMirrors(
        urls: List<String>,
        target: File,
        onProgress: (String, Long, Long) -> Unit
    ) {
        val failures = ArrayList<String>()
        for (url in urls) {
            val result = runCatching { download(url, target, onProgress) }
            if (result.isSuccess && ok(target)) return
            failures.add("${url.substringAfterLast('/')}: ${result.exceptionOrNull()?.message ?: "файл неполный"}")
        }
        throw IllegalStateException(failures.joinToString(" | "))
    }

    /** Ручной импорт: пользователь выбирает папку, где лежат det.onnx, rec.onnx, dict.txt. */
    suspend fun importFromFolder(files: Map<String,  java.io.InputStream>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            files.forEach { (name, stream) ->
                val target = File(dir, name)
                stream.use { input -> target.outputStream().use { out -> input.copyTo(out) } }
            }
            isReady
        }.getOrElse {
            lastError = it.message
            false
        }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
        Unit
    }

    private fun download(url: String, target: File, onProgress: (String, Long, Long) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".part")
        val conn = openFollowingRedirects(URL(url), 5)
        conn.inputStream.use { input ->
            val total = conn.contentLengthLong
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    done += read
                    onProgress(target.name, done, total)
                }
            }
        }
        conn.disconnect()
        val min = minSizes[target.name] ?: 1000L
        if (tmp.length() < min) {
            tmp.delete()
            throw IllegalStateException("получено ${tmp.length()} байт, ожидалось минимум $min")
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
    }

    /** Ручное следование 3xx: HttpURLConnection на Android не всегда дружит с цепочками редиректов. */
    private fun openFollowingRedirects(start: URL, hops: Int): HttpURLConnection {
        var url = start
        var remaining = hops
        while (true) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20000
                readTimeout = 60000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) BiblioGid/1.0")
                setRequestProperty("Accept", "*/*")
            }
            val code = conn.responseCode
            if (code in 300..399 && remaining > 0) {
                val location = conn.getHeaderField("Location") ?: run { conn.disconnect(); return conn }
                conn.disconnect()
                url = URL(url, location)
                remaining--
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IllegalStateException("HTTP $code для $url")
            }
            return conn
        }
    }

    companion object {
        val DET_MIRRORS = listOf(
            "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/onnx/PP-OCRv5/det/ch_PP-OCRv5_det_mobile.onnx",
            "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/master/onnx/PP-OCRv5/det/ch_PP-OCRv5_det_mobile.onnx",
            "https://huggingface.co/SWHL/RapidOCR/resolve/main/PP-OCRv4/ch_PP-OCRv4_det_infer.onnx"
        )
        val REC_MIRRORS = listOf(
            "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/onnx/PP-OCRv5/rec/cyrillic_PP-OCRv5_rec_mobile.onnx",
            "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/master/onnx/PP-OCRv5/rec/cyrillic_PP-OCRv5_rec_mobile.onnx"
        )
        val DICT_MIRRORS = listOf(
            "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/paddle/PP-OCRv5/rec/cyrillic_PP-OCRv5_rec_mobile/ppocrv5_cyrillic_dict.txt",
            "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/master/paddle/PP-OCRv5/rec/cyrillic_PP-OCRv5_rec_mobile/ppocrv5_cyrillic_dict.txt"
        )
    }
}
