package com.example.chatapp.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import com.example.chatapp.LocaleHelper
import com.example.chatapp.R
import com.example.chatapp.util.FileUtils
import com.example.chatapp.util.dpToPx

/**
 * Парсит и рендерит markdown-таблицы в красивые UI-карточки.
 *
 * Таблица считается валидной если в тексте есть:
 *  1. Строка заголовков (| A | B | ...)
 *  2. Строка-разделитель (| --- | --- |)
 *  3. Хотя бы одна строка данных
 */
object MarkdownTableRenderer {

    // ─────────── Данные ───────────

    data class ParsedTable(
        val headers: List<String>,
        val rows: List<List<String>>
    )

    /**
     * Разбивает произвольный текст на чанки трёх типов:
     * TEXT, CODE (между ```), TABLE (валидные md-таблицы).
     * Гарантирует, что обычный текст и код не затрагиваются.
     */
    sealed class Chunk {
        data class Text(val content: String) : Chunk()
        data class Code(val language: String, val content: String) : Chunk()
        data class Table(val parsed: ParsedTable, val raw: String) : Chunk()
    }

    fun splitIntoChunks(text: String): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        val normalized = text.replace("\r\n", "\n")
        val lines = normalized.split("\n")

        var i = 0
        var inCode = false
        var codeLang = ""
        val codeLines = mutableListOf<String>()
        val textLines = mutableListOf<String>()
        val tableLines = mutableListOf<String>()

        fun flushText() {
            val content = textLines.joinToString("\n").trim()
            if (content.isNotEmpty()) chunks.add(Chunk.Text(content))
            textLines.clear()
        }

        fun flushCode() {
            val content = codeLines.joinToString("\n")
            chunks.add(Chunk.Code(codeLang, content))
            codeLines.clear()
            codeLang = ""
        }

        fun flushTable() {
            if (tableLines.size >= 3) {
                val parsed = parseTableLines(tableLines)
                if (parsed != null) {
                    val raw = tableLines.joinToString("\n")
                    chunks.add(Chunk.Table(parsed, raw))
                    tableLines.clear()
                    return
                }
            }
            // Не валидная таблица — сбрасываем как текст
            textLines.addAll(tableLines)
            tableLines.clear()
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            if (inCode) {
                if (trimmed.startsWith("```")) {
                    inCode = false
                    flushCode()
                } else {
                    codeLines.add(line)
                }
                i++
                continue
            }

            if (trimmed.startsWith("```")) {
                // Закрываем накопленные таблицы и текст перед кодом
                if (tableLines.isNotEmpty()) flushTable()
                flushText()
                inCode = true
                codeLang = trimmed.removePrefix("```").trim()
                i++
                continue
            }

            val looksLikeTable = trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length > 2

            if (looksLikeTable) {
                // Завершаем накопленный текст перед таблицей
                if (textLines.isNotEmpty()) flushText()
                tableLines.add(line)
            } else {
                // Завершаем накопленную таблицу перед текстом
                if (tableLines.isNotEmpty()) flushTable()
                textLines.add(line)
            }

            i++
        }

        // Сброс того, что осталось
        if (inCode) {
            // Незакрытый блок кода — трактуем как обычный текст
            textLines.add("```$codeLang")
            textLines.addAll(codeLines)
            flushText()
        } else {
            if (tableLines.isNotEmpty()) flushTable()
            flushText()
        }

