package com.example.chatapp.util

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import java.util.regex.Pattern

/**
 * Подсветка синтаксиса для блоков кода в ответах ИИ.
 * Поддерживает: ключевые слова, строки, числа, комментарии, аннотации.
 * Цветовая схема вдохновлена IntelliJ Darcula.
 */
object SyntaxHighlighter {

    // Цвета для различных токенов (IntelliJ Darcula-стиль)
    private val COLOR_KEYWORD = Color.parseColor("#CC7832")
    private val COLOR_STRING = Color.parseColor("#6A8759")
    private val COLOR_NUMBER = Color.parseColor("#6897BB")
    private val COLOR_COMMENT = Color.parseColor("#808080")
    private val COLOR_ANNOTATION = Color.parseColor("#BBB529")

    // Предкомпилированные паттерны (создаются один раз для повышения производительности)
    private val keywordPattern = Pattern.compile(
        "\\b(val|var|fun|if|else|when|class|interface|object|for|while|return|try|catch|" +
        "true|false|null|import|private|public|protected|this|super|let|const|function|" +
        "def|export|default|new|switch|case|break|continue)\\b"
    )
    private val stringPattern = Pattern.compile("\"[^\"]*\"|'[^']*'")
    private val numberPattern = Pattern.compile("\\b\\d+\\b")
    private val commentPattern = Pattern.compile("//.*|/\\*[\\s\\S]*?\\*/")
    private val annotationPattern = Pattern.compile("@[A-Za-z0-9_]+")

    /**
     * Применяет подсветку синтаксиса к строке кода.
     * Порядок важен: комментарии перекрывают всё остальное (применяются последними).
     */
    fun highlight(code: String): SpannableString {
        val spannable = SpannableString(code)

        applyPattern(spannable, keywordPattern, COLOR_KEYWORD)
        applyPattern(spannable, stringPattern, COLOR_STRING)
        applyPattern(spannable, numberPattern, COLOR_NUMBER)
        // Комментарии последними — чтобы они перекрывали подсветку внутри себя
        applyPattern(spannable, commentPattern, COLOR_COMMENT)
        applyPattern(spannable, annotationPattern, COLOR_ANNOTATION)

        return spannable
    }

    private fun applyPattern(spannable: SpannableString, pattern: Pattern, color: Int) {
        val matcher = pattern.matcher(spannable)
        while (matcher.find()) {
            spannable.setSpan(
                ForegroundColorSpan(color),
                matcher.start(), matcher.end(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}
