package lv.bibliogid.manga.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageScalingTest {

    @Test
    fun `обычная страница манги не даунсемплится`() {
        assertEquals(1, ImageScaling.sampleSize(1200, 1800, 1080))
    }

    @Test
    fun `огромная страница даунсемплится под экран`() {
        val s = ImageScaling.sampleSize(6000, 9000, 1080)
        assertTrue("sample=$s", s >= 4)
        val (w, h) = ImageScaling.scaledSize(6000, 9000, s)
        assertTrue(w <= 2160)
        assertTrue(w.toLong() * h <= ImageScaling.MAX_PIXELS)
    }

    @Test
    fun `вебтун высотой 20000 px не съедает память`() {
        val s = ImageScaling.sampleSize(800, 20000, 1080)
        val (w, h) = ImageScaling.scaledSize(800, 20000, s)
        assertTrue("w=$w h=$h", w.toLong() * h <= ImageScaling.MAX_PIXELS)
    }

    @Test
    fun `нулевые размеры не ломают`() {
        assertEquals(1, ImageScaling.sampleSize(0, 0, 1080))
    }

    @Test
    fun `совсем маленькая картинка не апскейлится`() {
        assertEquals(1, ImageScaling.sampleSize(300, 400, 1080))
    }
}
