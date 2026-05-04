package com.example.chatapp.util

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import java.util.regex.Pattern

/**
 * Легкая подсветка синтаксиса для блоков кода ассистента.
 * Для неизвестных языков текст остается читаемым без попыток угадать формат.
 */
object SyntaxHighlighter {

    private val COLOR_KEYWORD = Color.parseColor("#C792EA")
    private val COLOR_STRING = Color.parseColor("#C3E88D")
    private val COLOR_NUMBER = Color.parseColor("#F78C6C")
    private val COLOR_COMMENT = Color.parseColor("#697098")
    private val COLOR_FUNCTION = Color.parseColor("#82AAFF")
    private val COLOR_CLASS = Color.parseColor("#FFCB6B")
    private val COLOR_VARIABLE = Color.parseColor("#EEFFFF")
    private val COLOR_TAG = Color.parseColor("#F07178")
    private val COLOR_ATTRIBUTE = Color.parseColor("#FFCB6B")
    private val COLOR_OPERATOR = Color.parseColor("#89DDFF")

    private val cLikeKeywords = setOf(
        "abstract", "as", "async", "await", "break", "case", "catch", "class", "const",
        "constructor", "continue", "data", "default", "do", "else", "enum", "export",
        "extends", "false", "finally", "for", "fun", "function", "if", "import", "in",
        "inline", "instanceof", "interface", "is", "let", "new", "null", "object",
        "override", "package", "private", "protected", "public", "return", "sealed",
        "static", "super", "switch", "this", "throw", "true", "try", "type", "typeof",
        "val", "var", "void", "when", "while"
    )

