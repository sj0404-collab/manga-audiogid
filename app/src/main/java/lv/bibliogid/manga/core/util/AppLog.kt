package lv.bibliogid.manga.core.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Журнал приложения: то же, что пишется в logcat, но доступно прямо в приложении.
 *
 * Зачем: разбирать проблемы по скриншотам дорого и неточно. Теперь любой шаг OCR,
 * озвучки и перелистывания оставляет строку в журнале, журнал открывается из читалки
 * и его можно отправить одним нажатием.
 */
object AppLog {

    private const val MAX_LINES = 1000
    private const val MAX_FILE_BYTES = 512 * 1024

    private val buffer = ArrayDeque<String>(MAX_LINES)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile private var file: File? = null

    fun init(context: Context) {
        if (file != null) return
        runCatching { file = File(context.filesDir, "bibliogid.log") }
        d("log", "=== запуск приложения ===")
    }

    @Synchronized
    fun d(tag: String, message: String) {
        val line = "${fmt.format(Date())} [$tag] $message"
        buffer.addLast(line)
        while (buffer.size > MAX_LINES) buffer.removeFirst()
        runCatching { Log.d("BiblioGid", line) }
        runCatching {
            val f = file ?: return@runCatching
            if (f.length() > MAX_FILE_BYTES) f.writeText("")
            f.appendText(line + "\n")
        }
    }

    @Synchronized
    fun dump(): String = buffer.joinToString("\n")

    @Synchronized
    fun clear() {
        buffer.clear()
        runCatching { file?.writeText("") }
        d("log", "журнал очищен")
    }

    fun filePath(): String = file?.absolutePath ?: ""
}
