package lv.bibliogid.manga.core.ocr

/**
 * «Полосы» — горизонтальные панели страницы.
 *
 * Реплики, которые по вертикали перекрываются (или почти перекрываются), относятся
 * к одной полосе. Нужно для показа «полоса 1 из 2» и для озвучки: сначала все
 * реплики верхней панели, потом нижней.
 */
object Bands {

    /**
     * Возвращает индекс полосы (с 0) для каждого прямоугольника.
     *
     * Идём сверху вниз и сравниваем только с ТЕКУЩЕЙ полосой: раньше диапазон полосы
     * расширялся на каждый новый бокс и постепенно «съедал» всю страницу — в панели
     * было «1 полоса» при шести репликах по всей высоте.
     */
    fun assign(boxes: List<Box>): IntArray {
        if (boxes.isEmpty()) return IntArray(0)
        val order = boxes.indices.sortedBy { boxes[it].top }
        val result = IntArray(boxes.size)
        var band = -1
        var start = 0f
        var end = 0f
        for (idx in order) {
            val b = boxes[idx]
            val overlap = if (band < 0) 0f else minOf(end, b.bottom) - maxOf(start, b.top)
            val join = band >= 0 && overlap >= b.height * 0.4f
            if (join) {
                end = maxOf(end, b.bottom)
                start = minOf(start, b.top)
            } else {
                band++
                start = b.top
                end = b.bottom
            }
            result[idx] = band
        }
        return result
    }

    fun count(bandIndexes: IntArray): Int = if (bandIndexes.isEmpty()) 0 else bandIndexes.max() + 1
}
