package lv.bibliogid.manga.core.tts

import lv.bibliogid.manga.core.ocr.Replica

/**
 * Темп авточтения.
 *
 * Требование: скорость не «взлетает» на страницах без текста и не превращается в
 * кашу на длинных; пользователь задаёт множитель (0.25x … 2.0x), и он честно
 * масштабирует и озвучку, и паузы между репликами, и задержку перелистывания.
 */
data class PacingConfig(
    val charsPerSecond: Float = 13.5f,
    val minReplicaDwellMs: Long = 900,
    val maxReplicaDwellMs: Long = 20_000,
    val interReplicaGapMs: Long = 260,
    val pageTurnDelayMs: Long = 380,
    val minSpeed: Float = 0.25f,
    val maxSpeed: Float = 2.0f
)

object Pacing {

    fun clampSpeed(speed: Float, cfg: PacingConfig = PacingConfig()): Float =
        speed.coerceIn(cfg.minSpeed, cfg.maxSpeed)

    /** Сколько «должна» длиться реплика при данной скорости (для прогресса/подсветки). */
    fun replicaDurationMs(chars: Int, speed: Float, cfg: PacingConfig = PacingConfig()): Long {
        val sp = clampSpeed(speed, cfg)
        val base = if (chars <= 0) cfg.minReplicaDwellMs.toDouble()
        else (chars.toDouble() / cfg.charsPerSecond) * 1000.0
        return (base / sp).toLong().coerceIn(120L, cfg.maxReplicaDwellMs)
    }

    /** Пауза между репликами (масштабируется скоростью). */
    fun gapMs(speed: Float, cfg: PacingConfig = PacingConfig()): Long {
        val sp = clampSpeed(speed, cfg)
        return (cfg.interReplicaGapMs / sp).toLong().coerceAtLeast(60L)
    }

    /** Задержка перед перелистыванием (в т.ч. для страниц вообще без текста). */
    fun pageTurnDelayMs(speed: Float, cfg: PacingConfig = PacingConfig()): Long {
        val sp = clampSpeed(speed, cfg)
        return (cfg.pageTurnDelayMs / sp).toLong().coerceAtLeast(80L)
    }

    /**
     * Сколько стоять на странице, где нет ни одной реплики.
     * Не 0 и не «мгновенно»: страница живёт минимум minReplicaDwellMs/speed.
     */
    fun emptyPageDwellMs(speed: Float, cfg: PacingConfig = PacingConfig()): Long {
        val sp = clampSpeed(speed, cfg)
        return (cfg.minReplicaDwellMs / sp).toLong().coerceIn(200L, cfg.maxReplicaDwellMs)
    }

    /** Полная длительность автопоказа страницы. */
    fun pageDurationMs(replicas: List<Replica>, speed: Float, cfg: PacingConfig = PacingConfig()): Long {
        if (replicas.isEmpty()) return emptyPageDwellMs(speed, cfg)
        var total = 0L
        for (r in replicas) {
            total += replicaDurationMs(r.text.length, speed, cfg)
            total += gapMs(speed, cfg)
        }
        return total + pageTurnDelayMs(speed, cfg)
    }
}

/** Готовый сценарий озвучки страницы (для системного TTS). */
data class TtsUtterance(val text: String, val replicaIndex: Int, val estimateMs: Long)

object TtsScript {

    /** Дробит реплики на куски, безопасные для TTS (лимит ~3500 символов/вызов). */
    fun build(replicas: List<Replica>, speed: Float, cfg: PacingConfig = PacingConfig()): List<TtsUtterance> {
        val out = ArrayList<TtsUtterance>(replicas.size)
        for (r in replicas) {
            // «молчание» (многоточие) видно рамкой на странице, но голосом не читается
            if (r.silence) continue
            val text = r.text.trim()
            if (text.isEmpty()) continue
            // Короткий обрывок-звук («Еу.», «БАМ!», «Хмм.») — не реплика. В журнале 1.6.9
            // такие читались голосом: «стр.5 реплика 4: Еу.», «стр.7 реплика 2: Сто.».
            // Настоящие короткие реплики («Да.», «Нет.», «Что?») есть в словаре и остаются.
            if (isFiller(text)) {
                lv.bibliogid.manga.core.util.AppLog.d("tts", "обрывок не озвучивается: $text")
                continue
            }
            // «У», «О», «...» — распознанный мусор, а не реплика: в панели он остаётся,
            // но голосом такое читать нельзя
            if (text.count { it.isLetter() } < 2 && text.length < 4) continue
            val chunks = if (text.length <= 3200) listOf(text) else splitLong(text)
            for (c in chunks) {
                out.add(TtsUtterance(c, r.index, Pacing.replicaDurationMs(c.length, speed, cfg)))
            }
        }
        return out
    }

    /**
     * Короткий обрывок вместо реплики: до четырёх букв и слова нет в частотном словаре.
     * Если словарь не загружен (юнит-тесты) — ничего не отбрасываем.
     */
    fun isFiller(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val letters = t.count { it.isLetter() }
        if (letters > 4) return false
        val word = Regex("[\\p{L}\\-]+").find(t)?.value?.lowercase() ?: return true
        if (!lv.bibliogid.manga.core.text.WordFrequencies.isLoaded) return false
        return !lv.bibliogid.manga.core.text.WordFrequencies.contains(word)
    }

    fun splitLong(text: String): List<String> {
        val parts = ArrayList<String>()
        var rest = text
        while (rest.length > 3200) {
            var cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', '…'), 3200)
            if (cut <= 0) cut = rest.lastIndexOf(' ', 3200)
            if (cut <= 0) cut = 3200
            parts.add(rest.substring(0, cut + 1).trim())
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) parts.add(rest)
        return parts
    }
}
