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

    // ------------------------------------- 1.6.17: слипшиеся облачка и пустые области

    @Test
    fun `слипшиеся облачка дают две реплики, и текст первой не теряется`() {
        val r = TextRegion(
            box = Box(140f, 100f, 610f, 886f),
            type = RegionType.BUBBLE,
            rawLines = listOf(
                "СТОП, ВСЁ", "НЕ ДОЛЖНО БЫТЬ", "ТАК ПРОСТО!",
                "ЭТО НЕ ЛЮБОВНЫЕ", "ОТНОШЕНИЯ!"
            ),
            lineBoxes = listOf(
                Box(150f, 100f, 600f, 140f),
                Box(140f, 146f, 610f, 186f),
                Box(160f, 192f, 600f, 232f),
                Box(150f, 800f, 600f, 840f),
                Box(180f, 846f, 570f, 886f)
            )
        )
        val page = builder.build(listOf(r), ReadingMode.WEBTOON_VERTICAL, "test", 1)
        assertEquals(2, page.replicas.size)
        assertTrue(page.replicas[0].text.contains("СТОП", ignoreCase = true))
        assertTrue(page.replicas[0].text.contains("ТАК ПРОСТО", ignoreCase = true))
        assertTrue(page.replicas[1].text.contains("ЛЮБОВНЫЕ", ignoreCase = true))
        // ни одна строка не пропала из текста реплик
        val all = page.replicas.joinToString(" ") { it.text.lowercase() }
        for (w in listOf("стоп", "просто", "любовные", "отношения")) {
            assertTrue("потерялось слово $w: $all", all.contains(w))
        }
    }

    @Test
    fun `пустая область во всю страницу не становится репликой`() {
        // облачко-«ванна» без букв: 40 % высоты страницы
        val r = TextRegion(Box(0f, 500f, 700f, 1500f), RegionType.BUBBLE, listOf("·"))
        val page = builder.build(
            listOf(r), ReadingMode.WEBTOON_VERTICAL, "test", 1,
            pageWidth = 700, pageHeight = 3000
        )
        assertTrue("пустая область стала репликой", page.replicas.isEmpty())
    }

    @Test
    fun `страница одним блоком всё равно делится по облачкам`() {
        // режим «вся страница одним блоком» (кнопка «Распознать заново» до 1.6.19):
        // одна реплика на весь столбец и рамка-гигант. Теперь блок режется по строкам.
        val r = TextRegion(
            box = Box(40f, 290f, 660f, 1450f),
            type = RegionType.FREE_TEXT,
            rawLines = listOf(
                "СЛУШАЙ, Я ПОДУ-", "МАЛА О КОЛИЧЕСТВЕ", "СПОСОБОВ...",
                "ЭТО ДЕЙСТ-", "ВИТЕЛЬНО НЕОБ-", "ХОДИМО?",
                "КОНЕЧНО!", "ОН БЫСТРО ЭТО", "ЗАМЕТИТ!"
            ),
            lineBoxes = listOf(
                Box(300f, 300f, 600f, 340f), Box(300f, 346f, 600f, 386f), Box(300f, 392f, 600f, 432f),
                Box(310f, 700f, 590f, 740f), Box(310f, 746f, 590f, 786f), Box(310f, 792f, 590f, 832f),
                Box(320f, 1100f, 580f, 1140f), Box(320f, 1146f, 580f, 1186f), Box(320f, 1192f, 580f, 1232f)
            )
        )
        val page = builder.build(
            listOf(r), ReadingMode.WEBTOON_VERTICAL, "test", 1,
            pageWidth = 700, pageHeight = 3081
        )
        assertEquals(3, page.replicas.size)
        assertTrue(page.replicas[0].text.contains("способов", ignoreCase = true))
        assertTrue(page.replicas[1].text.contains("необходимо", ignoreCase = true))
        assertTrue(page.replicas[2].text.contains("заметит", ignoreCase = true))
        // и рамки больше не на полстраницы
        assertTrue(page.replicas.all { it.box.height < 400f })
    }

    @Test
    fun `облачко с крупным межстрочным интервалом читается целиком`() {
        val r = TextRegion(
            box = Box(140f, 100f, 610f, 392f),
            type = RegionType.BUBBLE,
            rawLines = listOf("НО РАЗВЕ", "НАШ ЛИДЕР УЖЕ", "НЕДОСТАТОЧНО", "ПЛОХ?"),
            lineBoxes = listOf(
                Box(150f, 100f, 600f, 140f),
                Box(140f, 260f, 610f, 300f),
                Box(160f, 306f, 600f, 346f),
                Box(200f, 352f, 560f, 392f)
            )
        )
        val page = builder.build(listOf(r), ReadingMode.WEBTOON_VERTICAL, "test", 1)
        assertEquals(1, page.replicas.size)
        assertTrue(page.replicas[0].text.contains("разве", ignoreCase = true))
        assertTrue(page.replicas[0].text.contains("плох", ignoreCase = true))
    }

    @Test
    fun `разрезанная фраза склеивается обратно`() {
        // два региона одного облачка: верхний без точки на конце
        val top = TextRegion(
            Box(150f, 100f, 600f, 140f), RegionType.BUBBLE,
            listOf("НО РАЗВЕ"), lineBoxes = listOf(Box(150f, 100f, 600f, 140f))
        )
        val bottom = TextRegion(
            Box(150f, 146f, 600f, 232f), RegionType.BUBBLE,
            listOf("НАШ ЛИДЕР УЖЕ", "НЕДОСТАТОЧНО ПЛОХ?"),
            lineBoxes = listOf(Box(150f, 146f, 600f, 186f), Box(150f, 192f, 600f, 232f))
        )
        val page = builder.build(listOf(top, bottom), ReadingMode.WEBTOON_VERTICAL, "test", 1)
        assertEquals(1, page.replicas.size)
        assertTrue(page.replicas[0].text.contains("разве", ignoreCase = true))
        assertTrue(page.replicas[0].text.contains("плох", ignoreCase = true))
    }
}
