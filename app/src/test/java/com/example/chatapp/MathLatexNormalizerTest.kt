package com.example.chatapp

import com.example.chatapp.network.MathLatexNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class MathLatexNormalizerTest {

    @Test
    fun `plain text is unchanged`() {
        val text = "Привет, это обычный текст без формул."
        assertEquals(text, MathLatexNormalizer.normalize(text))
    }

    @Test
    fun `inline formula with backslash parens is converted to double dollars`() {
        val input = "Формула: \\( \\frac{a}{b} \\)"
        val expected = "Формула: $$ \\frac{a}{b} $$"
        assertEquals(expected, MathLatexNormalizer.normalize(input))
    }

    @Test
    fun `block formula with backslash brackets is converted to double dollars`() {
        val input = "Результат:\n\\[ x^{2} + 1 \\]"
        val expected = "Результат:\n$$ x^{2} + 1 $$"
        assertEquals(expected, MathLatexNormalizer.normalize(input))
    }

    @Test
    fun `dollar sign inline formula is converted to double dollars`() {
        val input = "The answer is \$x^{2} + 1\$"
        val expected = "The answer is \$\$x^{2} + 1\$\$"
        assertEquals(expected, MathLatexNormalizer.normalize(input))
    }

    @Test
    fun `double dollar block formula remains double dollar`() {
        val input = "Formula:\n\$\$\\frac{a}{b}\$\$"
        assertEquals(input, MathLatexNormalizer.normalize(input))
    }

    @Test
    fun `blank text returns blank`() {
        assertEquals("", MathLatexNormalizer.normalize(""))
        assertEquals("  ", MathLatexNormalizer.normalize("  "))
    }
}
