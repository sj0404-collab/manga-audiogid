package lv.bibliogid.manga.core.text

import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Ровный регистр и запятые вместо точек по журналам 1.6.0–1.6.2. */
class CaseFixTest {

    companion object {
        @BeforeClass @JvmStatic
        fun load() {
            WordFrequencies.loadFromFile(File("src/main/assets/ru_freq.txt"))
        }
    }

    private val n = TextNormalizer(joinMode = JoinMode.REGION)

    // как в приложении: нормализатор -> фразы -> склейка облачка с восстановлением запятых
    private fun one(vararg lines: String): String =
        lv.bibliogid.manga.core.ocr.BubbleJoiner.join(n.normalize(lines.toList()).replicas)

    @Test
    fun mixedCapsBlockBecomesEven() {
        assertEquals(
            "В будущем, когда вы будете говорить с её высочеством…",
            one("В БУДУЩЕМ,", "КОГДА ВЫ БУДЕ-", "ТЕ ГОВОРИТЬ С ЕЁ", "ВЫСОЧЕСТВОМ...")
        )
    }

    @Test
    fun shortCapsAnswerBecomesEven() {
        assertEquals("Не уверен.", one("не УВЕРЕН,"))
    }

    @Test
    fun shoutBlockBecomesSentenceCase() {
        assertEquals(
            "Уже пора начинать встречу, так что её высочество, должно быть, прибыла.",
            one("УЖЕ ПОРА НА-", "ЧИНАТЬ ВСТРЕЧУ.", "ТАК ЧТО ЕЁ ВЫСО-", "ЧЕСТВО, ДОЛЖНО", "БЫТЬ, ПРИБЫЛА.")
        )
    }

    @Test
    fun mixedCapsWordsInSentenceBecomeLowercase() {
        assertEquals("Лучше быть, более равнодушным.", one("ЛУЧШЕ БЫТЬ.", "БОЛЕЕ РАВНОДУШНЫМ."))
        assertEquals(
            "Да, это именно то, что вы должны делать.",
            one("ДА, ЭТО", "ИМЕННО ТО.", "ЧТО ВЫ ДОЛЖ-", "НЫ ДЕЛАТЬ.")
        )
        assertEquals("Не стоит, сейчас я здесь.", one("НЕ СТОИТ,", "СЕЙЧАС Я ЗДЕСЬ."))
    }

    @Test
    fun capitalInsideSentenceFromOcrIsLowered() {
        assertEquals("Не уверен.", one("Не УВЕРЕН,"))
        assertEquals("В будущем, когда вы будете говорить с её высочеством…",
            one("В БУДУЩЕМ,", "КОГДА ВЫ БУДЕ-", "ТЕ ГОВОРИТЬ С ЕЁ", "ВЫСОЧЕСТВОМ..."))
    }

    @Test
    fun keepAsIsPolicyStillRespected() {
        val keep = TextNormalizer(casePolicy = CasePolicy.KEEP_AS_IS, joinMode = JoinMode.REGION)
        assertEquals("Не УВЕРЕН.", keep.normalize(listOf("Не УВЕРЕН,")).replicas.joinToString(""))
    }
}
