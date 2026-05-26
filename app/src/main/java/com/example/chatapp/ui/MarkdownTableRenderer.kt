package com.example.chatapp.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
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
            background = ContextCompat.getDrawable(context, R.drawable.bg_table_header)
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
                SelectableTextSupport.configure(this)
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
                    SelectableTextSupport.configure(this)
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

        val xlsxBtn = buildSmallButton(
            context = context,
            label = LocaleHelper.getString(context, "table_export_xlsx_label"),
            iconRes = R.drawable.ic_download_simple,
            density = density
        ) {
            exportTableAsXlsx(context, table)
        }

        btnRow.addView(copyBtn)
        btnRow.addView(
            xlsxBtn,
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

    // ─────────── XLSX экспорт ───────────

    private fun exportTableAsXlsx(context: Context, table: ParsedTable) {
        val fileName = "table_export_${System.currentTimeMillis()}.xlsx"

        val savedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveXlsxViaMediaStore(context, fileName, table)
        } else {
            saveXlsxToDownloadsLegacy(context, fileName, table)
        }

        if (savedUri != null) {
            Toast.makeText(
                context,
                LocaleHelper.formatString(context, "toast_xlsx_saved_with_name", fileName),
                Toast.LENGTH_LONG
            ).show()
            openFileInExplorer(context, savedUri, fileName)
        } else {
            Toast.makeText(
                context,
                LocaleHelper.getString(context, "toast_error"),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Android 10+ (API 29+): сохранение через MediaStore.Downloads.
     * Возвращает URI сохранённого файла или null при ошибке.
     */
    private fun saveXlsxViaMediaStore(context: Context, fileName: String, table: ParsedTable): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                writeXlsxBytes(table, out)
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        }.getOrNull()
    }

    /**
     * Fallback для Android 9 и ниже: пишем в публичную папку Downloads.
     */
    @Suppress("DEPRECATION")
    private fun saveXlsxToDownloadsLegacy(context: Context, fileName: String, table: ParsedTable): Uri? {
        return runCatching {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val file = java.io.File(dir, fileName)
            file.outputStream().use { out ->
                writeXlsxBytes(table, out)
            }
            Uri.fromFile(file)
        }.getOrNull()
    }

    /**
     * Открывает файл в проводнике / файловом менеджере.
     * Сначала пробует открыть конкретный файл через ACTION_VIEW.
     * Если не получается — открывает папку Downloads.
     */
    private fun openFileInExplorer(context: Context, uri: Uri, fileName: String) {
        runCatching {
            // Пробуем открыть конкретный файл
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(viewIntent)
        }.onFailure {
            // Если не удалось открыть файл напрямую — открываем папку Downloads
            runCatching {
                val downloadsIntent = Intent(Intent.ACTION_VIEW).apply {
                    val downloadsUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    } else {
                        Uri.fromFile(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
                    }
                    setDataAndType(downloadsUri, "resource/folder")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(downloadsIntent)
            }.onFailure {
                // Последний вариант — открыть проводник через Document UI
                runCatching {
                    val browseIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browseIntent)
                }
            }
        }
    }

    // ─────────── XLSX генерация ───────────

    /**
     * Записывает полный XLSX файл в выходной поток.
     * XLSX — это ZIP-архив с Open XML Spreadsheet.
     */
    private fun writeXlsxBytes(table: ParsedTable, output: java.io.OutputStream) {
        val zip = ZipOutputStream(output)

        // Собираем все строки (shared strings)
        val allStrings = mutableListOf<String>()
        table.headers.forEach { allStrings.add(it) }
        table.rows.forEach { row ->
            padCells(row, table.headers.size).forEach { allStrings.add(it) }
        }
        val stringIndex = mutableMapOf<String, Int>()
        allStrings.forEachIndexed { idx, s ->
            if (s !in stringIndex) stringIndex[s] = stringIndex.size
        }
        val uniqueStrings = stringIndex.entries.sortedBy { it.value }.map { it.key }

        // [Content_Types].xml
        zip.putNextEntry(ZipEntry("[Content_Types].xml"))
        zip.write(xlsxContentTypes().toByteArray(Charsets.UTF_8))
        zip.closeEntry()

        // _rels/.rels
        zip.putNextEntry(ZipEntry("_rels/.rels"))
        zip.write(xlsxRels().toByteArray(Charsets.UTF_8))
        zip.closeEntry()

        // xl/_rels/workbook.xml.rels
        zip.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
        zip.write(xlsxWorkbookRels().toByteArray(Charsets.UTF_8))
        zip.closeEntry()

        // xl/workbook.xml
        zip.putNextEntry(ZipEntry("xl/workbook.xml"))
        zip.write(xlsxWorkbook().toByteArray(Charsets.UTF_8))
        zip.closeEntry()

        // xl/styles.xml (жирный заголовок)
        zip.putNextEntry(ZipEntry("xl/styles.xml"))
        zip.write(xlsxStyles().toByteArray(Charsets.UTF_8))
        zip.closeEntry()

        // xl/sharedStrings.xml
        zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
        zip.write(xlsxSharedStrings(uniqueStrings).toByteArray(Charsets.UTF_8))
        zip.closeEntry()

        // xl/worksheets/sheet1.xml
        zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
        zip.write(xlsxSheet(table, stringIndex).toByteArray(Charsets.UTF_8))
        zip.closeEntry()

        zip.finish()
        zip.flush()
    }

    private fun xlsxContentTypes(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

    private fun xlsxRels(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private fun xlsxWorkbookRels(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private fun xlsxWorkbook(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="Table" sheetId="1" r:id="rId1"/>
  </sheets>
</workbook>"""

    private fun xlsxStyles(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="2">
    <font><sz val="11"/><name val="Calibri"/></font>
    <font><b/><sz val="11"/><name val="Calibri"/></font>
  </fonts>
  <fills count="2">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
  </fills>
  <borders count="1">
    <border><left/><right/><top/><bottom/><diagonal/></border>
  </borders>
  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
  <cellXfs count="2">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
    <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
  </cellXfs>
</styleSheet>"""

    private fun xlsxSharedStrings(strings: List<String>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${strings.size}" uniqueCount="${strings.size}">""")
        strings.forEach { s ->
            sb.append("<si><t>")
            sb.append(escapeXml(s))
            sb.append("</t></si>")
        }
        sb.append("</sst>")
        return sb.toString()
    }

    private fun xlsxSheet(table: ParsedTable, stringIndex: Map<String, Int>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        sb.append("<sheetData>")

        // Строка заголовков (row 1, style=1 = bold)
        sb.append("""<row r="1">""")
        table.headers.forEachIndexed { colIdx, header ->
            val colRef = columnLetter(colIdx) + "1"
            val sIdx = stringIndex[header] ?: 0
            sb.append("""<c r="$colRef" t="s" s="1"><v>$sIdx</v></c>""")
        }
        sb.append("</row>")

        // Строки данных
        table.rows.forEachIndexed { rowIdx, cells ->
            val rowNum = rowIdx + 2
            sb.append("""<row r="$rowNum">""")
            val padded = padCells(cells, table.headers.size)
            padded.forEachIndexed { colIdx, cell ->
                val colRef = columnLetter(colIdx) + rowNum
                val sIdx = stringIndex[cell] ?: 0
                sb.append("""<c r="$colRef" t="s"><v>$sIdx</v></c>""")
            }
            sb.append("</row>")
        }

        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    /** Преобразует индекс колонки (0-based) в букву Excel: 0→A, 1→B, ..., 25→Z, 26→AA */
    private fun columnLetter(index: Int): String {
        var result = ""
        var n = index
        while (true) {
            result = ('A' + n % 26) + result
            n = n / 26 - 1
            if (n < 0) break
        }
        return result
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
