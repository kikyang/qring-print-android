package com.qring.print

import java.io.InputStream
import java.io.InputStreamReader

/**
 * CSV 表格解析（2026-08-27 加，变量数据批量打印用）。
 *
 * 纯 Kotlin 零依赖，返回结构化表格：首行 = 列名，后续行 = 记录。
 * 支持：
 * - 引号包裹的字段（含内嵌逗号/换行/双引号转义 `""`）
 * - 逗号分隔（默认）；`;` 分隔自动探测（首行分隔符计数）
 * - UTF-8（默认）与 GBK（BOM/非法 UTF-8 时回退）编码探测
 * - 空行跳过
 */
object CsvTableParser {

    /** 解析结果：列名 + 记录（每行为 列名→值 的 Map） */
    data class Table(val columns: List<String>, val rows: List<Map<String, String>>)

    /**
     * 解析 CSV。@throws Exception 空输入 / 损坏。
     * @param headerRow 是否把首行作为列名（true=默认；false=用 C1/C2/... 命名）
     */
    fun parse(input: InputStream, headerRow: Boolean = true): Table {
        val (delim, text) = readText(input)
        val lines = parseLines(text, delim)
        if (lines.isEmpty() || lines.all { it.isEmpty() }) throw IllegalStateException("CSV 为空")
        val header = if (headerRow) lines[0].map { it.trim() } else emptyList()
        val bodyStart = if (headerRow) 1 else 0
        val columns = if (headerRow) header.ifEmpty { (1..(lines.getOrNull(bodyStart)?.size ?: 1)).map { "列$it" } }
            else (1..(lines.getOrNull(0)?.size ?: 1)).map { "列$it" }
        val rows = ArrayList<Map<String, String>>()
        for (i in bodyStart until lines.size) {
            val cells = lines[i]
            if (cells.isEmpty()) continue
            // 补齐/截断到列数（防某行列数不一致）
            val row = LinkedHashMap<String, String>()
            for (c in columns.indices) {
                row[columns[c]] = cells.getOrNull(c)?.trim() ?: ""
            }
            rows.add(row)
        }
        return Table(columns, rows)
    }

    /** 读入文本 + 探测分隔符。GBK 探测：UTF-8 严格解码失败或大量替换字符 → 回退 GBK */
    private fun readText(input: InputStream): Pair<Char, String> {
        val bytes = input.readBytes()
        val offset = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        var text = String(bytes, offset, bytes.size - offset, Charsets.UTF_8)
        // GBK 回退：UTF-8 严格校验（构造 CharsetDecoder 报错）或含大量 U+FFFD 替换符
        val decodeFailed = try {
            val dec = Charsets.UTF_8.newDecoder()
            dec.decode(java.nio.ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            false
        } catch (e: java.nio.charset.CharacterCodingException) {
            true
        }
        if (decodeFailed || text.count { it == '�' } > 0) {
            text = String(bytes, offset, bytes.size - offset, java.nio.charset.Charset.forName("GBK"))
        }
        // 分隔符探测：统计首行逗号/分号出现次数
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
        val comma = firstLine.count { it == ',' }
        val semi = firstLine.count { it == ';' }
        val delim = if (semi > comma) ';' else ','
        return delim to text
    }

    /** RFC4180 风格逐行解析：引号包裹字段内可含逗号/换行，`""` 转义引号 */
    private fun parseLines(text: String, delim: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val row = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                inQuotes -> {
                    if (ch == '"') {
                        if (i + 1 < text.length && text[i + 1] == '"') {
                            sb.append('"'); i++          // 转义引号 ""
                        } else {
                            inQuotes = false
                        }
                    } else {
                        sb.append(ch)
                    }
                }
                ch == '"' -> inQuotes = true
                ch == delim -> {
                    row.add(sb.toString().trim()); sb.setLength(0)
                }
                ch == '\r' -> { /* 忽略 CR，\r\n 归一 */ }
                ch == '\n' -> {
                    row.add(sb.toString().trim()); sb.setLength(0)
                    if (row.size > 1 || row[0].isNotEmpty()) {
                        rows.add(ArrayList(row)); row.clear()
                    } else {
                        row.clear()
                    }
                }
                else -> sb.append(ch)
            }
            i++
        }
        // 末尾行（无换行结尾）
        if (sb.isNotEmpty() || row.isNotEmpty()) {
            row.add(sb.toString().trim())
            if (row.size > 1 || row[0].isNotEmpty()) rows.add(row)
        }
        return rows
    }
}
