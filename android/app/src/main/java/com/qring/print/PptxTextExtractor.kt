package com.qring.print

import android.content.Context
import android.net.Uri
import java.util.zip.ZipInputStream
import kotlin.math.max

/**
 * PPTX 文本提取（2026-08-18 加，v0.7.2 轻量方案）：
 * 直接解压 pptx，读取 ppt/slides/slideN.xml 中的 <a:t> 文本。
 * 不还原原始版式/位置/图片，先把内容完整提取出来供热敏打印。
 */
object PptxTextExtractor {

    /** 每页文本之间用分隔线隔开，方便在热敏纸上区分幻灯片 */
    private const val SLIDE_SEPARATOR = "---------- 幻灯片 ----------"

    /**
     * 提取 pptx 全部幻灯片文本。
     * @return 每页一个字符串；无文本页用空串占位。
     */
    fun extract(context: Context, uri: Uri): List<String> {
        val slides = mutableListOf<String>()
        val input = context.contentResolver.openInputStream(uri) ?: return emptyList()
        input.use { raw ->
            val zip = ZipInputStream(raw.buffered())
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (Regex("^ppt/slides/slide\\d+\\.xml$").matches(name)) {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    val texts = Regex("<a:t>([^<]*)</a:t>").findAll(xml)
                        .map { it.groupValues[1].trim() }
                        .filter { it.isNotEmpty() }
                        .toList()
                    slides.add(texts.joinToString("\n"))
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        // 按 slide 编号排序（zip 顺序通常有序，但稳妥起见）
        val indexed = slides.mapIndexed { i, text -> i + 1 to text }
            .sortedBy { it.first }
        return indexed.map { it.second }
    }

    /** 提取全部页文本并拼成一段可打印文本（含页分隔） */
    fun extractToText(context: Context, uri: Uri): String {
        val slides = extract(context, uri)
        if (slides.isEmpty()) return ""
        val max = max(1, slides.size)
        return slides.mapIndexed { i, text ->
            val header = "【第 ${i + 1}/$max 页】"
            if (text.isBlank()) header else "$header\n$text"
        }.joinToString("\n\n$SLIDE_SEPARATOR\n\n")
    }
}
