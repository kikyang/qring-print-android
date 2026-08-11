package com.qring.print

import android.content.Context
import android.net.Uri
import android.util.Xml
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser

/**
 * Excel (.xlsx) 表格提取（2026-08-11 加，零依赖）。
 *
 * xlsx = zip 容器：xl/sharedStrings.xml（字符串表）+ xl/worksheets/sheet1.xml（第一个工作表）。
 * 单元格 <c r="A1" t="s"><v>n</v></c> 的 n 是 sharedStrings 索引；数字单元格 <v> 直取。
 * 每行输出一行文本（单元格间空格分隔），走文字打印管线。
 *
 * 简化（窄纸打印可接受）：只取第一个工作表；合并单元格/公式/数字格式/列宽不处理。
 */
object XlsxTextExtractor {

    private const val S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

    /**
     * 提取工作表行文本。@throws Exception 解析失败（损坏/非 xlsx）
     * @param onProgress 解析进度回调（已读行数，大表防"像没反应"）
     */
    fun extract(context: Context, uri: Uri, onProgress: (Int) -> Unit = {}): List<String> {
        val lines = ArrayList<String>()
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法打开 Excel 文件")
        input.use { fileIn ->
            ZipInputStream(fileIn.buffered()).use { zip ->
                // 第一遍：读字符串表（可能不存在——纯数字表没有 sharedStrings.xml）
                val shared = ArrayList<String>()
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "xl/sharedStrings.xml") {
                        readSharedStrings(zip, shared)
                        break
                    }
                    entry = zip.nextEntry
                }
                // 第二遍：读第一个工作表（zip 流只能顺序读，需重新打开）
                val zip2 = ZipInputStream(context.contentResolver.openInputStream(uri)!!.buffered())
                zip2.use {
                    var e = it.nextEntry
                    while (e != null) {
                        if (e.name == "xl/worksheets/sheet1.xml") {
                            readSheet(it, shared, lines, onProgress)
                            return lines
                        }
                        e = it.nextEntry
                    }
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

    private fun readSheet(input: java.io.InputStream, shared: List<String>, out: MutableList<String>, onProgress: (Int) -> Unit) {
        val parser = Xml.newPullParser()
        parser.setFeature("http://xmlpull.org/v1/doc/features.html#process-namespaces", false)
        parser.setInput(input, "UTF-8")
        val cells = ArrayList<String>()
        var inRow = false
        var cellType: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "row" -> { inRow = true; cells.clear() }
                        "c" -> cellType = parser.getAttributeValue(null, "t")
                        "v" -> {
                            if (inRow) {
                                val v = parser.nextText()
                                cells.add(if (cellType == "s") {
                                    v.toIntOrNull()?.let { idx -> shared.getOrNull(idx) } ?: ""
                                } else {
                                    v
                                })
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "row" && inRow) {
                        inRow = false
                        val text = cells.joinToString(" ").trim()
                        if (text.isNotEmpty()) {
                            out.add(text)
                            // 节流回调：每 50 行报一次
                            if (out.size % 50 == 0) onProgress(out.size)
                        }
                    }
                }
            }
            event = parser.next()
        }
    }
}
