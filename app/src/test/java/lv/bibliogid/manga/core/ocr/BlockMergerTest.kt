package lv.bibliogid.manga.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockMergerTest {

    private fun region(l: Float, t: Float, r: Float, b: Float, text: String, type: RegionType = RegionType.BUBBLE) =
        TextRegion(Box(l, t, r, b), type, listOf(text))

    @Test
    fun `облачко разрезанное полосами склеивается в один блок`() {
        val regions = listOf(
            region(200f, 100f, 460f, 160f, "ПРИЧИНА, ПО КОТО-"),
            region(200f, 165f, 460f, 220f, "РОЙ МЫ ЗНАЕМ ДРУГ"),
            region(200f, 225f, 460f, 290f, "ДРУГА, ТАК ЭТО ТО, ЧТО"),
            region(200f, 295f, 460f, 350f, "МЫ БЫЛИ В ОДНОМ КЛАССЕ ПЕРВЫЙ ГОД!")
        )
        val merged = BlockMerger.merge(regions, pageHeight = 2853)
        assertEquals(1, merged.size)
        assertEquals(4, merged[0].rawLines.size)
        assertEquals(100f, merged[0].box.top)
        assertEquals(350f, merged[0].box.bottom)
    }

    @Test
    fun `два разных облачка не сливаются`() {
        val regions = listOf(
            region(100f, 100f, 300f, 200f, "ПРИВЕТ."),
            region(100f, 900f, 300f, 1000f, "ПОКА.")
        )
        assertEquals(2, BlockMerger.merge(regions, 2853).size)
    }

    @Test
    fun `звук не приклеивается к облачку`() {
        val regions = listOf(
            region(100f, 100f, 300f, 200f, "ПРИВЕТ."),
            region(100f, 210f, 300f, 260f, "БАМ!", RegionType.SFX)
        )
        assertEquals(2, BlockMerger.merge(regions, 2853).size)
    }

    @Test
    fun `блоки рядом по горизонтали но не перекрываются не сливаются`() {
        val regions = listOf(
            region(50f, 100f, 150f, 200f, "РАЗ."),
            region(400f, 100f, 500f, 200f, "ДВА.")
        )
        assertEquals(2, BlockMerger.merge(regions, 2853).size)
    }

    @Test
    fun `настоящая страница собирает цельное предложение`() {
        val regions = listOf(
            region(200f, 100f, 460f, 160f, "ПРИЧИНА, ПО КОТО-"),
            region(200f, 165f, 460f, 220f, "РОЙ МЫ ЗНАЕМ ДРУГ"),
            region(200f, 225f, 460f, 290f, "ДРУГА, ТАК ЭТО ТО, ЧТО"),
            region(200f, 295f, 460f, 350f, "МЫ БЫЛИ В ОДНОМ КЛАССЕ"),
            region(200f, 355f, 460f, 400f, "ПЕРВЫЙ ГОД!")
        )
        val page = PageTextBuilder().build(
            BlockMerger.merge(regions, 2853),
            ReadingMode.MANGA_RTL,
            "тест",
            0L,
            pageWidth = 690,
            pageHeight = 2853
        )
        assertTrue("реплик: ${page.replicas.size}", page.replicas.size == 1)
        assertEquals(
            "Причина, по которой мы знаем друг друга, так это то, что мы были в одном классе первый год!",
            page.replicas[0].text
        )
    }
}
