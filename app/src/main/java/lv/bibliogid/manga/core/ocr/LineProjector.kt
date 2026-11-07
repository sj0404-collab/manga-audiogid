package lv.bibliogid.manga.core.ocr

/**
 * Превращает карту вероятностей детектора («где текст») в **строчные рамки**.
 *
 * Почему не связные компоненты: в манге буквы внутри строки не касаются друг друга,
 * поэтому компоненты дают «МБ1», «Ягвр» — обрывки по 2–3 буквы. Распознаватель же
 * ждёт целую строку. Здесь работает проекция:
 *   1. маска слегка «толстеет» по горизонтали, чтобы буквы строки слиплись;
 *   2. по строкам ищем горизонтальные полосы (текстовые строки);
 *   3. внутри полосы — куски по столбцам, склеиваем их, пока разрыв меньше 1.5 высоты
 *      строки (это пробел между словами), больший разрыв — уже другое облачко.
 */
object LineProjector {

    fun project(mask: BooleanArray, w: Int, h: Int, thickenX: Int = 2): List<Box> {
        if (w <= 0 || h <= 0 || mask.size < w * h) return emptyList()

        val dil = if (thickenX <= 0) mask else dilateX(mask, w, h, thickenX)

        // полосы по строкам
        val rowHas = BooleanArray(h)
        for (y in 0 until h) {
            var has = false
            val base = y * w
            for (x in 0 until w) if (dil[base + x]) { has = true; break }
            rowHas[y] = has
        }
        val bands = ArrayList<IntArray>()
        var y = 0
        while (y < h) {
            if (rowHas[y]) {
                val y0 = y
                while (y < h && rowHas[y]) y++
                bands.add(intArrayOf(y0, y))
            } else y++
        }
        // соседние полосы, разделённые маленьким зазором, — это одна строка
        val merged = ArrayList<IntArray>(bands.size)
        for (b in bands) {
            val last = merged.lastOrNull()
            if (last != null) {
                val gap = b[0] - last[1]
                val hh = last[1] - last[0]
                if (gap < maxOf(2f, hh * 0.35f)) { last[1] = b[1]; continue }
            }
            merged.add(intArrayOf(b[0], b[1]))
        }

        val out = ArrayList<Box>(merged.size)
        for (band in merged) {
            val y0 = band[0]
            val y1 = band[1]
            val bh = (y1 - y0).toFloat()
            val colHas = BooleanArray(w)
            for (yy in y0 until y1) {
                val base = yy * w
                for (x in 0 until w) if (dil[base + x]) { colHas[x] = true }
            }
            var x = 0
            val runs = ArrayList<IntArray>()
            while (x < w) {
                if (colHas[x]) {
                    val x0 = x
                    while (x < w && colHas[x]) x++
                    runs.add(intArrayOf(x0, x))
                } else x++
            }
            val maxGap = maxOf(4f, bh * 1.2f)
            val lineRuns = ArrayList<IntArray>(runs.size)
            for (r in runs) {
                val last = lineRuns.lastOrNull()
                if (last != null && r[0] - last[1] < maxGap) { last[1] = r[1]; continue }
                lineRuns.add(intArrayOf(r[0], r[1]))
            }
            for (r in lineRuns) {
                out.add(Box(r[0].toFloat(), y0.toFloat(), r[1].toFloat(), y1.toFloat()))
            }
        }
        return out
    }

    private fun dilateX(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                if (!mask[base + x]) continue
                val from = (x - radius).coerceAtLeast(0)
                val to = (x + radius).coerceAtMost(w - 1)
                for (xx in from..to) out[base + xx] = true
            }
        }
        return out
    }
}
