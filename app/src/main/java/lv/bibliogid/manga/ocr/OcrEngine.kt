package lv.bibliogid.manga.ocr

import android.graphics.Bitmap
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import lv.bibliogid.manga.core.ocr.Box
import lv.bibliogid.manga.core.ocr.OcrLine
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/** Любой движок распознавания: локальный или облачный. */
interface OcrEngine {
    val id: String
    val label: String
    val isOnline: Boolean
    suspend fun recognize(bitmap: Bitmap): List<OcrLine>
    fun close() {}
}

/**
 * Локальный движок #1 — ML Kit (на устройстве, ~30-90 мс на страницу, bundled-модель).
 * Tesseract не используется и не может быть подключён: его нет ни в зависимостях, ни в коде.
 */
class MlKitOcrEngine : OcrEngine {
    override val id = "mlkit"
    override val label = "ML Kit · на устройстве"
    override val isOnline = false

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(bitmap: Bitmap): List<OcrLine> = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val lines = ArrayList<OcrLine>()
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val b = line.boundingBox ?: continue
                        lines.add(
                            OcrLine(
                                text = line.text,
                                box = Box(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat()),
                                confidence = 1f
                            )
                        )
                    }
                }
                cont.resume(lines)
            }
            .addOnFailureListener { cont.resume(emptyList()) }
    }

    override fun close() {
        runCatching { recognizer.close() }
    }
}

/** База для облачных движков: JPEG-байты + JSON. */
abstract class RemoteOcrEngine : OcrEngine {
    override val isOnline = true

    protected fun jpeg(bitmap: Bitmap, quality: Int = 90): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    protected fun postJson(url: String, body: JSONObject, headers: Map<String, String>): JSONObject? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) null else JSONObject(text)
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    protected fun postForm(url: String, fields: Map<String, String>, headers: Map<String, String> = emptyMap()): JSONObject? {
        val boundary = "----biblio" + System.nanoTime()
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { os ->
                for ((k, v) in fields) {
                    os.write("--$boundary\r\n".toByteArray())
                    os.write("Content-Disposition: form-data; name=\"$k\"\r\n\r\n".toByteArray())
                    os.write(v.toByteArray())
                    os.write("\r\n".toByteArray())
                }
                os.write("--$boundary--\r\n".toByteArray())
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) null else JSONObject(text)
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}

/** Yandex Vision OCR (кириллица — родная, лучшее качество на русском). */
class YandexOcrEngine(
    private val apiKey: String,
    private val folderId: String
) : RemoteOcrEngine() {
    override val id = "yandex"
    override val label = "Yandex Vision OCR · облако"

    override suspend fun recognize(bitmap: Bitmap): List<OcrLine> {
        val body = JSONObject().apply {
            put("mimeType", "image/jpeg")
            put("languageCodes", JSONArray(listOf("ru", "en")))
            put("model", "page")
            put("content", Base64.encodeToString(jpeg(bitmap), Base64.NO_WRAP))
        }
        val resp = postJson(
            "https://ocr.api.cloud.yandex.net/ocr/v1/recognizeText",
            body,
            mapOf(
                "Authorization" to "Api-Key $apiKey",
                "x-folder-id" to folderId,
                "x-data-logging-enabled" to "true"
            )
        ) ?: return emptyList()

        val out = ArrayList<OcrLine>()
        val blocks = resp.optJSONObject("result")?.optJSONObject("textAnnotation")?.optJSONArray("blocks")
            ?: return emptyList()
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            val lines = block.optJSONArray("lines") ?: continue
            for (j in 0 until lines.length()) {
                val line = lines.optJSONObject(j) ?: continue
                val text = line.optString("text")
                val poly = line.optJSONObject("boundingBox")?.optJSONArray("vertices") ?: continue
                val xs = ArrayList<Float>()
                val ys = ArrayList<Float>()
                for (k in 0 until poly.length()) {
                    val v = poly.optJSONObject(k) ?: continue
                    xs.add(v.optDouble("x", 0.0).toFloat())
                    ys.add(v.optDouble("y", 0.0).toFloat())
                }
                if (xs.isEmpty()) continue
                out.add(OcrLine(text, Box(xs.min(), ys.min(), xs.max(), ys.max())))
            }
        }
        return out
    }
}

/** Google Cloud Vision (DOCUMENT_TEXT_DETECTION). */
class GoogleVisionOcrEngine(private val apiKey: String) : RemoteOcrEngine() {
    override val id = "google"
    override val label = "Google Cloud Vision · облако"

