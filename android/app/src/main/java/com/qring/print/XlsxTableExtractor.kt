package com.qring.print

import android.content.Context
import android.net.Uri
import android.util.Xml
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser

/**
 * Excel (.xlsx) 结构化表格提取（2026-08-27 加，变量数据批量打印用）。
 *
 * 与 [XlsxTextExtractor]（展平行文本）不同，本类保留**行列结构**：
 * 首行 = 列名，后续行 = 记录（列名→值 Map）。
 * 零依赖（Android 自带 XmlPullParser + java.util.zip）。
 *
 * 简化：只取第一个工作表；合并单元格/公式/列宽不处理；共享字符串 + 内联字符串 + 数字直取。
 * 单元格坐标 r="A1" 解析列索引，保证跳列/空列正确占位。
 */
object XlsxTableExtractor {

    /** 解析结果：列名 + 记录 */
    data class Table(val columns: List<String>, val rows: List<Map<String, String>>)

    private const val S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

    /**
     * 提取第一个工作表为结构化表格。@throws Exception 解析失败（损坏/非 xlsx/空表）
     */
    fun extract(context: Context, uri: Uri): Table {
        val (_, rawRows) = readWorkbook(context, uri)
        if (rawRows.isEmpty()) throw IllegalStateException("Excel 工作表为空")
        // 首行 = 列名；空白列名跳过（不产生"列N"占位，绑定模板只出现真实列名）
        val header = rawRows[0]
        val colIdxToName = LinkedHashMap<Int, String>()
        header.forEachIndexed { i, v ->
            if (v.isNotBlank()) colIdxToName[i] = v.trim()
        }
        val columns = colIdxToName.values.toList()
        val colIdxs = colIdxToName.keys.toList()
        if (columns.isEmpty()) throw IllegalStateException("Excel 表头无有效列名")
        val rows = ArrayList<Map<String, String>>()
        for (i in 1 until rawRows.size) {
            val cells = rawRows[i]
            val row = LinkedHashMap<String, String>()
            for (k in colIdxs.indices) {
                row[columns[k]] = cells.getOrNull(colIdxs[k])?.trim() ?: ""
            }
            if (row.values.any { it.isNotEmpty() }) rows.add(row)
        }
        return Table(columns, rows)
    }

    /** 读字符串表 + 第一个工作表（zip 流顺序读，需重新打开） */
    private fun readWorkbook(context: Context, uri: Uri): Pair<List<String>, List<List<String>>> {
        val shared = ArrayList<String>()
        context.contentResolver.openInputStream(uri)?.use { fileIn ->
            ZipInputStream(fileIn.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "xl/sharedStrings.xml") {
                        readSharedStrings(zip, shared)
                        break
                    }
                    entry = zip.nextEntry
                }
            }
        }
        val rows = ArrayList<List<String>>()
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法打开 Excel 文件")
        input.use { fileIn ->
            ZipInputStream(fileIn.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "xl/worksheets/sheet1.xml") {
                        readSheet(zip, shared, rows)
                        return Pair(shared, rows)
                    }
                    entry = zip.nextEntry
                }
            }
        }
        throw IllegalStateException("不是有效的 Excel (.xlsx) 文件")
    }

    private fun readSharedStrings(input: java.io.InputStream, out: MutableList<String>) {
        val parser = Xml.newPullParser()
        parser.setFeature("http://xmlpull.org/v1/doc/features.html#process-namespaces", false)
        parser.setInput(input, "UTF-8")
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "t") {
                out.add(parser.nextText())
            }
            event = parser.next()
        }
    }

    /** 读第一个工作表：逐行解析单元格，按列坐标 A/B/C 定位，返回 行→List<单元格值> */
    private fun readSheet(input: java.io.InputStream, shared: List<String>, out: MutableList<List<String>>) {
        val parser = Xml.newPullParser()
        parser.setFeature("http://xmlpull.org/v1/doc/features.html#process-namespaces", false)
        parser.setInput(input, "UTF-8")
        val cells = LinkedHashMap<Int, String>()
        var cellType: String? = null
        var cellRef: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "row" -> cells.clear()
                        "c" -> {
                            cellType = parser.getAttributeValue(null, "t")
                            cellRef = parser.getAttributeValue(null, "r")   // 如 "B3"
                        }
                        "v" -> {
                            val v = parser.nextText()
                            val col = cellRef?.let(::colIndex) ?: cells.size
                            cells[col] = if (cellType == "s") {
                                v.toIntOrNull()?.let { idx -> shared.getOrNull(idx) } ?: ""
                            } else {
                                v
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "row") {
                        if (cells.isNotEmpty()) {
                            val width = cells.keys.maxOrNull()?.plus(1) ?: 0
                            val rowArr = ArrayList<String>(width)
                            repeat(width) { rowArr.add("") }
                            cells.forEach { (col, v) -> if (col in rowArr.indices) rowArr[col] = v }
                            out.add(rowArr)
                        }
                    }
                }
            }
            event = parser.next()
        }
    }

    /** 列坐标 "B3" → 1（A=0） */
    private fun colIndex(ref: String): Int {
        var idx = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') idx = idx * 26 + (ch - 'A' + 1)
            else break
        }
        return idx - 1
    }
}
