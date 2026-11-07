package lv.bibliogid.manga.core.util

/**
 * Подбор inSampleSize под экран.
 *
 * Правила:
 *  - не мельче, чем нужно: ширина страницы может быть до 2x ширины экрана (запас на зум);
 *  - не больше ~8 Мп на декод (иначе OOM на вебтунах высотой в десятки тысяч пикселей);
 *  - максимум 16x даунсемпла.
 */
object ImageScaling {

    const val MAX_PIXELS = 8_000_000L
    const val MAX_SIDE = 20_000

    fun sampleSize(width: Int, height: Int, targetWidth: Int): Int {
        if (width <= 0 || height <= 0) return 1
        val capWidth = if (targetWidth > 0) targetWidth * 2 else width
        var sample = 1
        while (sample < 16) {
            val w = width / sample
            val h = height / sample
            val tooWide = w > capWidth
            val tooManyPixels = w.toLong() * h.toLong() > MAX_PIXELS
            val tooTall = maxOf(w, h) > MAX_SIDE
            if (!tooWide && !tooManyPixels && !tooTall) break
            sample *= 2
        }
        return sample
    }

    /** Размер после даунсемпла — для логов и предварительного размещения. */
    fun scaledSize(width: Int, height: Int, sample: Int): Pair<Int, Int> =
        maxOf(1, width / sample) to maxOf(1, height / sample)
}
