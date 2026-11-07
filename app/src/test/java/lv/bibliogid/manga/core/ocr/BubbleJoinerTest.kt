package lv.bibliogid.manga.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

/** Стык фраз внутри облачка: запятая вместо точки от OCR. */
class BubbleJoinerTest {

    @Test
    fun shortFragmentBecomesComma() {
        assertEquals(
            "Лучше быть, более равнодушным.",
            BubbleJoiner.join(listOf("Лучше быть.", "Более равнодушным."))
        )
    }

    @Test
    fun continuationWordBecomesComma() {
        assertEquals(
            "Да, это именно то, что вы должны делать.",
            BubbleJoiner.join(listOf("Да, это именно то.", "Что вы должны делать."))
        )
        assertEquals(
            "Уже пора начинать встречу, так что её высочество прибыла.",
            BubbleJoiner.join(listOf("Уже пора начинать встречу.", "Так что её высочество прибыла."))
        )
    }

    @Test
    fun realSentenceBreakStays() {
        assertEquals(
            "Вы должны быть равнодушны, как и всегда. Притворитесь, что слушаете.",
            BubbleJoiner.join(
                listOf(
                    "Вы должны быть равнодушны, как и всегда.",
                    "Притворитесь, что слушаете."
                )
            )
        )
    }

    @Test
    fun parentheticalGetsComma() {
        assertEquals(
            "Уже пора начинать встречу, так что её высочество, должно быть, прибыла.",
            BubbleJoiner.join(
                listOf(
                    "Уже пора начинать встречу, так что её высочество.",
                    "Должно быть.",
                    "Прибыла."
                )
            )
        )
    }

    @Test
    fun singlePartUntouched() {
        assertEquals("Прочь.", BubbleJoiner.join(listOf("Прочь.")))
    }
}
