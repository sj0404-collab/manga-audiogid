package lv.bibliogid.manga.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lv.bibliogid.manga.core.ocr.Box
import lv.bibliogid.manga.core.ocr.LineProjector
import lv.bibliogid.manga.core.ocr.OcrLine
import java.io.File
import java.nio.FloatBuffer

/**
 * Локальный движок #2 — PaddleOCR (PP-OCR) на ONNX Runtime, кириллица.
 *
 * Полностью на устройстве, без сети и БЕЗ Tesseract:
 *   det.onnx — детектор текстовых областей (DB, вход 3xHxW, выход probability map),
 *   rec.onnx — распознаватель строк (CRNN/CTC, вход 3x48xW, выход [T, 165]),
 *   dict.txt — словарь символов.
 *
 * Выход модели — «сырой» текст; чистку делает TextNormalizer (гомоглифы латиница->кириллица,
 * мусор, склейка предложений). Так латинские двойники («Bы долЖHы») превращаются в «Вы должны».
 */
class PaddleOcrEngine(
    private val detModel: File,
    private val recModel: File,
    private val dictFile: File,
    private val detLimitSide: Int = 1200,
    private val detThresh: Float = 0.25f,
    private val minConfidence: Float = 0.10f
) : OcrEngine {

    override val id = "paddle"
    override val label = "PaddleOCR (кириллица) · на устройстве"
    override val isOnline = false

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    /** Детекция по плиткам — параллельно, но не больше двух плиток (память важнее). */
    private val detPool: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
            Thread(r, "ocr-det").apply { isDaemon = true }
        }
    }

    /**
     * Строки распознаём параллельно: раньше 14 строк страницы шли в `rec` по очереди и
     * страница занимала 4+ секунды. ONNX Runtime допускает один `run` на сессию
     * одновременно из разных потоков, поэтому 3–4 потока дают почти линейный выигрыш.
     */
    private val recPool: java.util.concurrent.ExecutorService by lazy {
        val n = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        java.util.concurrent.Executors.newFixedThreadPool(n) { r ->
            Thread(r, "ocr-rec").apply { isDaemon = true }
        }
    }
    private var det: OrtSession? = null
    private var rec: OrtSession? = null
    private var chars: List<String> = emptyList()

    private fun ensureLoaded() {
        if (det != null && rec != null) return
        val detOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        // у распознавателя строки идут параллельно на уровне нашего пула,
        // внутренние потоки ему только мешают (борьба за ядра)
        val recOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        det = env.createSession(detModel.absolutePath, detOptions)
        rec = env.createSession(recModel.absolutePath, recOptions)
        // индекс 0 — blank; далее словарь; последний — пробел (use_space_char)
        // Словарь PP-OCRv5 (кириллица): индекс 0 — CTC blank, далее символы, последний — пробел.
        val dict = dictFile.readLines().filter { it.isNotEmpty() }
        chars = buildList {
            add("")
            addAll(dict)
            add(" ")
        }
        Log.i("PaddleOcr", "loaded: det=${detModel.length()} rec=${recModel.length()} chars=${chars.size}")
    }

    val isReady: Boolean
        get() = detModel.exists() && recModel.exists() && dictFile.exists()

    override suspend fun recognize(bitmap: Bitmap): List<OcrLine> = withContext(Dispatchers.Default) {
        ensureLoaded()
        val t0 = System.currentTimeMillis()
        val boxes = detectAll(bitmap)
        val out = ArrayList<OcrLine>(boxes.size)
        val futures = boxes.map { b ->
            recPool.submit(java.util.concurrent.Callable { recognizeMulti(bitmap, b) })
        }
        for (f in futures) {
            out.addAll(runCatching { f.get() }.getOrDefault(emptyList()))
        }
        val elapsed = System.currentTimeMillis() - t0
        Log.i(
            "PaddleOcr",
            "page ${bitmap.width}x${bitmap.height}: боксов ${boxes.size}, строк ${out.size} за $elapsed мс"
        )
        lv.bibliogid.manga.core.util.AppLog.d(
            "ocr",
            "детекция ${bitmap.width}x${bitmap.height}: боксов ${boxes.size}, " +
                "распознано строк ${out.size} (отброшено ${boxes.size - out.size}) за $elapsed мс"
        )
                out.sortedWith(compareBy({ it.box.top }, { it.box.left }))
    }

    // ---------------------------------------------------------------- detection

    /**
     * Детектор (DB, PP-OCRv5) даёт карту вероятностей «где текст». Превращаем её
     * в СТРОЧНЫЕ рамки через [LineProjector]: буквы одной строки склеиваются,
     * строки разных облачков не склеиваются. Раньше здесь были связные компоненты,
     * из-за чего распознаватель получал обрывки «МБ1», «Ягвр» вместо строки.
     */
    /**
     * Детекция по всей странице. Длинные вебтун-полосы (например 690×2853) нельзя
     * подавать в детектор целиком: он масштабирует по длинной стороне до 1200 px, и от
     * страницы остаётся 290 px шириной — буквы высотой 5–7 px, детектор их не видит,
     * отсюда «не захватывает строки». Поэтому режем страницу на плитки с перекрытием
     * и распознаём каждую в полном разрешении.
     */
    private fun detectAll(bitmap: Bitmap): List<Box> {
        // Узкие вебтун-страницы (720 px) увеличиваем перед детекцией: модель приводит плитку
        // к своей рабочей стороне, поэтому в кадр попадают буквы в DET_UPSCALE раз крупнее —
        // мелкие облачка («Хотя это всего лишь контракт…», вторая половина «Не уверен…»)
        // находятся. Распознавание идёт по исходной странице, точность не теряется.
        val upscale = if (bitmap.width in 1..800) DET_UPSCALE else 1f
        val src = if (upscale > 1f) Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * upscale).toInt(),
            (bitmap.height * upscale).toInt(),
            true
        ) else bitmap

        val boxes = detectTiles(src)
        if (src !== bitmap) src.recycle()
        if (upscale == 1f) return boxes
        return boxes.map { b ->
            Box(b.left / upscale, b.top / upscale, b.right / upscale, b.bottom / upscale)
        }
    }

    /** Плитки одной страницы детектируем параллельно (не больше 2 одновременно — память). */
    private fun detectTiles(bitmap: Bitmap): List<Box> {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= (detLimitSide * 1.1f).toInt()) return detect(bitmap)

        val overlap = detLimitSide / 6 // ~17 % перекрытия, чтобы не резать строки пополам
        val step = (detLimitSide - overlap).coerceAtLeast(64)
        val vertical = bitmap.height >= bitmap.width
        val offsets = ArrayList<Pair<Int, Int>>() // смещение и размер
        var pos = 0
        val limit = if (vertical) bitmap.height else bitmap.width
        while (pos < limit) {
            val size = minOf(detLimitSide, limit - pos)
            offsets.add(pos to size)
            if (pos + size >= limit) break
            pos += step
        }

        val futures = offsets.map { (offset, size) ->
            detPool.submit(java.util.concurrent.Callable {
                val tile = if (vertical) {
                    Bitmap.createBitmap(bitmap, 0, offset, bitmap.width, size)
                } else {
                    Bitmap.createBitmap(bitmap, offset, 0, size, bitmap.height)
                }
                val found = try {
                    detect(tile)
                } finally {
                    if (tile !== bitmap) tile.recycle()
                }
                if (vertical) found.map { Box(it.left, it.top + offset, it.right, it.bottom + offset) }
                else found.map { Box(it.left + offset, it.top, it.right + offset, it.bottom) }
            })
        }
        val out = ArrayList<Box>()
        for (f in futures) out.addAll(runCatching { f.get() }.getOrDefault(emptyList()))
        return dedupe(out).sortedWith(compareBy({ it.top }, { it.left }))
    }

    /** Одна и та же строка, попавшая в две перекрывающиеся плитки, остаётся один раз. */
    private fun dedupe(boxes: List<Box>): List<Box> {
        val kept = ArrayList<Box>(boxes.size)
        for (b in boxes.sortedByDescending { it.area }) {
            var mergedInto = -1
            for (i in kept.indices) {
                if (sameLine(kept[i], b)) { mergedInto = i; break }
            }
            if (mergedInto >= 0) {
                // Одна и та же строка попалась дважды (перекрытие плиток, два масштаба) или
                // была обрезана краем плитки — берём объединение, иначе в тексте появлялись
                // задвоенные слова: «когда вы будете ововить с сё те говорить».
                kept[mergedInto] = kept[mergedInto].union(b)
            } else {
                kept += b
            }
        }
        return kept
    }

    /** Похоже ли на одну и ту же строку: сильное перекрытие или общая горизонтальная полоса. */
    private fun sameLine(a: Box, b: Box): Boolean {
        val interH = a.horizontalOverlap(b)
        val interV = a.verticalOverlap(b)
        val inter = interH * interV
        val minArea = minOf(a.area, b.area).coerceAtLeast(1f)
        if (inter / minArea > 0.55f) return true
        val minH = minOf(a.height, b.height).coerceAtLeast(1f)
        val minW = minOf(a.width, b.width).coerceAtLeast(1f)
        return interV / minH > 0.7f && interH / minW > 0.45f
    }

    private fun iou(a: Box, b: Box): Float {
        val l = maxOf(a.left, b.left)
        val t = maxOf(a.top, b.top)
        val r = minOf(a.right, b.right)
        val bo = minOf(a.bottom, b.bottom)
        val inter = (r - l).coerceAtLeast(0f) * (bo - t).coerceAtLeast(0f)
        val union = a.area + b.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun detect(bitmap: Bitmap): List<Box> {
        val scale = detLimitSide.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat()
        val nw = (roundTo32((bitmap.width * scale).toInt())).coerceAtLeast(32)
        val nh = (roundTo32((bitmap.height * scale).toInt())).coerceAtLeast(32)
        val resized = Bitmap.createScaledBitmap(bitmap, nw, nh, true)
        val pixels = IntArray(nw * nh)
        resized.getPixels(pixels, 0, nw, 0, 0, nw, nh)
        if (resized !== bitmap) resized.recycle()

        val data = FloatArray(3 * nw * nh)
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            data[i] = (r - mean[0]) / std[0]
            data[nw * nh + i] = (g - mean[1]) / std[1]
            data[2 * nw * nh + i] = (b - mean[2]) / std[2]
        }
        val input = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, nh.toLong(), nw.toLong()))
        val prob: FloatArray = input.use {
            det!!.run(mapOf("x" to it)).use { res ->
                val tensor = res[0] as OnnxTensor
                val buf = tensor.floatBuffer
                FloatArray(buf.remaining()).also { b -> buf.get(b) }
            }
        }
        val mask = BooleanArray(nw * nh) { prob[it] > detThresh }
        // «толщина» по горизонтали: в детекции на 1200 px буквы разделяют 1-2 пикселя
        val thicken = maxOf(1, (nw / 320f).toInt())
        val boxes = LineProjector.project(mask, nw, nh, thickenX = thicken)

        val sx = bitmap.width.toFloat() / nw
        val sy = bitmap.height.toFloat() / nh
        return boxes.mapNotNull { b ->
            val pad = maxOf(2f, b.height * 0.12f)
            val l = (b.left * sx - pad).coerceAtLeast(0f)
            val t = (b.top * sy - pad).coerceAtLeast(0f)
            val r = (b.right * sx + pad).coerceAtMost(bitmap.width.toFloat())
            val bt = (b.bottom * sy + pad).coerceAtMost(bitmap.height.toFloat())
            val out = Box(l, t, r, bt)
            if (out.width < 10f || out.height < 7f) null else out
        }.sortedWith(compareBy({ it.top }, { it.left }))
    }

    private fun roundTo32(v: Int): Int = ((v + 31) / 32) * 32

    private fun cropRegion(bitmap: Bitmap, box: Box): Bitmap? {
        val l = box.left.toInt().coerceIn(0, bitmap.width - 1)
        val t = box.top.toInt().coerceIn(0, bitmap.height - 1)
        val r = box.right.toInt().coerceIn(l + 1, bitmap.width)
        val b = box.bottom.toInt().coerceIn(t + 1, bitmap.height)
        return runCatching { Bitmap.createBitmap(bitmap, l, t, r - l, b - t) }.getOrNull()
    }

    // ------------------------------------------------------------ recognition

    /** Кроп строки -> распознавание -> отсев мусора. Вызывается из пула потоков. */
    /**
     * Распознаёт область и возвращает ОДНУ ИЛИ НЕСКОЛЬКО строк.
     *
     * Детектор иногда отдаёт один высокий бокс на две-три строки облачка: в этом шрифте
     * межстрочный интервал маленький, полосы пикселей слипаются. Распознаватель ждёт одну
     * строку, поэтому такой бокс давал мусор и отбрасывался — так и пропадали строки
     * «КАК СТОИТ СКА-ЗАТЬ ЭТО, НО…». Теперь высокий бокс разрезается по пустым строкам
     * пикселей на отдельные строки, и каждая распознаётся сама.
     */
    private fun recognizeMulti(bitmap: Bitmap, b: Box): List<OcrLine> {
        val crop = cropRegion(bitmap, b) ?: return emptyList()
        try {
            val w = crop.width
            val h = crop.height
            if (w <= 0 || h <= 0) return emptyList()
            val wide = w.toFloat() / h >= 2.2f || h < 26
            val strips = if (wide) listOf(0 to h) else splitInkRows(crop)
            if (strips.size <= 1) {
                val line = recognizeCrop(crop, b)
                return if (line != null) listOf(line) else emptyList()
            }
            val out = ArrayList<OcrLine>(strips.size)
            for ((top, bottom) in strips) {
                if (bottom - top < 6) continue
                val sub = Bitmap.createBitmap(crop, 0, top, w, bottom - top)
                try {
                    val line = recognizeCrop(sub, Box(b.left, b.top + top, b.right, b.top + bottom))
                    if (line != null) out.add(line)
                } finally {
                    if (sub !== crop) sub.recycle()
                }
            }
            if (out.isEmpty()) {
                val line = recognizeCrop(crop, b)
                if (line != null) out.add(line)
            }
            return out
        } finally {
            crop.recycle()
        }
    }

    /** Один кроп -> строка. Мусор (штрихи, одиночные буквы) отбрасываем и пишем в журнал. */
    private fun recognizeCrop(crop: Bitmap, pageBox: Box): OcrLine? {
        val (text, conf) = recognizeLine(crop)
        val letters = text.count { it.isLetter() }
        // Многоточие — не мусор, а облачко-«молчание»: его надо ПОКАЗАТЬ рамкой,
        // но голосом не читать. Раньше такие кропы отбрасывались здесь (в них
        // ноль букв), и до страницы «…» вообще не доходило — в журнале 20:54
        // облачко с точками на стр.2 осталось без рамки.
        if (letters < 2 && lv.bibliogid.manga.core.ocr.Silence.isSilence(text)) {
            lv.bibliogid.manga.core.util.AppLog.d(
                "ocr",
                "молчание «…»: уверенность " + "%.2f".format(conf) + ", [" +
                    pageBox.left.toInt() + "," + pageBox.top.toInt() + "," +
                    pageBox.right.toInt() + "," + pageBox.bottom.toInt() + "]"
            )
            return OcrLine("…", pageBox, conf)
        }
        if (letters < 2 || conf < minConfidence) {
            // логируем ВСЁ, что отбросили: без этого «пропало облачко» не разобрать —
            // в журнале 20:54 из 9 отброшенных строк было видно только 2
            run {
                lv.bibliogid.manga.core.util.AppLog.d(
                    "ocr",
                    "отброшено: уверенность " + "%.2f".format(conf) + ", букв " + letters + ", " +
                        "[" + pageBox.left.toInt() + "," + pageBox.top.toInt() + "," +
                        pageBox.right.toInt() + "," + pageBox.bottom.toInt() + "]: «" + text.trim() + "»"
                )
            }
            return null
        }
        return OcrLine(text.trim(), pageBox, conf)
    }

    /**
     * Разрезает высокий кроп на полосы текста по «чернилам»: в белом облачке чернила тёмные,
     * в тёмном рисунке — светлые. Пустые края и промежутки между строками отрезаются.
     */
    private fun splitInkRows(crop: Bitmap): List<Pair<Int, Int>> {
        val w = crop.width
        val h = crop.height
        val px = IntArray(w * h)
        crop.getPixels(px, 0, w, 0, 0, w, h)
        val luma = IntArray(w * h) { i ->
            val p = px[i]
            (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        var sum = 0L
        for (v in luma) sum += v
        val mean = sum.toDouble() / luma.size
        val dark = mean > 128
        val threshold = if (dark) (mean - 45).toInt() else (mean + 45).toInt()
        val ink = IntArray(h)
        for (y in 0 until h) {
            var c = 0
            val base = y * w
            for (x in 0 until w) {
                val v = luma[base + x]
                if (if (dark) v < threshold else v > threshold) c++
            }
            ink[y] = c
        }
        val minInk = maxOf(2, (w * 0.03f).toInt())
        val strips = ArrayList<Pair<Int, Int>>()
        var y = 0
        while (y < h) {
            if (ink[y] >= minInk) {
                val y0 = y
                while (y < h && ink[y] >= minInk) y++
                strips.add(y0 to y)
            } else y++
        }
        val minH = maxOf(6, h / 10)
        return strips.filter { it.second - it.first >= minH }
    }


    private fun recognizeLine(crop: Bitmap): Pair<String, Float> {
        val targetH = 48
        val targetW = maxOf(8, (crop.width.toFloat() / crop.height * targetH).toInt()).coerceAtMost(1600)
        val scaled = Bitmap.createScaledBitmap(crop, targetW, targetH, true)
        val pixels = IntArray(targetW * targetH)
        scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        if (scaled !== crop) scaled.recycle()

        val data = FloatArray(3 * targetW * targetH)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            data[i] = (r - 0.5f) / 0.5f
            data[targetW * targetH + i] = (g - 0.5f) / 0.5f
            data[2 * targetW * targetH + i] = (b - 0.5f) / 0.5f
        }
        val input = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(data),
            longArrayOf(1, 3, targetH.toLong(), targetW.toLong())
        )
        return input.use {
            rec!!.run(mapOf("x" to it)).use { res ->
                val tensor = res[0] as OnnxTensor
                val shape = tensor.info.shape // [1, T, C]
                val steps = shape[1].toInt()
                val classes = shape[2].toInt()
                val buf = tensor.floatBuffer
                val flat = FloatArray(buf.remaining()).also { buf.get(it) }
                ctcDecode(flat, steps, classes)
            }
        }
    }

    /** CTC greedy decode + маппинг индексов в символы. */
    fun ctcDecode(flat: FloatArray, steps: Int, classes: Int): Pair<String, Float> {
        val sb = StringBuilder()
        var prev = -1
        var confSum = 0f
        var count = 0
        for (t in 0 until steps) {
            val offset = t * classes
            var best = 0
            var bestP = flat[offset]
            for (c in 1 until classes) {
                val p = flat[offset + c]
                if (p > bestP) { bestP = p; best = c }
            }
            if (best != 0 && best != prev) {
                if (best < chars.size) sb.append(chars[best])
                confSum += bestP
                count++
            }
            prev = best
        }
        return sb.toString() to if (count > 0) confSum / count else 0f
    }

    override fun close() {
        runCatching { det?.close() }
        runCatching { rec?.close() }
        det = null
        rec = null
    }

    companion object {
        /** Во сколько раз увеличиваем узкую страницу перед детекцией. */
        const val DET_UPSCALE = 1.4f

        const val DET_URL =
            "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/onnx/PP-OCRv5/det/ch_PP-OCRv5_det_mobile.onnx"
        const val REC_URL =
            "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/onnx/PP-OCRv5/rec/cyrillic_PP-OCRv5_rec_mobile.onnx"
        const val DICT_URL =
            "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/paddle/PP-OCRv5/rec/cyrillic_PP-OCRv5_rec_mobile/ppocrv5_cyrillic_dict.txt"
    }
}