    private val pythonKeywords = setOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue", "def",
        "del", "elif", "else", "except", "False", "finally", "for", "from", "global",
        "if", "import", "in", "is", "lambda", "None", "nonlocal", "not", "or", "pass",
        "raise", "return", "True", "try", "while", "with", "yield"
    )

    private val sqlKeywords = setOf(
        "add", "alter", "and", "as", "asc", "between", "by", "case", "create", "delete",
        "desc", "distinct", "drop", "else", "end", "from", "group", "having", "in",
        "insert", "into", "is", "join", "left", "like", "limit", "not", "null", "on",
        "or", "order", "outer", "right", "select", "set", "table", "then", "union",
        "update", "values", "when", "where"
    )

    private val bashKeywords = setOf(
        "case", "do", "done", "elif", "else", "esac", "fi", "for", "function", "if",
        "in", "local", "select", "then", "until", "while"
    )

    private val stringPattern = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`")
    private val tripleStringPattern = Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''")
    private val cLikeCommentPattern = Pattern.compile("//.*|/\\*[\\s\\S]*?\\*/")
    private val hashCommentPattern = Pattern.compile("(?m)#.*$")
    private val sqlCommentPattern = Pattern.compile("--.*|/\\*[\\s\\S]*?\\*/")
    private val htmlCommentPattern = Pattern.compile("<!--[\\s\\S]*?-->")
    private val numberPattern = Pattern.compile("\\b(?:0x[0-9A-Fa-f]+|\\d+(?:\\.\\d+)?)\\b")
    private val functionPattern = Pattern.compile("\\b([A-Za-z_$][\\w$]*)\\s*(?=\\()")
    private val classPattern = Pattern.compile("\\b(?:class|interface|enum|object|data\\s+class|sealed\\s+class|def)\\s+([A-Za-z_$][\\w$]*)")
    private val variablePattern = Pattern.compile("\\b(?:val|var|let|const|final)\\s+([A-Za-z_$][\\w$]*)")
    private val pythonVariablePattern = Pattern.compile("\\b([A-Za-z_][\\w]*)\\s*(?=[:=])")
    private val htmlTagPattern = Pattern.compile("</?\\s*([A-Za-z][\\w:-]*)")
    private val htmlAttributePattern = Pattern.compile("\\s([A-Za-z_:][\\w:.-]*)(?=\\s*=)")
    private val operatorPattern = Pattern.compile("[{}()\\[\\].,;:+\\-*/%=!<>|&?]+")

    fun highlight(code: String, language: String? = null): SpannableString {
        val normalizedLanguage = normalizeLanguage(language)
        val spannable = SpannableString(code)

        when (normalizedLanguage) {
            "kotlin", "java", "javascript", "typescript" -> highlightCLike(spannable)
            "python" -> highlightPython(spannable)
            "html", "xml" -> highlightMarkup(spannable)
            "css" -> highlightCss(spannable)
            "json" -> highlightJson(spannable)
            "sql" -> highlightSql(spannable)
            "bash" -> highlightBash(spannable)
            else -> return spannable
        }

        return spannable
    }

    private fun highlightCLike(spannable: SpannableString) {
        applyKeywordSet(spannable, cLikeKeywords, COLOR_KEYWORD)
        applyPatternGroup(spannable, classPattern, 1, COLOR_CLASS)
        applyPatternGroup(spannable, functionPattern, 1, COLOR_FUNCTION)
        applyPatternGroup(spannable, variablePattern, 1, COLOR_VARIABLE)
        applyPattern(spannable, operatorPattern, COLOR_OPERATOR)
        applyPattern(spannable, numberPattern, COLOR_NUMBER)
        applyPattern(spannable, stringPattern, COLOR_STRING)
        applyPattern(spannable, cLikeCommentPattern, COLOR_COMMENT)
    }

    private fun highlightPython(spannable: SpannableString) {
        applyKeywordSet(spannable, pythonKeywords, COLOR_KEYWORD, ignoreCase = false)
        applyPatternGroup(spannable, Pattern.compile("\\bdef\\s+([A-Za-z_][\\w]*)"), 1, COLOR_FUNCTION)
        applyPatternGroup(spannable, Pattern.compile("\\bclass\\s+([A-Za-z_][\\w]*)"), 1, COLOR_CLASS)
        applyPatternGroup(spannable, pythonVariablePattern, 1, COLOR_VARIABLE)
        applyPattern(spannable, operatorPattern, COLOR_OPERATOR)
        applyPattern(spannable, numberPattern, COLOR_NUMBER)
        applyPattern(spannable, stringPattern, COLOR_STRING)
        applyPattern(spannable, tripleStringPattern, COLOR_STRING)
        applyPattern(spannable, hashCommentPattern, COLOR_COMMENT)
    }

    private fun highlightMarkup(spannable: SpannableString) {
        applyPatternGroup(spannable, htmlTagPattern, 1, COLOR_TAG)
        applyPatternGroup(spannable, htmlAttributePattern, 1, COLOR_ATTRIBUTE)
        applyPattern(spannable, operatorPattern, COLOR_OPERATOR)
        applyPattern(spannable, stringPattern, COLOR_STRING)
        applyPattern(spannable, htmlCommentPattern, COLOR_COMMENT)
    }

    private fun highlightCss(spannable: SpannableString) {
        applyPattern(spannable, Pattern.compile("(?m)(^[.#]?[A-Za-z][\\w.#: \\-]*)(?=\\s*\\{)"), COLOR_CLASS)
        applyPatternGroup(spannable, Pattern.compile("([A-Za-z-]+)\\s*:"), 1, COLOR_ATTRIBUTE)
        applyPattern(spannable, operatorPattern, COLOR_OPERATOR)
        applyPattern(spannable, numberPattern, COLOR_NUMBER)
        applyPattern(spannable, stringPattern, COLOR_STRING)
        applyPattern(spannable, cLikeCommentPattern, COLOR_COMMENT)
    }

    private fun highlightJson(spannable: SpannableString) {
        applyPatternGroup(spannable, Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"\\s*:"), 0, COLOR_ATTRIBUTE)
        applyKeywordSet(spannable, setOf("true", "false", "null"), COLOR_KEYWORD)
        applyPattern(spannable, operatorPattern, COLOR_OPERATOR)
        applyPattern(spannable, numberPattern, COLOR_NUMBER)
        applyPattern(spannable, stringPattern, COLOR_STRING)
        applyPatternGroup(spannable, Pattern.compile("(\"(?:\\\\.|[^\"\\\\])*\")\\s*:"), 1, COLOR_ATTRIBUTE)
    }

    private fun highlightSql(spannable: SpannableString) {
        applyKeywordSet(spannable, sqlKeywords, COLOR_KEYWORD)
        applyPattern(spannable, operatorPattern, COLOR_OPERATOR)
        applyPattern(spannable, numberPattern, COLOR_NUMBER)
        applyPattern(spannable, stringPattern, COLOR_STRING)
        applyPattern(spannable, sqlCommentPattern, COLOR_COMMENT)
    }

    private fun highlightBash(spannable: SpannableString) {
        applyKeywordSet(spannable, bashKeywords, COLOR_KEYWORD)
        applyPattern(spannable, Pattern.compile("\\$[A-Za-z_][\\w]*|\\$\\{[^}]+}"), COLOR_VARIABLE)
        applyPatternGroup(spannable, Pattern.compile("(^|[;&|]\\s*)([A-Za-z_./-][\\w./-]*)"), 2, COLOR_FUNCTION)
        applyPattern(spannable, operatorPattern, COLOR_OPERATOR)
        applyPattern(spannable, numberPattern, COLOR_NUMBER)
        applyPattern(spannable, stringPattern, COLOR_STRING)
        applyPattern(spannable, hashCommentPattern, COLOR_COMMENT)
    }

    private fun normalizeLanguage(language: String?): String {
        return when (language.orEmpty().trim().lowercase()) {
            "kt", "kts", "kotlin" -> "kotlin"
            "java" -> "java"
            "js", "jsx", "javascript" -> "javascript"
            "ts", "tsx", "typescript" -> "typescript"
            "py", "python" -> "python"
            "html", "xhtml" -> "html"
            "css", "scss", "sass" -> "css"
            "xml", "svg", "xaml" -> "xml"
            "json", "jsonc" -> "json"
            "sql" -> "sql"
            "sh", "shell", "bash", "zsh", "powershell", "ps1" -> "bash"
            else -> ""
        }
    }

    private fun applyKeywordSet(
        spannable: SpannableString,
        keywords: Set<String>,
        color: Int,
        ignoreCase: Boolean = true
    ) {
        val flags = if (ignoreCase) Pattern.CASE_INSENSITIVE else 0
        val pattern = Pattern.compile("\\b(${keywords.joinToString("|") { Pattern.quote(it) }})\\b", flags)
        applyPattern(spannable, pattern, color)
    }

    private fun applyPattern(spannable: SpannableString, pattern: Pattern, color: Int) {
        val matcher = pattern.matcher(spannable)
        while (matcher.find()) {
            spannable.setSpan(
                ForegroundColorSpan(color),
                matcher.start(),
                matcher.end(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun applyPatternGroup(
        spannable: SpannableString,
        pattern: Pattern,
        group: Int,
        color: Int
    ) {
        val matcher = pattern.matcher(spannable)
        while (matcher.find()) {
            val start = matcher.start(group)
            val end = matcher.end(group)
            if (start >= 0 && end > start) {
                spannable.setSpan(
                    ForegroundColorSpan(color),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }
}
