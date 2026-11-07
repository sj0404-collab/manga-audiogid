package lv.bibliogid.manga.data

import android.content.Context
import android.content.SharedPreferences

/** Все настройки приложения в одном месте (SharedPreferences, без лишних зависимостей). */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("bibliogid_settings", Context.MODE_PRIVATE)

    var libraryTreeUri: String?
        get() = prefs.getString("tree_uri", null)
        set(v) { prefs.edit().putString("tree_uri", v).apply() }

    /** engine id: mlkit | paddle | yandex | google | ocrspace */
    var localEngine: String
        get() = prefs.getString("local_engine", "paddle") ?: "paddle"
        set(v) { prefs.edit().putString("local_engine", v).apply() }

    var onlineEngine: String
        get() = prefs.getString("online_engine", "yandex") ?: "yandex"
        set(v) { prefs.edit().putString("online_engine", v).apply() }

    var useOnline: Boolean
        get() = prefs.getBoolean("use_online", false)
        set(v) { prefs.edit().putBoolean("use_online", v).apply() }

    var autoOcr: Boolean
        get() = prefs.getBoolean("auto_ocr", true)
        set(v) { prefs.edit().putBoolean("auto_ocr", v).apply() }

    var apiKeyYandex: String
        get() = prefs.getString("key_yandex", "") ?: ""
        set(v) { prefs.edit().putString("key_yandex", v).apply() }

    var folderIdYandex: String
        get() = prefs.getString("folder_yandex", "") ?: ""
        set(v) { prefs.edit().putString("folder_yandex", v).apply() }

    var apiKeyGoogle: String
        get() = prefs.getString("key_google", "") ?: ""
        set(v) { prefs.edit().putString("key_google", v).apply() }

    var apiKeyOcrSpace: String
        get() = prefs.getString("key_ocrspace", "") ?: ""
        set(v) { prefs.edit().putString("key_ocrspace", v).apply() }

    /** 0.25 … 2.0 */
    var ttsSpeed: Float
        get() = prefs.getFloat("tts_speed", 1.0f)
        set(v) { prefs.edit().putFloat("tts_speed", v).apply() }

    var ttsAutoPageTurn: Boolean
        get() = prefs.getBoolean("tts_auto_turn", true)
        set(v) { prefs.edit().putBoolean("tts_auto_turn", v).apply() }

    var readMode: String
        get() = prefs.getString("read_mode", "MANGA_RTL") ?: "MANGA_RTL"
        set(v) { prefs.edit().putString("read_mode", v).apply() }

    var continuousScroll: Boolean
        get() = prefs.getBoolean("continuous", false)
        set(v) { prefs.edit().putBoolean("continuous", v).apply() }

    var keepCaps: Boolean
        get() = prefs.getBoolean("keep_caps", false)
        set(v) { prefs.edit().putBoolean("keep_caps", v).apply() }

    /** Размер кеша декодированных страниц в памяти (0 = без кеша, страницы декодируются на месте). */
    var pageCacheSize: Int
        get() = prefs.getInt("page_cache", 4)
        set(v) { prefs.edit().putInt("page_cache", v).apply() }

    var fontScale: Float
        get() = prefs.getFloat("font_scale", 1.0f)
        set(v) { prefs.edit().putFloat("font_scale", v).apply() }

    /** Показывать рамки найденного текста поверх страницы. */
    var showTextFrames: Boolean
        get() = prefs.getBoolean("show_frames", true)
        set(v) { prefs.edit().putBoolean("show_frames", v).apply() }

    /** Скрывать меню/плеер, пока идёт озвучка (по тапу возвращаются на 3 секунды). */
    var hideUiWhileReading: Boolean
        get() = prefs.getBoolean("hide_ui_reading", true)
        set(v) { prefs.edit().putBoolean("hide_ui_reading", v).apply() }

    /** Свои настройки чтения для каждой книги. */
    var perBookSettings: Boolean
        get() = prefs.getBoolean("per_book_settings", true)
        set(v) { prefs.edit().putBoolean("per_book_settings", v).apply() }

    /** Сохраняет текущие настройки чтения как настройки конкретной книги. */
    fun saveBook(title: String) {
        if (!perBookSettings || title.isBlank()) return
        val k = key(title)
        prefs.edit()
            .putFloat("${k}speed", ttsSpeed)
            .putBoolean("${k}autoturn", ttsAutoPageTurn)
            .putBoolean("${k}frames", showTextFrames)
            .putBoolean("${k}continuous", continuousScroll)
            .putFloat("${k}dwell", emptyPageDwellSec)
            .apply()
    }

    /** Применяет сохранённые настройки книги (если они есть). */
    fun applyBook(title: String): Boolean {
        if (!perBookSettings || title.isBlank()) return false
        val k = key(title)
        if (!prefs.contains("${k}speed")) return false
        ttsSpeed = prefs.getFloat("${k}speed", ttsSpeed)
        ttsAutoPageTurn = prefs.getBoolean("${k}autoturn", ttsAutoPageTurn)
        showTextFrames = prefs.getBoolean("${k}frames", showTextFrames)
        continuousScroll = prefs.getBoolean("${k}continuous", continuousScroll)
        emptyPageDwellSec = prefs.getFloat("${k}dwell", emptyPageDwellSec)
        return true
    }

    private fun key(title: String) = "book:" + title.lowercase().take(80) + ":"

    /**
     * Один раз на версию 1.3.4 включает рамки обратно: в 1.3.x переключатель был
     * неотличимой иконкой ▢/▣, его выключали случайно и думали, что рамки сломались.
     */
    fun migrateFramesOnce() {
        if (prefs.getBoolean("frames_reset_134", false)) return
        prefs.edit().putBoolean("show_frames", true).putBoolean("frames_reset_134", true).apply()
    }

    /** Один раз на версию 1.4.0: пауза на странице без текста — 5 с (было 3 с). */
    fun migrateEmptyDwellOnce() {
        if (prefs.getBoolean("dwell_reset_140", false)) return
        prefs.edit().putFloat("empty_dwell_sec", 5.0f).putBoolean("dwell_reset_140", true).apply()
    }

    /** Сколько секунд стоять на странице, где нет распознанного текста (чтобы не терять сюжет). */
    var emptyPageDwellSec: Float
        get() = prefs.getFloat("empty_dwell_sec", 3.0f)
        set(v) { prefs.edit().putFloat("empty_dwell_sec", v).apply() }

    var showReplicaNumbers: Boolean
        get() = prefs.getBoolean("show_numbers", true)
        set(v) { prefs.edit().putBoolean("show_numbers", v).apply() }
}
