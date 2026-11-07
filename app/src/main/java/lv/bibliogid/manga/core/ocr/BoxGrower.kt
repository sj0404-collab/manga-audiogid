package lv.bibliogid.manga.core.ocr

/**
 * Расширение рамки региона до границ облачка.
 *
 * Детектор даёт рамку **по буквам**, а не по облачку: получается «[95,1191,303,1311]»
 * поверх левой половины белого пузыря, и рамка в читалке выглядит обрезанной. При этом
 * облачко — светлая область, ограниченная тёмным контуром, а яркость страницы у нас есть.
 * Значит рамку можно «дорастить» в четыре стороны, пока не упрёмся в тёмный пиксель
 * (контур облачка) или в предел роста.
 *
 * Рост ограничен по обеим осям, поэтому на рисунке без облачка (текст поверх светлой
 * графики) рамка не разъезжается на полстраницы.
 */
object BoxGrower {

    fun grow(
        luma: IntArray,
        w: Int,
        h: Int,
        box: Box,
        lightMin: Int = 160,
        maxGrowXFactor: Float = 1.0f,
        maxGrowYFactor: Float = 0.8f
    ): Box {
        if (w <= 0 || h <= 0 || luma.size < w * h) return box
        if (box.width < 2f || box.height < 2f) return box

        val l = box.left.toInt().coerceIn(0, w - 1)
        val t = box.top.toInt().coerceIn(0, h - 1)
        val r = box.right.toInt().coerceIn(l + 1, w)
        val b = box.bottom.toInt().coerceIn(t + 1, h)

        // Тонкий регион (одна строка внутри высокого облачка) иначе «застревает»: при
        // ограничении роста долей высоты рамка накрывала только строку, а не пузырь.
        val growX = maxOf((box.width * maxGrowXFactor).toInt(), (box.height * 1.5f).toInt())
        val growY = maxOf(
            (box.height * maxGrowYFactor).toInt(),
            (box.width * 0.6f).toInt()
        )

        val rows = samples(t, b, 13)
        val lefts = IntArray(rows.size)
        val rights = IntArray(rows.size)
        rows.forEachIndexed { i, y ->
            val base = y * w
            lefts[i] = scanLeft(luma, base, l - 1, (l - growX).coerceAtLeast(0), lightMin)
            rights[i] = scanRight(luma, base, r, (r + growX).coerceAtMost(w - 1), lightMin)
        }
        val newLeft = percentile(lefts, 0.25f).coerceAtMost(l)
        val newRight = percentile(rights, 0.75f).coerceAtLeast(r)

        val cols = samples(newLeft, newRight, 17)
        val tops = IntArray(cols.size)
        val bottoms = IntArray(cols.size)
        cols.forEachIndexed { i, x ->
            tops[i] = scanUp(luma, w, h, x, t - 1, (t - growY).coerceAtLeast(0), lightMin)
            bottoms[i] = scanDown(luma, w, h, x, b, (b + growY).coerceAtMost(h - 1), lightMin)
        }
        val newTop = percentile(tops, 0.25f).coerceAtMost(t)
        val newBottom = percentile(bottoms, 0.75f).coerceAtLeast(b)

        // Страховка от «разъехавшейся» рамки. Журнал 1.6.9, стр.16:
        //   [0,1321,719,2875] — рамка на полстраницы (в ней 5 строк из разных облачек).
        // Причина: рост по Y считался от ШИРИНЫ региона (width*0.6), а у широкой плашки
        // это сотни пикселей в каждую сторону, и на белом фоне рост не останавливался.
        // Теперь итог не может быть больше текста в разы: по X — 2,6 ширины, по Y — 3,6 высоты.
        val maxW = box.width * 2.6f + 8f
        val maxH = box.height * 3.6f + 8f
        var L = newLeft.toFloat()
        var R = newRight.toFloat()
        var T = newTop.toFloat()
        var B = newBottom.toFloat()

        if (R - L > maxW) {
            val cx = box.centerX.coerceIn(L + maxW / 2f, R - maxW / 2f)
            L = cx - maxW / 2f
            R = cx + maxW / 2f
        }
        if (B - T > maxH) {
            // прижимаем к тексту: сначала режем сверху, остаток — снизу
            val topShare = (t - T).coerceAtLeast(0f)
            val cutTop = (B - T - maxH).coerceAtMost(topShare)
            T += cutTop
            B = (T + maxH).coerceAtMost(h.toFloat())
            if (B - T > maxH) T = (B - maxH).coerceAtLeast(0f)
        }

        return Box(
            L.coerceIn(0f, w.toFloat()),
            T.coerceIn(0f, h.toFloat()),
            R.coerceIn(0f, w.toFloat()),
            B.coerceIn(0f, h.toFloat())
        )
    }

    /** идём влево, пока светло; возвращаем самый левый светлый индекс (или границу). */
    private fun scanLeft(luma: IntArray, base: Int, from: Int, limit: Int, lightMin: Int): Int {
        var x = from.coerceIn(0, luma.size - 1)
        if (base + x >= luma.size) return x
        while (x > limit && luma[base + x] >= lightMin) x--
        return if (luma[base + x] >= lightMin) x else (x + 1).coerceAtLeast(0)
    }

    /** идём вправо, пока светло; возвращаем первый тёмный индекс (или границу). */
    private fun scanRight(luma: IntArray, base: Int, from: Int, limit: Int, lightMin: Int): Int {
        var x = from.coerceAtMost(limit)
        while (x < limit && luma[base + x] >= lightMin) x++
        return x
    }

    private fun scanUp(luma: IntArray, w: Int, h: Int, x: Int, from: Int, limit: Int, lightMin: Int): Int {
        var y = from.coerceIn(0, h - 1)
        while (y > limit && luma[y * w + x] >= lightMin) y--
        return if (luma[y * w + x] >= lightMin) y else (y + 1).coerceAtMost(h - 1)
    }

    private fun scanDown(luma: IntArray, w: Int, h: Int, x: Int, from: Int, limit: Int, lightMin: Int): Int {
        var y = from.coerceAtMost(limit)
        while (y < limit && luma[y * w + x] >= lightMin) y++
        return y
    }

    private fun samples(from: Int, to: Int, max: Int): IntArray {
        val span = to - from
        if (span <= 2) return intArrayOf((from + to) / 2)
        val n = minOf(max, span - 1).coerceAtLeast(1)
        return IntArray(n) { i ->
            (from + ((i + 0.5f) * span / n).toInt()).coerceIn(from + 1, to - 1)
        }
    }

    private fun percentile(values: IntArray, q: Float): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sortedArray()
        val idx = (q * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
