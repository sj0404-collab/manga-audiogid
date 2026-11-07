package lv.bibliogid.manga.core.ocr

import lv.bibliogid.manga.core.text.Homoglyphs
import lv.bibliogid.manga.core.text.WordFrequencies

/**
 * Отсев «нереплик»: декоративных заголовков, номеров глав/страниц, латинских ярлыков.
 *
 * Из журналов 1.6.0–1.6.1:
 *   «Жак прожить жизнь, оуеуиь смаршеи даже.» — заставка-название тайтла;
 *   «48» и «T4d» — номер главы, распознанный как строка.
 *
 * Из журнала 1.6.8 (20:54) — ОБРАТНАЯ ошибка: фильтр срезал живой диалог.
 *   «пропущено как декор [130,0,436,129]: Не позволяйте себе увлечься её высочеством.»
 *   «пропущено как декор [325,421,612,654]: Вы должны быть равноадушны…»
 * Оба облачка стоят в верхней части страницы, а OCR исказил пару слов
 * («равноадушны», «обаумываете») — и правило «сверху + 2 незнакомых слова»
 * выбросило настоящие реплики. Поэтому правило заставки теперь требует ещё и
 * почти полной ширины страницы, а узнаваемый диалог (3+ слова из словаря)
 * не режется в принципе.
 */
object DecorativeFilter {

    /** Ссылки и домены: «vk.com/assleyteam», «t.me/…», «http…». */
    private val LINK = Regex(
        "(?i)(https?://|www\\.|t\\.me/|\\b[a-z0-9-]{2,}\\.(com|ru|net|org|me|io|tv|su|info|xyz)\\b)"
    )

    /** Слова из подписи команды перевода. */
    private val CREDIT_WORDS = listOf(
        "перевод", "перевол", "тайпер", "редактор", "оформлен", "вконтакте",
        "telegram", "телеграм", "assley", "асслей", "scanlat", "scanlation"
    )

    fun isDecorative(
        text: String,
        box: Box,
        pageWidth: Int,
        pageHeight: Int
    ): Boolean = reason(text, box, pageWidth, pageHeight) != null

    /** Почему строка признана декором (для журнала), null — это реплика. */
    fun reason(
        text: String,
        box: Box,
        pageWidth: Int,
        pageHeight: Int
    ): String? {
        val trimmed = text.trim()
        // пустой текст не «декорация»: он остаётся репликой с меткой «проверить»,
        // чтобы было видно, что тут что-то было (озвучка такое не читает)
        if (trimmed.isEmpty()) return null
        val letters = trimmed.count { it.isLetter() }
        val digits = trimmed.count { it.isDigit() }

        // ---------- правила, не зависящие от места на странице ----------
        // Журнал 1.6.9 (21:52), последняя страница главы — всё это звучало голосом:
        //   3. [0,2222,719,2825] Мак прожит жизнь, оуаучиь старшей сесmbои mbана.
        //   4. [135,2713,588,2957] Перевол аѕѕеу теам лuоllт тlл.
        //   5. [0,2887,719,3082] Со аѕѕеутеам.
        // Это название тайтла и подпись команды перевода, а не реплики. Раньше
        // «декор» искали только в верхней части страницы, поэтому внизу он проходил.
        if (LINK.containsMatchIn(trimmed)) return "ссылка/водяной знак"
        val lower = trimmed.lowercase()
        if (CREDIT_WORDS.any { lower.contains(it) }) return "подпись команды перевода"

        // Латиница в реплике манги почти не встречается: «ASSLEY TEAM», «сесmbои», «лuоllт».
        val latin = trimmed.count { it in 'A'..'Z' || it in 'a'..'z' }
        if (latin >= 4 || (latin >= 3 && latin * 5 >= letters)) return "латиница/подпись"

        // одни цифры/знаки — номер страницы, главы, «48»
        if (letters == 0) return if (digits > 0) "номер без букв" else null
        // одна буква («Е», «R», «T») — никогда не реплика
        if (letters < 2) return "одна буква"
        // «T4d»: пара латинских букв с цифрой или чисто латинский ярлык
        if (letters <= 2) {
            val allLatin = trimmed.filter { it.isLetter() }.all { Homoglyphs.isLatinLetter(it) }
            if (digits > 0 || allLatin) return "латинский ярлык"
        }

        if (!WordFrequencies.isLoaded) return null

        val words = Regex("[\\p{L}\\-]+").findAll(trimmed)
            .map { it.value.lowercase() }
            .filter { it.count { c -> c.isLetter() } >= 2 }
            .toList()
        if (words.isEmpty()) return null
        val unknown = words.count { !WordFrequencies.contains(it) }
        val known = words.size - unknown
        val junkRatio = unknown.toDouble() / words.size
        val topRatio = if (pageHeight > 0) box.top / pageHeight else 1f
        // заставка тайтла занимает почти всю ширину страницы (в журналах — 0,76 и 1,0 ширины)
        val wide = pageWidth > 0 && box.width >= pageWidth * 0.65f

        // 0) подпись/водяной знак в любом месте страницы: широко, и почти нет словарных слов
        //    («Со аѕѕеутеам.» — 719 px из 720, узнано одно служебное слово «со»)
        if (pageWidth > 0 && box.width >= pageWidth * 0.60f && known <= 2 && junkRatio >= 0.40) {
            return "подпись/водяной знак"
        }
        // 1) заставка-название: во всю ширину, у самого верха, и есть «не слова»
        //    Узкие облачка (0,3–0,45 ширины) сюда не попадают никогда — в журнале 20:54
        //    именно так были срезаны две настоящие реплики в верхней части страницы.
        if (topRatio < 0.20f && wide && junkRatio >= 0.20) return "заставка-название"
        // 2) живой диалог не режем: три узнанных слова — это уже реплика,
        //    даже если OCR исказил «равноадушны» или «обаумываете».
        //    Имя героя («КИМ МУВОН») словарю неизвестно, но это тоже реплика.
        if (known >= 3) return null
        // 3) номер главы крупным шрифтом сверху страницы
        if (topRatio < 0.35f && words.size <= 2 && digits >= 1 && unknown == words.size) {
            return "номер главы"
        }
        return null
    }
}
