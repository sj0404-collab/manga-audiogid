package lv.bibliogid.manga.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCoreTest {

    @Test
    fun `натуральная сортировка страниц`() {
        val pages = listOf("page10.jpg", "page2.jpg", "page1.jpg")
        assertEquals(listOf("page1.jpg", "page2.jpg", "page10.jpg"), ArchiveFiles.pageOrder(pages))
    }

    @Test
    fun `обложка выбирается по имени`() {
        val cover = ArchiveFiles.chooseCover(listOf("001.jpg", "cover.jpg", "002.jpg"))
        assertEquals("cover.jpg", cover)
    }

    @Test
    fun `обложка по умолчанию — первая страница`() {
        assertEquals("001.jpg", ArchiveFiles.chooseCover(listOf("002.jpg", "001.jpg")))
    }

    @Test
    fun `мусорные файлы не считаются страницами`() {
        val pages = ArchiveFiles.pageOrder(
            listOf("001.jpg", "__MACOSX/._001.jpg", "Thumbs.db", "002.png", ".hidden.jpg")
        )
        assertEquals(listOf("001.jpg", "002.png"), pages)
    }

    @Test
    fun `разбор имени главы с томом и экстрой`() {
        val i = ChapterParser.parse("Том 14. Глава 55.5 - Экстра. Большой п...")
        assertEquals(14, i.volume)
        assertEquals(55.5, i.number!!, 0.001)
        assertTrue(i.extra)
    }

    @Test
    fun `разбор имени с русификацией`() {
        val i = ChapterParser.parse("Русификация_Том 20. Глава 80 - Вперёд!")
        assertEquals(20, i.volume)
        assertEquals(80.0, i.number!!, 0.001)
        assertTrue(i.isRussianRename)
    }

    @Test
    fun `разбор простого имени`() {
        val i = ChapterParser.parse("Том 1 Глава 48")
        assertEquals(1, i.volume)
        assertEquals(48.0, i.number!!, 0.001)
    }

    @Test
    fun `главы сортируются по тому и номеру`() {
        val chapters = ChapterBuilder.build("m1", listOf(
            RawChild("Том 2. Глава 10.cbz", false, 100),
            RawChild("Том 1. Глава 5.cbz", false, 100),
            RawChild("Том 1. Глава 12.cbz", false, 100)
        ))
        assertEquals(3, chapters.size)
        assertEquals(5.0, chapters[0].info.number!!, 0.001)
        assertEquals(12.0, chapters[1].info.number!!, 0.001)
        assertEquals(10.0, chapters[2].info.number!!, 0.001)
    }

    @Test
    fun `папки-главы тоже собираются`() {
        val chapters = ChapterBuilder.build("m2", listOf(
            RawChild("Глава 1", true, 1000),
            RawChild("Глава 2", true, 2000)
        ))
        assertEquals(2, chapters.size)
        assertTrue(chapters.none { it.isArchive })
    }

    @Test
    fun `фильтр по поиску и сортировка по новизне`() {
        val manga = listOf(
            MangaEntry("1", "Grand Blue", "uri1", null, emptyList(), ReadingModeHint.MANGA_RTL, 100),
            MangaEntry("2", "Атака Титанов", "uri2", null, emptyList(), ReadingModeHint.MANGA_RTL, 200)
        )
        val found = LibraryFilter.apply(manga, query = "grand")
        assertEquals(1, found.size)
        assertEquals("Grand Blue", found[0].title)
        val newest = LibraryFilter.apply(manga, sort = LibrarySort.NEWEST_FIRST)
        assertEquals("Атака Титанов", newest[0].title)
    }

    @Test
    fun `фильтр по букве`() {
        val manga = listOf(
            MangaEntry("1", "Наруто", "u", null, emptyList(), ReadingModeHint.MANGA_RTL, 1),
            MangaEntry("2", "Ван Пис", "u", null, emptyList(), ReadingModeHint.MANGA_RTL, 2)
        )
        val n = LibraryFilter.apply(manga, letter = 'Н')
        assertEquals(1, n.size)
        assertNotNull(n.firstOrNull())
    }
}
