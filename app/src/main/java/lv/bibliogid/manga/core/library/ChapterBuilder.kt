package lv.bibliogid.manga.core.library

/** Что мы увидели в папке манги. */
data class RawChild(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0,
    val lastModified: Long = 0
)

/**
 * Сборка списка глав из содержимого папки манги.
 * Поддерживает три раскладки:
 *  1) главы-архивы: 1.cbz, 2.cbz ...
 *  2) главы-папки:  папка «Глава 1» с jpg-страницами
 *  3) всё в одной папке: jpg-страницы прямо в корне (одна глава)
 */
object ChapterBuilder {

    fun build(mangaId: String, children: List<RawChild>): List<ChapterEntry> {
        val dirs = children.filter { it.isDirectory }
        val archives = children.filter { !it.isDirectory && ArchiveFiles.isArchive(it.name) }
        val images = children.filter { !it.isDirectory && ArchiveFiles.isImage(it.name) }

        val out = ArrayList<ChapterEntry>()
        archives.forEach { a ->
            val info = ChapterParser.parse(a.name)
            out.add(ChapterEntry("$mangaId/${a.name}", a.name, info, true, a.sizeBytes, a.lastModified))
        }
        dirs.forEach { d ->
            val info = ChapterParser.parse(d.name)
            out.add(ChapterEntry("$mangaId/${d.name}", d.name, info, false, d.sizeBytes, d.lastModified))
        }
        if (out.isEmpty() && images.isNotEmpty()) {
            val info = ChapterParser.parse("Глава 1")
            out.add(ChapterEntry("$mangaId/", "Глава 1", info, false,
                images.sumOf { it.sizeBytes }, images.maxOfOrNull { it.lastModified } ?: 0L, images.size))
        }
        return sortChapters(out)
    }

    fun sortChapters(chapters: List<ChapterEntry>): List<ChapterEntry> {
        val hasNumbers = chapters.any { it.info.number != null || it.info.volume != null }
        return if (hasNumbers) chapters.sortedBy { it.info.sortKey }
        else chapters.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
    }

    /** Тип контента по размерам страниц мы не знаем заранее — эвристика по имени/тегам. */
    fun guessReadingMode(title: String, children: List<RawChild>): ReadingModeHint {
        val t = title.lowercase()
        if (t.contains("вебтун") || t.contains("webtoon") || t.contains("манхва") || t.contains("manhwa")) {
            return ReadingModeHint.WEBTOON_VERTICAL
        }
        if (t.contains("комикс") || t.contains("comic") || t.contains("marvel") || t.contains("dc")) {
            return ReadingModeHint.COMIC_LTR
        }
        return ReadingModeHint.MANGA_RTL
    }
}
