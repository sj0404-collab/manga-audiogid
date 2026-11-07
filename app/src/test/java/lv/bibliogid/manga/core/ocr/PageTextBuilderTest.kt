package lv.bibliogid.manga.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTextBuilderTest {

    private val builder = PageTextBuilder()

    @Test
    fun `порядок реплик соответствует манге и текст чистый`() {
        val left = TextRegion(Box(10f, 300f, 200f, 360f), RegionType.BUBBLE,
            listOf("ЭТО ЛЕВОЕ ОБЛАЧКО", "ВТОРАЯ СТРОКА"))
        val right = TextRegion(Box(400f, 300f, 600f, 360f), RegionType.BUBBLE,
            listOf("ЭТО ПРАВОЕ ОБЛАЧКО"))
        val page = builder.build(listOf(left, right), ReadingMode.MANGA_RTL, "test", 12)
        assertEquals(2, page.replicas.size)
        assertTrue(page.replicas[0].text.contains("правое", ignoreCase = true))
        assertTrue(page.replicas[1].text.contains("левое", ignoreCase = true))
        assertEquals(0, page.latinLeft)
        assertFalse(page.replicas[0].text.any { it.code > 0x0400 && it.code < 0x0530 && it.code > 0x04FF })
    }

    @Test
    fun `ни одна реплика не теряется`() {
        val regions = (0 until 5).map { i ->
            // разные облачка: края не совпадают (иначе это была бы одна плашка)
            TextRegion(
                Box(i * 9f, i * 170f, 200f - i * 7f, i * 170f + 80f),
                RegionType.BUBBLE,
                listOf("РЕПЛИКА $i.")
            )
        }
        val page = builder.build(regions, ReadingMode.WEBTOON_VERTICAL, "test", 1)
        assertEquals(5, page.replicas.size)
        assertEquals(5, page.rawLineCount)
    }

    @Test
    fun `регион без читаемого текста помечается, а не исчезает`() {
        val region = TextRegion(Box(0f, 0f, 100f, 50f), RegionType.BUBBLE, listOf("$$$ ^^^ λλ"))
        val page = builder.build(listOf(region), ReadingMode.MANGA_RTL, "test", 1)
        assertTrue(page.replicas.any { it.needsReview })
    }

    @Test
    fun `единица показа — предложение, а не строка OCR`() {
        val r = TextRegion(Box(0f, 0f, 300f, 100f), RegionType.BUBBLE, listOf(
            "ВЫ ДОЛЖНЫ БЫТЬ", "РАВНОДУШНЫ, КАК И ВСЕГДА."
        ))
        val page = builder.build(listOf(r), ReadingMode.MANGA_RTL, "test", 1)
        assertEquals(1, page.replicas.size)
        assertEquals("Вы должны быть равнодушны, как и всегда.", page.replicas[0].text)
    }

    @Test
    fun `реплики разносятся по полосам, и полосы считаются`() {
        val top = TextRegion(Box(10f, 10f, 300f, 90f), RegionType.CAPTION, listOf("ВЕРХНЯЯ ПАНЕЛЬ"))
        val bottom = TextRegion(Box(10f, 400f, 300f, 480f), RegionType.BUBBLE, listOf("НИЖНЯЯ ПАНЕЛЬ"))
        val page = builder.build(listOf(top, bottom), ReadingMode.WEBTOON_VERTICAL, "test", 5)
        assertEquals(2, page.bandCount)
        assertEquals(1, page.band(0).size)
        assertEquals(1, page.band(1).size)
        assertEquals(listOf(0, 1), page.replicas.map { it.bandIndex }.sorted())
    }

    @Test
    fun `сырой текст движка сохраняется для секции «как распознано»`() {
        val r = TextRegion(Box(0f, 0f, 200f, 80f), RegionType.BUBBLE, listOf("ПРИВЕТ, МИР"))
        val page = builder.build(listOf(r), ReadingMode.MANGA_RTL, "test", 2)
        assertTrue(page.rawText.isNotEmpty())
        assertEquals("ПРИВЕТ, МИР", page.rawText)
        // «как читается» — уже исправленный текст (регистр, запятая, точка в конце)
        assertEquals("Привет, мир.", page.replicas[0].text)
    }

    @Test
    fun `флаг кеша ставится отдельно от данных страницы`() {
        val r = TextRegion(Box(0f, 0f, 200f, 80f), RegionType.BUBBLE, listOf("ТЕКСТ"))
        val page = builder.build(listOf(r), ReadingMode.MANGA_RTL, "test", 1)
        assertFalse(page.fromCache)
        assertTrue(page.withCacheFlag(true).fromCache)
        assertEquals(page.replicas.size, page.withCacheFlag(true).replicas.size)
    }

    @Test
    fun `пустая страница — ноль полос, а не падение`() {
        val page = builder.build(emptyList(), ReadingMode.MANGA_RTL, "test", 1)
        assertTrue(page.isEmpty)
        assertEquals(0, page.bandCount)
        assertEquals(0, page.band(0).size)
    }

    @Test
    fun `координаты рамок и размер страницы доходят до реплик`() {
        val r = TextRegion(Box(100f, 200f, 400f, 300f), RegionType.BUBBLE, listOf("КИМ МУВОН"))
        val page = builder.build(listOf(r), ReadingMode.MANGA_RTL, "test", 3, pageWidth = 1080, pageHeight = 1920)
        assertEquals(1080, page.pageWidth)
        assertEquals(1920, page.pageHeight)
        assertEquals(100f, page.replicas[0].box.left)
        assertEquals(300f, page.replicas[0].box.bottom)
    }

    @Test
    fun `строка мусора не даёт пустую реплику, но помечается`() {
        val r = TextRegion(Box(0f, 0f, 100f, 50f), RegionType.SFX, listOf("$$$ ^^^ ~|||"))
        val page = builder.build(listOf(r), ReadingMode.MANGA_RTL, "test", 1)
        assertEquals(1, page.replicas.size)
        assertTrue(page.replicas[0].needsReview)
    }

    @Test
    fun `одно облачко — одна реплика, а не фраза за фразой`() {
        val region = TextRegion(
            Box(10f, 10f, 400f, 200f),
            RegionType.BUBBLE,
            listOf(
                "ОДНАКО. Я НИЧЕГО НЕ МОГУ ПОДЕЛАТЬ.",
                "ЕСЛИ ЛЮДИ ПОЙМУТ, ЧТО Я БЛИЗКА С КИМ МУВОНОМ,",
                "ТО ОНИ НАЧНУТ ДРАЗНИТЬ МЕНЯ С НИМ!"
            )
        )
        val page = builder.build(listOf(region), ReadingMode.MANGA_RTL, "test", 1)
        assertEquals(1, page.replicas.size)
        assertTrue(page.replicas[0].text.contains("дразнить", ignoreCase = true))
    }
}
