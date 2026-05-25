package com.example.chatapp

import com.example.chatapp.network.MathRequestDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MathRequestDetectorTest {

    @Test
    fun `solve equation is detected`() {
        assertTrue(MathRequestDetector.isMathQuery("Реши уравнение x + 5 = 10"))
    }

    @Test
    fun `find derivative is detected`() {
        assertTrue(MathRequestDetector.isMathQuery("Найди производную sin(x)"))
    }

    @Test
    fun `calculate percentage is detected`() {
        assertTrue(MathRequestDetector.isMathQuery("Посчитай проценты от 500"))
    }

    @Test
    fun `average grade is detected`() {
        assertTrue(MathRequestDetector.isMathQuery("Какая средняя оценка?"))
    }

    @Test
    fun `show formula is detected`() {
        assertTrue(MathRequestDetector.isMathQuery("Покажи формулу площади круга"))
    }

    @Test
    fun `solve integral is detected`() {
        assertTrue(MathRequestDetector.isMathQuery("Найди интеграл от x^2 dx"))
    }

    @Test
    fun `arithmetic expression is detected`() {
        assertTrue(MathRequestDetector.isMathQuery("сколько будет 15 + 27"))
    }

    @Test
    fun `equation pattern is detected`() {
        assertTrue(MathRequestDetector.isMathQuery("x + 5 = 10"))
    }

    @Test
    fun `english solve is detected`() {
        assertTrue(MathRequestDetector.isMathQuery("Solve the equation 2x + 3 = 7"))
    }

    @Test
    fun `english calculate is detected`() {
        assertTrue(MathRequestDetector.isMathQuery("Calculate the average of 10 and 5"))
    }

    @Test
    fun `weather question is not math`() {
        assertFalse(MathRequestDetector.isMathQuery("Какая погода сегодня?"))
    }

    @Test
    fun `greeting is not math`() {
        assertFalse(MathRequestDetector.isMathQuery("Привет, как дела?"))
    }

    @Test
    fun `recipe question is not math`() {
        assertFalse(MathRequestDetector.isMathQuery("Как приготовить борщ?"))
    }

    @Test
    fun `blank message is not math`() {
        assertFalse(MathRequestDetector.isMathQuery(""))
    }

    @Test
    fun `simplify expression is detected`() {
        assertTrue(MathRequestDetector.isMathQuery("Упрости выражение 3x + 2x"))
    }

    @Test
    fun `fraction question is detected`() {
        assertTrue(MathRequestDetector.isMathQuery("Как складывать дроби?"))
    }

    @Test
    fun `area calculation is detected`() {
        assertTrue(MathRequestDetector.isMathQuery("Найди площадь треугольника"))
    }
}
