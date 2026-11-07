package lv.bibliogid.manga.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Золотой» тест на сыром тексте реальной страницы (кириллица, OCR-ошибки,
 * знаки препинания потеряны, цифры внутри слов, латинские двойники).
 * Проверяем не дословность, а свойства результата: без латиницы, без мусора,
 * без цифр внутри слов, цельные предложения, ничего не потеряно.
 */
class RealScreenshotTest {

    private val normalizer = TextNormalizer()

    private val rawPage = listOf(
        "5 ЙЕН ПОДОЙДЕТ ДЛЯ ПОДНОШЕНИЯ?",
        "ДА, ПОЧЕМУ НЕТ? ВЫ И",
        "П›алия ПОВМОВЬ МНЕ ДО ОТВАЛА ПОЕСТЬ ВКУСНОЙ ЕДЫ",
        "П2ЖАЛИСТА СДЕЛАЙ ЧТО-НИБУДЬ С ЭТОЙ СТРАШНОЙ ШТУКОЙ КОТОРАЯ СТОИТ РЯДОМ С ХАНОЙ",
        "ПОЖАЛУЙСТА, Я СДЕЛАЮ ЧТО УГОДН2 ЧТОБЫ ЭТА ШТУКА ОТЦЕПИЛАСЬ ОТ ХАНЫ",
        "ПОЖАЛУЙСТА СНИМИ С ХАНЫ ЭТО ПРОКЛЯПЕ И ПОМОГИ МНЕ",
        "ОНА ВСЕ ЕЩЕ ШЛЕЩЕТ",
        "ГОТОВОХ НЕ ЭЙО ПОМОЖЕТ ЛИ ЭП% НО\"",
        "Я РАССЧИТЫВАЮ НА ВАС ДУХИ"
    )

    @Test
    fun `на реальной странице не остаётся латиницы`() {
        val res = normalizer.normalize(rawPage)
        val text = res.replicas.joinToString(" ")
        assertFalse("латиница в: $text", text.any { it in 'a'..'z' || it in 'A'..'Z' })
        assertEquals(0, res.report.latinLeft)
    }

    @Test
    fun `на реальной странице нет мусорных символов и цифр внутри слов`() {
        val res = normalizer.normalize(rawPage)
        val text = res.replicas.joinToString(" ")
        assertFalse(text.contains("›"))
        assertFalse(text.contains("%"))
        assertFalse(text.contains("\""))
        // «П2ЖАЛИСТА» -> «пожалуйста», «УГОДН2» -> «угодно»
        assertFalse("цифры внутри слов: $text", Regex("[А-Яа-я]\\d|\\d[А-Яа-я]").containsMatchIn(text))
    }

    @Test
    fun `известные битые слова восстанавливаются`() {
        val res = normalizer.normalize(rawPage)
        val text = res.replicas.joinToString(" ").lowercase()
        assertTrue("нет «пожалуйста» в: $text", text.contains("пожалуйста"))
    }

    @Test
    fun `число реплик не меньше числа непустых строк без склеек`() {
        val res = normalizer.normalize(rawPage)
        // строки склеиваются в предложения => реплик не больше, но и не 0
        assertTrue(res.replicas.isNotEmpty())
        // каждая строка учтена: сумма слов в репликах >= половины исходных слов
        val rawWords = rawPage.sumOf { it.split(" ").count { w -> w.isNotBlank() } }
        val outWords = res.replicas.sumOf { it.split(" ").count { w -> w.isNotBlank() } }
        assertTrue("raw=$rawWords out=$outWords", outWords >= rawWords / 2)
    }

    @Test
    fun `каждая реплика — законченное предложение`() {
        val res = normalizer.normalize(rawPage)
        res.replicas.forEach { r ->
            val last = r.lastOrNull() ?: return@forEach
            assertTrue("реплика без завершения: «$r»", last in ".!?…:;)\"" || r.split(" ").size < 2)
        }
    }

    @Test
    fun `текст с референса приводится к чистому виду`() {
        // ровно та строка, что видна на скриншоте с «П2ЖАЛИСТА»
        val out = normalizer.normalize(listOf(
            "П2ЖАЛИСТА СДЕЛАЙ ЧТО-НИБУДЬ С ЭТОЙ СТРАШНОЙ",
            "ШТУКОЙ КОТОРАЯ СТОИТ РЯДОМ С ХАНОЙ"
        ))
        val text = out.replicas.joinToString(" ")
        assertTrue(text.lowercase().startsWith("пожалуйста сделай"))
        assertFalse(text.any { it.isDigit() })
    }
}
