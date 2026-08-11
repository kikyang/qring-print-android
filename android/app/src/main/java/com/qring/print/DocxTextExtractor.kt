package com.qring.print

import android.content.Context
import android.net.Uri
import android.util.Xml
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser

/**
 * Word (.docx) 纯文本提取（2026-08-11 加，零依赖）。
 *
 * docx = zip 容器，正文在 word/document.xml。用 XmlPullParser 流式解析：
 * <w:p> = 段落，<w:t> = 文本片段（同段内多个 <w:t> 拼接）。
 * 样式/图片/表格全丢弃——58mm 热敏窄纸上本来也展示不了，纯文本流式打印即可。
 *
 * 注意 xml:space="preserve"：<w:t> 内的首尾空格是有效内容，需原样保留。
 */
object DocxTextExtractor {

    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    /**
     * 提取段落列表（空行已过滤）。@throws Exception 解析失败（损坏/非 docx）
     * @param onProgress 解析进度回调（已提取段落数，大文件防"像没反应"）
     */
    fun extract(context: Context, uri: Uri, onProgress: (Int) -> Unit = {}): List<String> {
        val paragraphs = ArrayList<String>()
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法打开 Word 文件")
        input.use { fileIn ->
            ZipInputStream(fileIn.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        parseDocument(zip, paragraphs, onProgress)
                        return paragraphs
                    }
                    entry = zip.nextEntry
                }
            }
        }
        throw IllegalStateException("不是有效的 Word (.docx) 文件")
    }

    private fun parseDocument(input: java.io.InputStream, out: MutableList<String>, onProgress: (Int) -> Unit) {
        val parser = Xml.newPullParser()
        parser.setFeature("http://xmlpull.org/v1/doc/features.html#process-namespaces", false)
        parser.setInput(input, "UTF-8")
        val sb = StringBuilder()
        var inParagraph = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    if (name == "w:p") {
                        inParagraph = true
                        sb.setLength(0)
                    } else if (inParagraph && name == "w:t") {
                        sb.append(parser.nextText())
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "w:p" && inParagraph) {
                        inParagraph = false
                        val text = sb.toString().trim()
                        if (text.isNotEmpty()) {
                            out.add(text)
                            // 节流回调：每 20 段报一次（频繁回调会卡 UI）
                            if (out.size % 20 == 0) onProgress(out.size)
                        }
                    }
                }
            }
            event = parser.next()
        }
    }
}
