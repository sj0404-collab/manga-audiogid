package lv.bibliogid.manga.core.library

/** Глава внутри манги (в библиотеке — либо папка, либо архив). */
data class ChapterEntry(
    val id: String,
    val name: String,
    val info: ChapterInfo,
    val isArchive: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val pageCount: Int = 0
) {
    val isExtra: Boolean get() = info.extra
}

/** Манга = папка с обложкой и главами. */
data class MangaEntry(
    val id: String,
    val title: String,
    val rootUri: String,
    val coverUri: String?,
    val chapters: List<ChapterEntry>,
    val readingMode: ReadingModeHint,
    val lastModified: Long,
    val totalSizeBytes: Long = chapters.sumOf { it.sizeBytes },
    val isWebtoon: Boolean = false,
    val folderLetter: Char = title.firstOrNull { it.isLetter() }?.uppercaseChar() ?: '#'
) {
    val chapterCount: Int get() = chapters.size
}

enum class ReadingModeHint { MANGA_RTL, COMIC_LTR, WEBTOON_VERTICAL }

/** Сортировки и фильтры библиотеки (как в референсе: Все / А-Я / Сначала новые / Популярное). */
enum class LibrarySort { TITLE_ASC, NEWEST_FIRST, CHAPTERS_DESC, SIZE_DESC }

object LibraryFilter {

    fun apply(
        items: List<MangaEntry>,
        query: String = "",
        letter: Char? = null,
        sort: LibrarySort = LibrarySort.NEWEST_FIRST,
        onlyWebtoon: Boolean = false
    ): List<MangaEntry> {
        val q = query.trim().lowercase()
        var list = items
        if (q.isNotEmpty()) list = list.filter { it.title.lowercase().contains(q) }
        if (letter != null && letter != 'В') {
            list = if (letter == '#') list.filter { !it.folderLetter.isLetter() }
            else list.filter { it.folderLetter == letter }
        }
        if (onlyWebtoon) list = list.filter { it.isWebtoon }
        return when (sort) {
            LibrarySort.TITLE_ASC -> list.sortedWith { a, b -> NaturalOrder.compare(a.title, b.title) }
            LibrarySort.NEWEST_FIRST -> list.sortedByDescending { it.lastModified }
            LibrarySort.CHAPTERS_DESC -> list.sortedByDescending { it.chapterCount }
            LibrarySort.SIZE_DESC -> list.sortedByDescending { it.totalSizeBytes }
        }
    }
}
