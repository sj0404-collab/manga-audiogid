package lv.bibliogid.manga.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Системный TTS (Android TextToSpeech) с управляемой скоростью 0.25x…2.0x.
 * Озвучка идёт по репликам: одна реплика = один utterance, поэтому подсветка
 * и «следующая/предыдущая реплика» работают точно.
 */
class TtsController(context: Context) {

    private companion object {
        const val MAX_AUDIO_FILES = 25
        /** Аудиожурнал одним файлом: ZIP и его копия с расширением .png. */
        /** Префикс utteranceId записи в файл — такие колбэки не считаются речью. */
        const val SYNTH_PREFIX = "jun_"
        const val ARCHIVE_ZIP = "audio_journal.zip"
        const val ARCHIVE_PNG = "audio_journal.png"
    }

    private val appContext: Context = context.applicationContext

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: (() -> Unit)? = null

    var onUtteranceDone: ((utteranceId: String) -> Unit)? = null
    var onUtteranceStart: ((utteranceId: String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    var isSpeaking: Boolean = false
        private set

    fun init(onReady: (Boolean) -> Unit) {
        if (tts != null) { onReady(ready); return }
        pending = { onReady(ready) }
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                val res = tts?.setLanguage(Locale("ru", "RU"))
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    onError?.invoke("Русский голос TTS не установлен в системе")
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        val id = utteranceId ?: return
                        if (id.startsWith(SYNTH_PREFIX)) return   // это запись в файл, не речь
                        isSpeaking = true
                        onUtteranceStart?.invoke(id)
                    }

                    override fun onDone(utteranceId: String?) {
                        val id = utteranceId ?: return
                        if (id.startsWith(SYNTH_PREFIX)) return   // иначе реплика «обрывалась»
                        isSpeaking = false
                        onUtteranceDone?.invoke(id)
                    }

                    @Deprecated("legacy callback")
                    override fun onError(utteranceId: String?) {
                        isSpeaking = false
                        onError?.invoke("Ошибка TTS")
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        isSpeaking = false
                        onError?.invoke("Ошибка TTS: $errorCode")
                    }
                })
                Log.i("TtsController", "TTS ready")
            }
            pending?.invoke()
            pending = null
        }
    }

    fun isReady(): Boolean = ready

    /**
     * Запись в файл идёт на ОТДЕЛЬНОМ движке: у большинства системных TTS вызов
     * `synthesizeToFile` на том же экземпляре, который говорит, ставит озвучку на паузу
     * или обрывает её. Плюс колбэки записи приходили как колбэки речи, приложение считало
     * реплику законченной и прыгало к следующей («голос обрывает чтение»).
     */
    private var fileEngine: TextToSpeech? = null

    private fun ensureFileEngine() {
        if (fileEngine != null) return
        fileEngine = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                fileEngine?.setLanguage(Locale("ru", "RU"))
                fileEngine?.setSpeechRate(1.0f)
            } else {
                fileEngine = null
            }
        }
    }

    fun speak(text: String, speed: Float, utteranceId: String) {
        val engine = tts ?: return
        if (!ready || text.isBlank()) return
        engine.setSpeechRate(speed.coerceIn(0.25f, 2.0f))
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        // аудиожурнал: то же, что услышал пользователь, сохраняем в файл
        if (audioJournal) synthToFile(text, speed, utteranceId)
    }

    /** Включён ли аудиожурнал (последние реплики сохраняются в файлы). */
    var audioJournal: Boolean = true

    private val audioDir: java.io.File by lazy {
        java.io.File(appContext.filesDir, "audio_journal").apply { mkdirs() }
    }

    fun audioFiles(): List<java.io.File> =
        audioDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun clearAudioJournal() {
        audioDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Аудиожурнал одним файлом: ZIP (и его копия с расширением .png — такой файл принимают
     * приёмники, которым не отдать .zip). Порядок записей — порядок озвучки, имена 001_…, 002_…
     * с началом реплики, чтобы архив можно было слушать с листа.
     */
    fun buildAudioArchive(asPng: Boolean = false): java.io.File? {
        val files = audioDir.listFiles()?.filter { it.length() > 0 }?.sortedBy { it.lastModified() }
            ?: return null
        if (files.isEmpty()) return null
        val out = java.io.File(appContext.filesDir, if (asPng) ARCHIVE_PNG else ARCHIVE_ZIP)
        runCatching {
            java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zip ->
                files.forEachIndexed { i, f ->
                    val num = (i + 1).toString().padStart(3, '0')
                    // u_<метка времени>_r2_<начало реплики>.wav -> 001_r2_<начало реплики>.wav
                    val rest = f.name.removePrefix("u_").substringAfter('_')
                    val name = if (rest.isNotBlank()) "${num}_$rest" else "$num.wav"
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }.onFailure { Log.w("TtsController", "audio zip: ${it.message}"); return null }
        return out
    }

    private fun synthToFile(text: String, speed: Float, utteranceId: String) {
        runCatching {
            val files = audioDir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
            if (files.size >= MAX_AUDIO_FILES) files.take(files.size - MAX_AUDIO_FILES + 1).forEach { it.delete() }
            // имя начинается с самой реплики — в архиве сразу видно, что где звучит
            val slug = text.replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(40)
            val f = java.io.File(audioDir, "u_${System.currentTimeMillis()}_${utteranceId}_$slug.wav")
            ensureFileEngine()
            val engine = fileEngine ?: return@runCatching
            engine.setSpeechRate(speed.coerceIn(0.25f, 2.0f))
            engine.synthesizeToFile(text, null, f, "jun_$utteranceId")
        }.onFailure { Log.w("TtsController", "audio journal: ${it.message}") }
    }

    fun stop() {
        runCatching { tts?.stop() }
        isSpeaking = false
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        runCatching { fileEngine?.shutdown() }
        tts = null
        fileEngine = null
        ready = false
    }
}
