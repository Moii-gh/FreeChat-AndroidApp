package com.example.chatapp.ui.markdown

import kotlin.math.max

object StreamingMarkdownParser {
    fun parse(
        previous: StreamingMarkdownState,
        rawText: String,
        isFinal: Boolean
    ): StreamingMarkdownState {
        val normalized = rawText.replace("\r\n", "\n").replace('\r', '\n')
        val appendOnly = normalized.startsWith(previous.rawText) && previous.chunks.isNotEmpty()
        val frozenChunks = if (appendOnly && !isFinal) {
            previous.chunks.filter { it.isFrozen && it.endOffset <= previous.frozenPrefixLength }
        } else {
            emptyList()
        }

        val tailStart = if (frozenChunks.isNotEmpty()) {
            frozenChunks.maxOf { it.endOffset }.coerceIn(0, normalized.length)
        } else {
            0
        }

        val parsedTail = parseRange(
            text = normalized,
            start = tailStart,
            end = normalized.length,
            isFinal = isFinal,
            ordinalOffset = frozenChunks.size
        )
        val combined = freezeStableChunks(frozenChunks + parsedTail, normalized.length, isFinal)
        val frozenPrefixLength = combined
            .asSequence()
            .takeWhile { it.isFrozen }
            .lastOrNull()
            ?.endOffset
            ?: 0

        return StreamingMarkdownState(
            rawText = normalized,
            chunks = combined,
            frozenPrefixLength = frozenPrefixLength
        )
    }

    private fun parseRange(
        text: String,
        start: Int,
        end: Int,
        isFinal: Boolean,
        ordinalOffset: Int
    ): List<MarkdownChunk> {
        if (start >= end) return emptyList()

        val chunks = mutableListOf<MarkdownChunk>()
        val lineStarts = lineStarts(text, start, end)
        var lineIndex = 0
        var textStart = start
        var ordinal = ordinalOffset

        fun flushText(upTo: Int) {
            if (upTo <= textStart) return
            splitTextBlocks(text, textStart, upTo).forEach { range ->
                val blockEnd = range.last + 1
                val content = text.substring(range.first, blockEnd)
                if (content.isNotBlank()) {
                    chunks += createChunk(
                        type = ChunkType.Text,
                        content = content,
                        text = text,
                        startOffset = range.first,
                        endOffset = blockEnd,
                        ordinal = ordinal++,
                        isFrozen = isFinal,
                        links = StreamingLinkExtractor.extractLinks(content)
                    )
                }
            }
            textStart = upTo
        }

        while (lineIndex < lineStarts.size) {
            val lineStart = lineStarts[lineIndex]
            val lineEnd = lineEnd(text, lineStart, end)
            val lineWithBreakEnd = lineWithBreakEnd(text, lineEnd, end)
            val line = text.substring(lineStart, lineEnd)
            val trimmed = line.trimStart()

            if (trimmed.startsWith("```")) {
                flushText(lineStart)
                val language = trimmed.removePrefix("```").trim()
                val codeStart = lineWithBreakEnd
                var closingLineIndex = -1
                var closingEnd = end
                var searchIndex = lineIndex + 1
                while (searchIndex < lineStarts.size) {
                    val candidateStart = lineStarts[searchIndex]
                    val candidateEnd = lineEnd(text, candidateStart, end)
                    val candidate = text.substring(candidateStart, candidateEnd).trimStart()
                    if (candidate.startsWith("```")) {
                        closingLineIndex = searchIndex
                        closingEnd = lineWithBreakEnd(text, candidateEnd, end)
                        break
                    }
                    searchIndex++
                }

                val codeEnd = if (closingLineIndex >= 0) {
                    lineStarts[closingLineIndex].let { if (it > codeStart && text[it - 1] == '\n') it - 1 else it }
                } else {
                    end
                }.coerceAtLeast(codeStart)
                val content = text.substring(codeStart, codeEnd)
                chunks += createChunk(
                    type = ChunkType.CodeFence,
                    content = content,
                    text = text,
                    startOffset = lineStart,
                    endOffset = closingEnd,
                    ordinal = ordinal++,
                    isFrozen = closingLineIndex >= 0 || isFinal,
                    language = language
                )
                textStart = closingEnd
                lineIndex = if (closingLineIndex >= 0) closingLineIndex + 1 else lineStarts.size
                continue
            }

            if (looksLikeTableLine(line)) {
                val tableStartLine = lineIndex
                val tableStart = lineStart
                var tableEndLine = lineIndex
                var tableEnd = lineWithBreakEnd
                while (tableEndLine + 1 < lineStarts.size) {
                    val nextStart = lineStarts[tableEndLine + 1]
                    val nextEnd = lineEnd(text, nextStart, end)
                    if (!looksLikeTableLine(text.substring(nextStart, nextEnd))) break
                    tableEndLine++
                    tableEnd = lineWithBreakEnd(text, nextEnd, end)
                }

                val lines = (tableStartLine..tableEndLine).map { idx ->
                    val candidateStart = lineStarts[idx]
                    val candidateEnd = lineEnd(text, candidateStart, end)
                    text.substring(candidateStart, candidateEnd)
                }
                if (isValidTable(lines)) {
                    flushText(tableStart)
                    val rawTable = text.substring(tableStart, tableEnd).trimEnd('\n')
                    chunks += createChunk(
                        type = ChunkType.Table,
                        content = rawTable,
                        text = text,
                        startOffset = tableStart,
                        endOffset = tableEnd,
                        ordinal = ordinal++,
                        isFrozen = tableEnd < end || isFinal
                    )
                    textStart = tableEnd
                    lineIndex = tableEndLine + 1
                    continue
                }
            }

            lineIndex++
        }

        flushText(end)
        return chunks
    }

