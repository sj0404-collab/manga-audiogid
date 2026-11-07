package lv.bibliogid.manga.ocr

import android.graphics.Bitmap
import lv.bibliogid.manga.core.ocr.Box
import lv.bibliogid.manga.core.ocr.RegionMetrics
import kotlin.math.abs

/**
 * Считает пиксельные метрики вокруг текстового блока, чтобы классификатор понял:
 * облачко это, плашка, текст поверх рисунка или звуковой эффект.
 * Никаких внешних CV-библиотек — только пиксели.
 */
object PageAnalyzer {

    fun metrics(
        bitmap: Bitmap,
        box: Box,
        lineCount: Int,
        pageWidth: Int
    ): RegionMetrics {
        val w = bitmap.width
        val h = bitmap.height
        val l = box.left.toInt().coerceIn(0, w - 1)
        val t = box.top.toInt().coerceIn(0, h - 1)
        val r = box.right.toInt().coerceIn(l + 1, w)
        val b = box.bottom.toInt().coerceIn(t + 1, h)

        val inside = IntArray((r - l) * (b - t))
        bitmap.getPixels(inside, 0, r - l, l, t, r - l, b - t)

        var insideSum = 0.0
        var minL = 255.0
        var maxL = 0.0
        for (p in inside) {
            val luma = luma(p)
            insideSum += luma
            if (luma < minL) minL = luma
            if (luma > maxL) maxL = luma
        }
        val insideMean = insideSum / inside.size.coerceAtLeast(1)

        // текст обычно темнее фона => берём среднюю яркость «чернил» как 15-й перцентиль
        val sorted = DoubleArray(inside.size) { luma(inside[it]) }.also { it.sort() }
        val inkLuma = if (sorted.isNotEmpty()) sorted[(sorted.size * 0.10).toInt().coerceIn(0, sorted.size - 1)] else insideMean
        val bgLuma = if (sorted.isNotEmpty()) sorted[(sorted.size * 0.90).toInt().coerceIn(0, sorted.size - 1)] else insideMean
        val inkDensity = if (abs(bgLuma - inkLuma) < 1.0) 0.0
        else sorted.count { abs(it - bgLuma) > abs(bgLuma - inkLuma) * 0.5 }.toDouble() / sorted.size

        // кольцо вокруг блока
        val pad = (minOf(r - l, b - t) * 0.25f).toInt().coerceIn(3, 24)
        val ringL = (l - pad).coerceAtLeast(0)
        val ringT = (t - pad).coerceAtLeast(0)
        val ringR = (r + pad).coerceAtMost(w)
        val ringB = (b + pad).coerceAtMost(h)
        val rw = ringR - ringL
        val rh = ringB - ringT
        val ring = IntArray(rw * rh)
        bitmap.getPixels(ring, 0, rw, ringL, ringT, rw, rh)

        var ringSum = 0.0
        var edgeCount = 0
        var samples = 0
        for (y in 0 until rh) {
            for (x in 0 until rw) {
                val globalX = ringL + x
                val globalY = ringT + y
                val insideBox = globalX in l until r && globalY in t until b
                if (insideBox) continue
                val idx = y * rw + x
                ringSum += luma(ring[idx])
                if (x > 0 && y > 0 && x < rw - 1 && y < rh - 1) {
                    val gx = abs(luma(ring[idx + 1]) - luma(ring[idx - 1]))
                    val gy = abs(luma(ring[idx + rw]) - luma(ring[idx - rw]))
                    if (gx + gy > 60) edgeCount++
                    samples++
                }
            }
        }
        val ringPixels = (rw * rh - (r - l) * (b - t)).coerceAtLeast(1)
        val ringMean = ringSum / ringPixels
        val edgeDensity = if (samples > 0) edgeCount.toDouble() / samples else 0.0

        val glyphHeight = if (lineCount > 0) (b - t).toFloat() / lineCount else (b - t).toFloat()

        return RegionMetrics(
            insideLuma = inkLuma,
            ringLuma = ringMean,
            inkDensity = inkDensity,
            ringEdgeDensity = edgeDensity,
            glyphHeightPx = glyphHeight,
            regionWidthPx = (r - l).toFloat(),
            chars = 0,
            columnFillRatio = (r - l).toDouble() / pageWidth.coerceAtLeast(1)
        )
    }

    private fun luma(p: Int): Double {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }
}
