package com.example.chatapp.ui.chat

data class ChatRenameAnimationPlan(
    val commonPrefix: String,
    val deleteSteps: List<String>,
    val typeSteps: List<String>,
    val finalText: String
)

object ChatRenameAnimationPlanner {
    fun plan(oldTitle: String, newTitle: String): ChatRenameAnimationPlan {
        if (oldTitle == newTitle) {
            return ChatRenameAnimationPlan(
                commonPrefix = oldTitle,
                deleteSteps = emptyList(),
                typeSteps = emptyList(),
                finalText = newTitle
            )
        }

        val commonCodePoints = commonPrefixCodePoints(oldTitle, newTitle)
        val oldCodePoints = oldTitle.codePointCountSafe()
        val newCodePoints = newTitle.codePointCountSafe()
        val commonPrefix = oldTitle.prefixByCodePoints(commonCodePoints)

        val deleteSteps = (oldCodePoints - 1 downTo commonCodePoints)
            .map { oldTitle.prefixByCodePoints(it) }

        val typeSteps = (commonCodePoints + 1..newCodePoints)
            .map { newTitle.prefixByCodePoints(it) }

        return ChatRenameAnimationPlan(
            commonPrefix = commonPrefix,
            deleteSteps = deleteSteps,
            typeSteps = typeSteps,
            finalText = newTitle
        )
    }

    private fun commonPrefixCodePoints(left: String, right: String): Int {
        val max = minOf(left.codePointCountSafe(), right.codePointCountSafe())
        var index = 0
        while (index < max && left.codePointAtIndex(index) == right.codePointAtIndex(index)) {
            index += 1
        }
        return index
    }

    private fun String.codePointCountSafe(): Int = codePointCount(0, length)

    private fun String.prefixByCodePoints(codePointCount: Int): String {
        if (codePointCount <= 0) return ""
        val safeCount = codePointCount.coerceAtMost(codePointCountSafe())
        return substring(0, offsetByCodePoints(0, safeCount))
    }

    private fun String.codePointAtIndex(codePointIndex: Int): Int {
        val charIndex = offsetByCodePoints(0, codePointIndex)
        return codePointAt(charIndex)
    }
}