    private fun freezeStableChunks(
        chunks: List<MarkdownChunk>,
        fullLength: Int,
        isFinal: Boolean
    ): List<MarkdownChunk> {
        if (chunks.isEmpty()) return chunks
        if (isFinal) return chunks.map { it.copy(isFrozen = true) }

        val lastIndex = chunks.lastIndex
        return chunks.mapIndexed { index, chunk ->
            val hasStableBoundary = chunk.endOffset < fullLength && endsAtStableBoundary(chunk)
            chunk.copy(isFrozen = index < lastIndex && hasStableBoundary)
        }
    }

    private fun endsAtStableBoundary(chunk: MarkdownChunk): Boolean =
        when (chunk.type) {
            ChunkType.CodeFence, ChunkType.Table, ChunkType.Image, ChunkType.Sources -> true
            ChunkType.Text -> chunk.content.endsWith("\n\n") || chunk.content.endsWith("\n")
        }

    private fun createChunk(
        type: ChunkType,
        content: String,
        text: String,
        startOffset: Int,
        endOffset: Int,
        ordinal: Int,
        isFrozen: Boolean,
        links: List<MarkdownLinkRange> = emptyList(),
        language: String = ""
    ): MarkdownChunk {
        val prefixHash = rollingHash(text, 0, startOffset)
        val id = "${ordinal}-${type.name.lowercase()}-$startOffset-$prefixHash"
        return MarkdownChunk(
            id = id,
            type = type,
            content = content,
            isFrozen = isFrozen,
            links = links.take(MAX_LINKS_PER_CHUNK),
            startOffset = startOffset,
            endOffset = endOffset,
            language = language
        )
    }

    private fun lineStarts(text: String, start: Int, end: Int): List<Int> {
        val result = mutableListOf(start)
        var index = start
        while (index < end) {
            if (text[index] == '\n' && index + 1 < end) {
                result += index + 1
            }
            index++
        }
        return result
    }

    private fun lineEnd(text: String, start: Int, maxEnd: Int): Int {
        var index = start
        while (index < maxEnd && text[index] != '\n') {
            index++
        }
        return index
    }

    private fun lineWithBreakEnd(text: String, lineEnd: Int, maxEnd: Int): Int =
        if (lineEnd < maxEnd && text[lineEnd] == '\n') lineEnd + 1 else lineEnd

    private fun splitTextBlocks(text: String, start: Int, end: Int): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var blockStart = start
        var index = start
        while (index < end - 1) {
            if (text[index] == '\n' && text[index + 1] == '\n') {
                val blockEnd = index + 2
                if (blockEnd > blockStart) result += blockStart until blockEnd
                blockStart = blockEnd
                index = blockEnd
                continue
            }
            index++
        }
        if (end > blockStart) {
            result += blockStart until end
        }
        return result
    }

    private fun looksLikeTableLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length > 2
    }

    private fun isValidTable(lines: List<String>): Boolean {
        if (lines.size < 3) return false
        val separatorIndex = lines.indexOfFirst { line ->
            val cells = parseTableCells(line)
            cells.isNotEmpty() && cells.all { cell ->
                val trimmed = cell.trim()
                trimmed.isNotEmpty() && trimmed.all { it == '-' || it == ':' || it == ' ' }
            }
        }
        return separatorIndex == 1 && lines.drop(separatorIndex + 1).isNotEmpty()
    }

    private fun parseTableCells(line: String): List<String> {
        val trimmed = line.trim()
        if (!trimmed.startsWith("|") || !trimmed.endsWith("|")) return emptyList()
        return trimmed.removePrefix("|").removeSuffix("|").split("|")
    }

    private fun rollingHash(text: String, start: Int, end: Int): Int {
        var hash = 0
        for (index in start until max(start, end)) {
            hash = (hash * 31) + text[index].code
        }
        return hash
    }

    private const val MAX_LINKS_PER_CHUNK = 128
}
