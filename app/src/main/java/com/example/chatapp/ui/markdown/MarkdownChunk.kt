package com.example.chatapp.ui.markdown

data class MarkdownChunk(
    val id: String,
    val type: ChunkType,
    val content: String,
    val isFrozen: Boolean,
    val links: List<MarkdownLinkRange> = emptyList(),
    val startOffset: Int = 0,
    val endOffset: Int = startOffset + content.length,
    val language: String = ""
)

enum class ChunkType {
    Text,
    CodeFence,
    Table,
    Image,
    Sources
}

data class MarkdownLinkRange(
    val start: Int,
    val end: Int,
    val url: String,
    val label: String
)

data class StreamingMarkdownState(
    val rawText: String = "",
    val chunks: List<MarkdownChunk> = emptyList(),
    val frozenPrefixLength: Int = 0
) {
    companion object {
        val Empty = StreamingMarkdownState()
    }
}

data class MarkdownChunkDiff(
    val unchanged: List<MarkdownChunk> = emptyList(),
    val changed: List<MarkdownChunk> = emptyList(),
    val inserted: List<MarkdownChunk> = emptyList(),
    val removed: List<MarkdownChunk> = emptyList()
) {
    companion object {
        fun calculate(oldChunks: List<MarkdownChunk>, newChunks: List<MarkdownChunk>): MarkdownChunkDiff {
            val oldById = oldChunks.associateBy { it.id }
            val newById = newChunks.associateBy { it.id }

            val unchanged = mutableListOf<MarkdownChunk>()
            val changed = mutableListOf<MarkdownChunk>()
            val inserted = mutableListOf<MarkdownChunk>()
            val removed = oldChunks.filter { it.id !in newById }

            newChunks.forEach { newChunk ->
                val oldChunk = oldById[newChunk.id]
                when {
                    oldChunk == null -> inserted += newChunk
                    oldChunk.type != newChunk.type ||
                        oldChunk.content != newChunk.content ||
                        oldChunk.language != newChunk.language ||
                        oldChunk.isFrozen != newChunk.isFrozen -> changed += newChunk
                    else -> unchanged += newChunk
                }
            }

            return MarkdownChunkDiff(
                unchanged = unchanged,
                changed = changed,
                inserted = inserted,
                removed = removed
            )
        }
    }
}
