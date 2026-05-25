package com.example.chatapp.network

/**
 * Нормализует LaTeX-ограничители для совместимости с Markwon LaTeX Plugin.
 * Преобразует \[...\] и \(...\) в $$...$$.
 */
object MathLatexNormalizer {

    fun normalize(text: String): String {
        if (text.isBlank()) return text

        var result = text

        // 1. Заменяем \[ и \] на $$
        result = result.replace("\\[", "$$").replace("\\]", "$$")

        // 2. Заменяем \( и \) на $$
        result = result.replace("\\(", "$$").replace("\\)", "$$")

        // 3. Заменяем одиночные $ ... $ (inline) на $$ ... $$, если они еще не $$
        val singleDollarRegex = Regex("""(?<!\$)\$(?!\$)(.+?)(?<!\$)\$(?!\$)""", RegexOption.DOT_MATCHES_ALL)
        result = singleDollarRegex.replace(result) { match ->
            "$$${match.groupValues[1].trim()}$$"
        }

        return result
    }
}