        return chunks
    }

    /**
     * Парсит строки markdown-таблицы.
     * Возвращает null, если формат не соответствует требованиям:
     * - строка заголовков
     * - строка-разделитель (ячейки состоят из -/:/пробелов)
     * - минимум одна строка данных
     */
    fun parseTableLines(lines: List<String>): ParsedTable? {
        if (lines.size < 3) return null

        // Ищем строку-разделитель (вторая непустая строка или явно типа |---|)
        var headerLine: String? = null
        var separatorIdx = -1

        for (idx in lines.indices) {
            val trimmed = lines[idx].trim()
            if (!trimmed.startsWith("|")) continue
            if (headerLine == null) {
                headerLine = trimmed
            } else {
                // Проверяем что это разделитель
                if (isSeparatorLine(trimmed)) {
                    separatorIdx = idx
                    break
                } else {
                    // Вторая строка не разделитель — не таблица
                    return null
                }
            }
        }

        if (headerLine == null || separatorIdx < 0) return null

        val dataLines = lines.subList(separatorIdx + 1, lines.size)
            .filter { it.trim().startsWith("|") && it.trim().endsWith("|") }

        if (dataLines.isEmpty()) return null

        val headers = parseCells(headerLine)
        if (headers.isEmpty()) return null

        val rows = dataLines.map { parseCells(it) }

        return ParsedTable(headers = headers, rows = rows)
    }

    private fun isSeparatorLine(line: String): Boolean {
        val cells = parseCells(line)
        if (cells.isEmpty()) return false
        return cells.all { cell ->
            val t = cell.trim()
            t.isNotEmpty() && t.all { ch -> ch == '-' || ch == ':' || ch == ' ' }
        }
    }

    private fun parseCells(line: String): List<String> {
        val trimmed = line.trim()
        if (!trimmed.startsWith("|") || !trimmed.endsWith("|")) return emptyList()
        return trimmed.removePrefix("|").removeSuffix("|")
            .split("|")
            .map { it.trim() }
    }

    // ─────────── Отрисовка таблицы ───────────

    /**
     * Создаёт полный UI-блок для одной таблицы:
     * тёмная карточка + горизонтальный скролл + кнопки.
     */
    fun createTableView(context: Context, table: ParsedTable, rawMarkdown: String): View {
        val density = context.resources.displayMetrics.density

        // Внешняя карточка
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_table_card)
            val pad = (4 * density).toInt()
            setPadding(0, 0, 0, pad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                val m = (8 * density).toInt()
                setMargins(0, m, 0, m)
            }
        }

        // Горизонтальный скролл для широких таблиц
        val hScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Таблица внутри горизонтального скролла.
        val tableLayout = buildTableLayout(context, table, density)
        hScroll.addView(tableLayout)
        card.addView(hScroll)

        // Кнопки под таблицей
        val btnRow = buildButtonRow(context, table, rawMarkdown, density)
        card.addView(btnRow)

        return card
    }

    private fun buildTableLayout(
        context: Context,
        table: ParsedTable,
        density: Float
    ): TableLayout {
        val colCount = table.headers.size
        val cellPadH = (14 * density).toInt()
        val cellPadV = (12 * density).toInt()
        val minColWidth = (80 * density).toInt()

        val tableLayout = TableLayout(context).apply {
            layoutParams = TableLayout.LayoutParams(
                TableLayout.LayoutParams.WRAP_CONTENT,
                TableLayout.LayoutParams.WRAP_CONTENT
            )
            isStretchAllColumns = false
        }

        // ── Строка заголовков ──
        val headerRow = TableRow(context).apply {
            setBackgroundColor(Color.parseColor("#2C2C2E"))
        }
        table.headers.forEachIndexed { colIdx, header ->
            val tv = TextView(context).apply {
                text = header.ifEmpty { " " }
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setPadding(cellPadH, cellPadV, cellPadH, cellPadV)
                minWidth = minColWidth
                layoutParams = TableRow.LayoutParams(
                    TableRow.LayoutParams.WRAP_CONTENT,
                    TableRow.LayoutParams.WRAP_CONTENT
                )
                // Правая граница между колонками (кроме последней)
                if (colIdx < colCount - 1) {
                    background = columnDividerDrawable(
                        fillColor = Color.parseColor("#2C2C2E"),
                        dividerColor = Color.parseColor("#3A3A3C"),
                        density = density
                    )
                }
            }
            headerRow.addView(tv)
        }
        tableLayout.addView(headerRow)

        // ── Строки данных ──
        table.rows.forEachIndexed { rowIdx, cells ->
            // Горизонтальный разделитель
            val divider = View(context).apply {
                setBackgroundColor(Color.parseColor("#3A3A3C"))
                layoutParams = TableLayout.LayoutParams(
                    TableLayout.LayoutParams.MATCH_PARENT,
                    (1 * density).toInt().coerceAtLeast(1)
                )
            }
            tableLayout.addView(divider)

            val dataRow = TableRow(context).apply {
                // Чередование оттенков строк (опционально)
                setBackgroundColor(Color.TRANSPARENT)
            }

            val paddedCells = padCells(cells, colCount)
            paddedCells.forEachIndexed { colIdx, cellText ->
                val isFirstCol = colIdx == 0
                val tv = TextView(context).apply {
                    text = cellText.ifEmpty { " " }
                    setTextColor(if (isFirstCol) Color.WHITE else Color.parseColor("#E5E5EA"))
                    textSize = 14f
                    if (isFirstCol) setTypeface(null, Typeface.BOLD)
                    setPadding(cellPadH, cellPadV, cellPadH, cellPadV)
                    minWidth = minColWidth
                    layoutParams = TableRow.LayoutParams(
                        TableRow.LayoutParams.WRAP_CONTENT,
                        TableRow.LayoutParams.WRAP_CONTENT
                    )
                    // Вертикальный разделитель между колонками
                    if (colIdx < colCount - 1) {
                        background = columnDividerDrawable(
                            fillColor = Color.TRANSPARENT,
                            dividerColor = Color.parseColor("#2C2C2E"),
                            density = density
                        )
                    }
                }
                dataRow.addView(tv)
            }
            tableLayout.addView(dataRow)
        }

        return tableLayout
    }

    /** Фон ячейки с правой границей-разделителем */
    private fun columnDividerDrawable(
        fillColor: Int,
        dividerColor: Int,
        density: Float
    ): GradientDrawable {
        // Оставляем цельный фон: разделитель уже реализован фоном строки заголовка.
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
        }
    }

    private fun buildButtonRow(
        context: Context,
        table: ParsedTable,
        rawMarkdown: String,
        density: Float
    ): LinearLayout {
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                val m = (8 * density).toInt()
                setMargins(m, (4 * density).toInt(), m, (4 * density).toInt())
            }
        }

        val copyBtn = buildSmallButton(
            context = context,
            label = LocaleHelper.getString(context, "code_copy"),
            iconRes = R.drawable.ic_copy,
            density = density
        ) {
            FileUtils.copyToClipboard(context, rawMarkdown)
            Toast.makeText(context, LocaleHelper.getString(context, "toast_table_copied"), Toast.LENGTH_SHORT).show()
        }

        val xmlBtn = buildSmallButton(
            context = context,
            label = LocaleHelper.getString(context, "table_export_xml_label"),
            iconRes = R.drawable.ic_download_simple,
            density = density
        ) {
            exportTableAsXml(context, table)
        }

        btnRow.addView(copyBtn)
        btnRow.addView(
            xmlBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (8 * density).toInt()
            }
        )

        return btnRow
    }

    private fun buildSmallButton(
        context: Context,
        label: String,
        iconRes: Int,
        density: Float,
        onClick: () -> Unit
    ): LinearLayout {
        val btn = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = (10 * density).toInt()
            val padV = (6 * density).toInt()
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#2C2C2E"))
                cornerRadius = 999f
                setStroke((1 * density).toInt(), Color.parseColor("#3A3A3C"))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(Color.parseColor("#CCCCCC"))
            layoutParams = LinearLayout.LayoutParams(
                (13 * density).toInt(),
                (13 * density).toInt()
            ).apply {
                marginEnd = (5 * density).toInt()
            }
        }

        val tv = TextView(context).apply {
            text = label
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 12f
        }

        btn.addView(icon)
        btn.addView(tv)
        return btn
    }

    // ─────────── XML экспорт ───────────

    private fun exportTableAsXml(context: Context, table: ParsedTable) {
        val xmlContent = buildXmlContent(table)
        val fileName = "table_export_${System.currentTimeMillis()}.xml"

        val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, fileName, xmlContent)
        } else {
            shareXmlFallback(context, fileName, xmlContent)
            true // Запасной вариант считается успешным, если открыт системный диалог.
        }

        if (success) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Toast.makeText(
                    context,
                    LocaleHelper.formatString(context, "toast_xml_saved_with_name", fileName),
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            // Если MediaStore не сработал, пробуем системный intent шаринга.
            shareXmlFallback(context, fileName, xmlContent)
        }
    }

    /**
     * Android 10+ (API 29+): сохранение через MediaStore.Downloads.
     * Не требует разрешений WRITE_EXTERNAL_STORAGE.
     */
    private fun saveViaMediaStore(context: Context, fileName: String, content: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/xml")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            }.getOrElse { false }
        } else {
            false
        }
    }

    /**
     * Fallback: share-intent с текстом XML.
     * Работает на Android 9 и ниже, либо если MediaStore недоступен.
     */
    private fun shareXmlFallback(context: Context, fileName: String, content: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, content)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
        }
        val chooser = Intent.createChooser(intent, "Поделиться XML")
        if (context !is android.app.Activity) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
    }

    /**
     * Строит XML-строку из таблицы.
     * Правила нормализации XML-тегов:
     *  - транслитерация кириллицы
     *  - пробелы → подчёркивание
     *  - удаление недопустимых символов
     *  - если начинается с цифры или пустая → col_N
     */
    fun buildXmlContent(table: ParsedTable): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("<table>")

        val tagNames = table.headers.mapIndexed { idx, h ->
            normalizeXmlTag(h, idx + 1)
        }

        table.rows.forEach { cells ->
            sb.appendLine("  <row>")
            val padded = padCells(cells, table.headers.size)
            padded.forEachIndexed { i, cell ->
                val tag = tagNames[i]
                val escaped = escapeXml(cell)
                sb.appendLine("    <$tag>$escaped</$tag>")
            }
            sb.appendLine("  </row>")
        }

        sb.append("</table>")
        return sb.toString()
    }

    /**
     * Нормализует заголовок колонки в допустимый XML-тег.
     * Запасной вариант: col_N (где N — порядковый номер от 1).
     */
    private fun normalizeXmlTag(header: String, colNumber: Int): String {
        // Транслит кириллицы
        var result = transliterateCyrillic(header)
        // Заменяем пробелы и дефисы на подчёркивание
        result = result.replace(Regex("[\\s\\-]+"), "_")
        // Убираем всё, кроме букв, цифр и подчёркивания
        result = result.replace(Regex("[^a-zA-Z0-9_]"), "")
        // Убираем ведущие цифры и подчёркивания
        result = result.trimStart('_').trimStart { it.isDigit() }.trimStart('_')

        return if (result.isBlank()) "col_$colNumber" else result
    }

    private fun transliterateCyrillic(input: String): String {
        val map = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d",
            'е' to "e", 'ё' to "yo", 'ж' to "zh", 'з' to "z", 'и' to "i",
            'й' to "j", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
            'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
            'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch",
            'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "",
            'э' to "e", 'ю' to "yu", 'я' to "ya",
            'А' to "A", 'Б' to "B", 'В' to "V", 'Г' to "G", 'Д' to "D",
            'Е' to "E", 'Ё' to "Yo", 'Ж' to "Zh", 'З' to "Z", 'И' to "I",
            'Й' to "J", 'К' to "K", 'Л' to "L", 'М' to "M", 'Н' to "N",
            'О' to "O", 'П' to "P", 'Р' to "R", 'С' to "S", 'Т' to "T",
            'У' to "U", 'Ф' to "F", 'Х' to "Kh", 'Ц' to "Ts", 'Ч' to "Ch",
            'Ш' to "Sh", 'Щ' to "Sch", 'Ъ' to "", 'Ы' to "Y", 'Ь' to "",
            'Э' to "E", 'Ю' to "Yu", 'Я' to "Ya"
        )
        return input.map { ch -> map[ch] ?: ch.toString() }.joinToString("")
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    // ─────────── Утилиты ───────────

    /** Дополняет список ячеек до нужного числа столбцов */
    private fun padCells(cells: List<String>, colCount: Int): List<String> {
        return when {
            cells.size >= colCount -> cells.take(colCount)
            else -> cells + List(colCount - cells.size) { "" }
        }
    }
}
