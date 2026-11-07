package lv.bibliogid.manga.core.library

import java.text.Normalizer as JNormalizer

/**
 * Разбор имени файла/папки главы.
 * Примеры, которые обязаны работать:
 *   «Том 14. Глава 55.5 - Экстра. Большой п...»  -> volume=14, number=55.5, extra
 *   «Русификация_Том 20. Глава 80 - Вперёд!»     -> volume=20, number=80.0
 *   «Том 1 Глава 48»                             -> volume=1, number=48
 *   «ch. 012», «chapter-12», «Глава 12.5»        -> number
 */
data class ChapterInfo(
    val fileName: String,
    val displayName: String,
    val volume: Int?,
    val number: Double?,
    val extra: Boolean,
    val title: String?,
    val isRussianRename: Boolean
) {
    /** Ключ сортировки: том -> глава -> экстра. */
    val sortKey: Double
        get() = (volume ?: 0) * 1_000_000.0 + (number ?: 0.0) * 10.0 + if (extra) 0.5 else 0.0
}

object ChapterParser {

    private val volumeRegex = Regex(
        """(?iu)(?:том|vol(?:ume)?)\s*[.:]?\s*(\d{1,4})""")
    private val chapterRegex = Regex(
        """(?iu)(?:глава|гл\.?|chapter|chap\.?|ch\.?|эпизод|ep\.?)\s*[.:\-]?\s*(\d{1,5}(?:[.,]\d{1,3})?)""")
    private val bareNumberRegex = Regex("""(?:^|[^\d])(\d{1,4}(?:[.,]\d{1,3})?)(?![\d])""")
    private val extraRegex = Regex("""(?iu)(?:экстра|extra|бонус|bonus|дополн\w*|omake|side[\s-]?story|special|спэшл)""")
    private val rusRenameRegex = Regex("""(?iu)(русификац\w*|перевод|perevod)""")

    fun parse(fileName: String): ChapterInfo {
        val base = stripExtension(fileName)
        val clean = JNormalizer.normalize(base, JNormalizer.Form.NFC)
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        val volume = volumeRegex.find(clean)?.groupValues?.get(1)?.toIntOrNull()
        val chapterRaw = chapterRegex.find(clean)?.groupValues?.get(1)?.replace(',', '.')
        val number: Double? = chapterRaw?.toDoubleOrNull()
            ?: if (volume == null) bareNumberRegex.find(clean)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() else null

        val extra = extraRegex.containsMatchIn(clean)
        val title = clean.substringAfter(" - ", "").trim().ifEmpty { null }

        return ChapterInfo(
            fileName = fileName,
            displayName = prettify(clean),
            volume = volume,
            number = number,
            extra = extra,
            title = title,
            isRussianRename = rusRenameRegex.containsMatchIn(clean)
        )
    }

    /** Убираем расширение только если это правда расширение, а не «Глава 55.5». */
    private fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name
        val ext = name.substring(dot + 1)
        val looksLikeExt = ext.length in 1..5 && ext.all { it.isLetterOrDigit() }
        return if (looksLikeExt) name.substring(0, dot) else name
    }

    private fun prettify(s: String): String =
        s.replace(Regex("\\s+"), " ").trim().replaceFirstChar { it.uppercaseChar() }

    /** Быстрая проверка «это глава, а не обложка/арт». */
    fun looksLikeChapter(fileName: String): Boolean {
        val info = parse(fileName)
        return info.number != null || chapterRegex.containsMatchIn(fileName) || extraRegex.containsMatchIn(fileName)
    }
}
