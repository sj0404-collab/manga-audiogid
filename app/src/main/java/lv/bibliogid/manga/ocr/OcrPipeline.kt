package lv.bibliogid.manga.ocr

import android.graphics.Bitmap
import android.util.Log
import lv.bibliogid.manga.core.ocr.Box
import lv.bibliogid.manga.core.ocr.BoxGrower
import lv.bibliogid.manga.core.ocr.OcrLine
import lv.bibliogid.manga.core.ocr.PageText
import lv.bibliogid.manga.core.ocr.PageTextBuilder
import lv.bibliogid.manga.core.ocr.ReadingMode
import lv.bibliogid.manga.core.ocr.RegionClassifier
import lv.bibliogid.manga.core.ocr.RegionGrouper
import lv.bibliogid.manga.core.ocr.TextRegion
import lv.bibliogid.manga.core.text.JoinMode
import lv.bibliogid.manga.core.text.TextNormalizer

/**
 * Полный конвейер страницы:
 *   bitmap -> движок OCR -> строки -> группировка в регионы -> тип региона (облачко/плашка/SFX)
 *   -> порядок чтения -> чистый русский текст по репликам.
 */
class OcrPipeline {

    // Регистр всегда выравнивается (никаких «Не УВЕРЕН.», «БУДЕте», «ВЫСОЧЕСТВОМ»),
    // а JoinMode.REGION означает «одно облачко = одна реплика»: без этого ассемблер
    // судил о каждой строке отдельно и добавлял точки внутри фразы («должно. Быть, прибыла.»).
    private val builder = PageTextBuilder(TextNormalizer(joinMode = JoinMode.REGION))

    suspend fun process(
        bitmap: Bitmap,
        engine: OcrEngine,
        mode: ReadingMode,
        fullPage: Boolean = false
    ): PageText {
        val started = System.currentTimeMillis()
        val lines = try {
            engine.recognize(bitmap)
        } catch (e: Exception) {
            Log.e("OcrPipeline", "engine ${engine.id} failed: ${e.message}")
            emptyList()
        }
        val elapsed = System.currentTimeMillis() - started

        if (lines.isEmpty()) {
            return PageText(emptyList(), engine.label, elapsed, 0, 0, 0,
                warnings = listOf("движок не вернул ни строки"))
        }

        val regions = if (fullPage) {
            listOf(
                TextRegion(
                    box = lines.map { it.box }.reduce { a, b -> a.union(b) },
                    type = lv.bibliogid.manga.core.ocr.RegionType.FREE_TEXT,
                    rawLines = lines.map { it.text }
                )
            )
        } else {
            RegionGrouper.group(lines).map { (box, group) ->
                val metrics = PageAnalyzer.metrics(bitmap, box, group.size, bitmap.width)
                TextRegion(
                    box = box,
                    type = RegionClassifier.classify(metrics),
                    rawLines = group.map { it.text },
                    metrics = metrics,
                    lineBoxes = group.map { it.box }
                )
            }
        }
        val luma = if (fullPage) null else lumaOf(bitmap)
        return builder.build(
            regions, mode, engine.label, elapsed,
            pageWidth = bitmap.width,
            pageHeight = bitmap.height,
            grower = if (luma == null) null else { b -> BoxGrower.grow(luma, bitmap.width, bitmap.height, b) }
        )
    }

    /** Яркость страницы 0..255 — по ней рамка доращивается до контура облачка. */
    private fun lumaOf(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        return IntArray(w * h) { i ->
            val p = px[i]
            (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
    }
}
