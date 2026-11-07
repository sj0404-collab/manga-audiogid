package lv.bibliogid.manga.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class BandsTest {

    @Test
    fun `две панели — две полосы`() {
        val boxes = listOf(
            Box(10f, 10f, 200f, 80f),    // верхняя панель
            Box(220f, 12f, 400f, 82f),   // верхняя панель, правее
            Box(10f, 300f, 200f, 400f)   // нижняя панель
        )
        val bands = Bands.assign(boxes)
        assertEquals(0, bands[0])
        assertEquals(0, bands[1])
        assertEquals(1, bands[2])
        assertEquals(2, Bands.count(bands))
    }

    @Test
    fun `три облачка в одной панели — одна полоса`() {
        val boxes = listOf(
            Box(10f, 100f, 80f, 180f),
            Box(100f, 110f, 180f, 190f),
            Box(200f, 105f, 280f, 185f)
        )
        val bands = Bands.assign(boxes)
        assertEquals(1, Bands.count(bands))
    }

    @Test
    fun `небольшое перекрытие по границе не склеивает разные полосы`() {
        // перекрытие 10 px при высоте 130 px (≈8 %) — это разные панели
        val boxes = listOf(
            Box(10f, 10f, 200f, 200f),
            Box(10f, 190f, 200f, 320f),
            Box(10f, 330f, 200f, 500f)
        )
        val bands = Bands.assign(boxes)
        assertEquals(3, Bands.count(bands))
        assertEquals(listOf(0, 1, 2), bands.toList())
    }

    @Test
    fun `сильное перекрытие (больше 40 процентов) объединяет в одну полосу`() {
        val boxes = listOf(
            Box(10f, 10f, 200f, 200f),
            Box(210f, 120f, 400f, 190f)
        )
        val bands = Bands.assign(boxes)
        assertEquals(1, Bands.count(bands))
        assertEquals(bands[0], bands[1])
    }

    @Test
    fun `пустой список`() {
        assertEquals(0, Bands.count(Bands.assign(emptyList())))
    }
}