    override suspend fun recognize(bitmap: Bitmap): List<OcrLine> {
        val body = JSONObject().apply {
            put("requests", JSONArray().apply {
                put(JSONObject().apply {
                    put("image", JSONObject().put("content", Base64.encodeToString(jpeg(bitmap), Base64.NO_WRAP)))
                    put("features", JSONArray().apply {
                        put(JSONObject().put("type", "TEXT_DETECTION"))
                    })
                    put("imageContext", JSONObject().put("languageHints", JSONArray(listOf("ru"))))
                })
            })
        }
        val resp = postJson("https://vision.googleapis.com/v1/images:annotate?key=$apiKey", body, emptyMap())
            ?: return emptyList()
        val out = ArrayList<OcrLine>()
        val blocks = resp.optJSONArray("responses")?.optJSONObject(0)
            ?.optJSONObject("fullTextAnnotation")?.optJSONArray("pages")
            ?.optJSONObject(0)?.optJSONArray("blocks") ?: return emptyList()
        for (i in 0 until blocks.length()) {
            val paragraphs = blocks.optJSONObject(i)?.optJSONArray("paragraphs") ?: continue
            for (p in 0 until paragraphs.length()) {
                val lines = paragraphs.optJSONObject(p)?.optJSONArray("words") ?: continue
                // собираем слова в строку по вертикали
                val words = ArrayList<Pair<String, Box>>()
                for (w in 0 until lines.length()) {
                    val word = lines.optJSONObject(w) ?: continue
                    val text = word.optJSONArray("symbols")?.let { syms ->
                        (0 until syms.length()).joinToString("") { syms.optJSONObject(it)?.optString("text").orEmpty() }
                    } ?: continue
                    val v = word.optJSONObject("boundingBox")?.optJSONArray("vertices") ?: continue
                    val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
                    for (k in 0 until v.length()) {
                        v.optJSONObject(k)?.let {
                            xs.add(it.optDouble("x", 0.0).toFloat()); ys.add(it.optDouble("y", 0.0).toFloat())
                        }
                    }
                    if (xs.isNotEmpty()) words.add(text to Box(xs.min(), ys.min(), xs.max(), ys.max()))
                }
                words.groupBy { (it.second.centerY / 24f).toInt() }.toSortedMap().forEach { (_, group) ->
                    val text = group.joinToString(" ") { it.first }
                    val box = group.map { it.second }.reduce { a, b -> a.union(b) }
                    out.add(OcrLine(text, box))
                }
            }
        }
        return out
    }
}

/** OCR.space (бесплатный ключ, быстрый старт без биллинга). */
class OcrSpaceEngine(private val apiKey: String) : RemoteOcrEngine() {
    override val id = "ocrspace"
    override val label = "OCR.space · облако"

    override suspend fun recognize(bitmap: Bitmap): List<OcrLine> {
        val b64 = Base64.encodeToString(jpeg(bitmap), Base64.NO_WRAP)
        val resp = postForm(
            "https://api.ocr.space/parse/image",
            mapOf(
                "apikey" to apiKey,
                "language" to "rus",
                "OCREngine" to "2",
                "isOverlayRequired" to "true",
                "base64Image" to "data:image/jpeg;base64,$b64"
            )
        ) ?: return emptyList()
        val out = ArrayList<OcrLine>()
        val results = resp.optJSONArray("ParsedResults") ?: return emptyList()
        for (i in 0 until results.length()) {
            val overlay = results.optJSONObject(i)?.optJSONObject("TextOverlay") ?: continue
            val lines = overlay.optJSONArray("Lines") ?: continue
            for (j in 0 until lines.length()) {
                val line = lines.optJSONObject(j) ?: continue
                val text = line.optString("LineText")
                val minTop = line.optInt("MinTop", 0).toFloat()
                val maxH = line.optInt("MaxHeight", 12).toFloat()
                var left = Float.MAX_VALUE
                var right = 0f
                val words = line.optJSONArray("Words")
                if (words != null) {
                    for (k in 0 until words.length()) {
                        val w = words.optJSONObject(k) ?: continue
                        left = minOf(left, w.optInt("Left", 0).toFloat())
                        right = maxOf(right, (w.optInt("Left", 0) + w.optInt("Width", 0)).toFloat())
                    }
                }
                if (left == Float.MAX_VALUE) { left = 0f; right = bitmap.width.toFloat() }
                out.add(OcrLine(text, Box(left, minTop, right, minTop + maxH)))
            }
        }
        return out
    }
}
