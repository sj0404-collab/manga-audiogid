package lv.bibliogid.manga.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingOrderTest {

    private fun region(id: String, l: Float, t: Float, r: Float, b: Float) =
        TextRegion(Box(l, t, r, b), RegionType.BUBBLE, listOf(id))

    @Test
    fun `манга читается справа налево по полосам`() {
        val a = region("A", 10f, 10f, 90f, 40f)    // верх лево
        val b = region("B", 110f, 12f, 190f, 42f)  // верх право
        val c = region("C", 10f, 200f, 90f, 240f)  // низ лево
        val ordered = ReadingOrder.order(listOf(a, b, c), ReadingMode.MANGA_RTL)
        assertEquals(listOf("B", "A", "C"), ordered.map { it.rawLines[0] })
    }

    @Test
    fun `комикс читается слева направо`() {
        val a = region("A", 10f, 10f, 90f, 40f)
        val b = region("B", 110f, 12f, 190f, 42f)
        val ordered = ReadingOrder.order(listOf(b, a), ReadingMode.COMIC_LTR)
        assertEquals(listOf("A", "B"), ordered.map { it.rawLines[0] })
    }

    @Test
    fun `вебтун — строго сверху вниз`() {
        val top = region("T", 100f, 10f, 200f, 40f)
        val mid = region("M", 10f, 200f, 90f, 240f)
        val bottom = region("B", 300f, 500f, 400f, 540f)
        val ordered = ReadingOrder.order(listOf(bottom, top, mid), ReadingMode.WEBTOON_VERTICAL)
        assertEquals(listOf("T", "M", "B"), ordered.map { it.rawLines[0] })
    }

    @Test
    fun `три облачка в один ряд читаются справа налево`() {
        val r1 = region("1", 300f, 10f, 380f, 60f)
        val r2 = region("2", 160f, 12f, 240f, 62f)
        val r3 = region("3", 20f, 11f, 100f, 61f)
        val ordered = ReadingOrder.order(listOf(r3, r1, r2), ReadingMode.MANGA_RTL)
        assertEquals(listOf("1", "2", "3"), ordered.map { it.rawLines[0] })
    }
}
