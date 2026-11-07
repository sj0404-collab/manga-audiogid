package lv.bibliogid.manga.ocr

import android.content.Context
import lv.bibliogid.manga.core.ocr.Box
import lv.bibliogid.manga.core.ocr.PageText
import lv.bibliogid.manga.core.ocr.RegionType
import lv.bibliogid.manga.core.ocr.Replica
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Кеш РАСПОЗНАННОГО ТЕКСТА (не страниц!).
 * Хранится маленькими JSON-файлами, чтобы повторное открытие главы не гоняло OCR заново.
 */
class TextStore(context: Context) {

    private val dir: File = File(context.filesDir, "ocr_text").apply { mkdirs() }

    private fun fileFor(key: String): File =
        File(dir, key.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(120) + ".json")

    fun load(key: String): PageText? {
        val f = fileFor(key)
        if (!f.exists()) return null
        return runCatching {
            val json = JSONObject(f.readText())
            val arr = json.optJSONArray("replicas") ?: JSONArray()
            val replicas = ArrayList<Replica>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                replicas.add(
                    Replica(
                        index = o.optInt("i", i),
                        text = o.optString("t"),
                        type = runCatching { RegionType.valueOf(o.optString("ty", "UNKNOWN")) }
                            .getOrDefault(RegionType.UNKNOWN),
                        box = Box(
                            o.optDouble("bl", 0.0).toFloat(),
                            o.optDouble("bt", 0.0).toFloat(),
                            o.optDouble("br", 0.0).toFloat(),
                            o.optDouble("bb", 0.0).toFloat()
                        ),
                        needsReview = o.optBoolean("nr", false),
                        bandIndex = o.optInt("b", 0),
                        openEnded = o.optBoolean("oe", false),
                        lines = run {
                            val ln = o.optJSONArray("ln") ?: return@run emptyList()
                            (0 until ln.length()).mapNotNull { k ->
                                val a = ln.optJSONArray(k) ?: return@mapNotNull null
                                Box(
                                    a.optDouble(0).toFloat(),
                                    a.optDouble(1).toFloat(),
                                    a.optDouble(2).toFloat(),
                                    a.optDouble(3).toFloat()
                                )
                            }
                        }
                    )
                )
            }
            PageText(
                replicas = replicas,
                engine = json.optString("engine"),
                elapsedMs = json.optLong("ms"),
                rawLineCount = json.optInt("raw"),
                cleanLineCount = json.optInt("clean"),
                latinLeft = json.optInt("latin"),
                warnings = emptyList(),
                rawText = json.optString("rawText"),
                fromCache = true,
                bandCount = json.optInt("bands", 1),
                pageWidth = json.optInt("pw", 0),
                pageHeight = json.optInt("ph", 0)
            )
        }.getOrNull()
    }

    fun save(key: String, page: PageText, book: String? = null) {
        runCatching {
            val arr = JSONArray()
            page.replicas.forEach { r ->
                arr.put(JSONObject().apply {
                    put("i", r.index)
                    put("t", r.text)
                    put("ty", r.type.name)
                    put("nr", r.needsReview)
                    put("b", r.bandIndex)
                    if (r.openEnded) put("oe", true)
                    if (r.box.area > 0f) {
                        put("bl", r.box.left.toDouble())
                        put("bt", r.box.top.toDouble())
                        put("br", r.box.right.toDouble())
                        put("bb", r.box.bottom.toDouble())
                    }
                    if (r.lines.isNotEmpty()) {
                        val ln = JSONArray()
                        r.lines.forEach { b ->
                            ln.put(JSONArray().put(b.left.toDouble()).put(b.top.toDouble())
                                .put(b.right.toDouble()).put(b.bottom.toDouble()))
                        }
                        put("ln", ln)
                    }
                })
            }
            val json = JSONObject().apply {
                put("engine", page.engine)
                put("ms", page.elapsedMs)
                put("raw", page.rawLineCount)
                put("clean", page.cleanLineCount)
                put("latin", page.latinLeft)
                put("rawText", page.rawText)
                put("bands", page.bandCount)
                put("pw", page.pageWidth)
                put("ph", page.pageHeight)
                put("book", book ?: "")
                put("saved", System.currentTimeMillis())
                put("replicas", arr)
            }
            fileFor(key).writeText(json.toString())
        }
    }

    /** Удалить текст одной страницы (для «Распознать заново»). */
    fun forgetPage(key: String): Boolean = runCatching { fileFor(key).delete() }.getOrDefault(false)

    /** «Сбросить у всех книг». */
    fun clearAll(): Int {
        var removed = 0
        runCatching {
            dir.listFiles()?.forEach { if (it.delete()) removed++ }
        }
        return removed
    }

    /** «Забыть текст этой книги» — удаляем только её файлы. */
    fun forgetBook(book: String): Int {
        if (book.isBlank()) return 0
        var removed = 0
        runCatching {
            dir.listFiles()?.forEach { f ->
                val json = runCatching { JSONObject(f.readText()) }.getOrNull()
                if (json != null && json.optString("book") == book) {
                    if (f.delete()) removed++
                }
            }
        }
        return removed
    }

    fun countForBook(book: String): Int {
        if (book.isBlank()) return 0
        return runCatching {
            dir.listFiles()?.count { f ->
                val json = runCatching { JSONObject(f.readText()) }.getOrNull()
                json != null && json.optString("book") == book
            } ?: 0
        }.getOrDefault(0)
    }
}
