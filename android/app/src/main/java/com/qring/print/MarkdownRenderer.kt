package com.qring.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Markdown 渲染器（2026-08-14 加）：把 [MarkdownParser.parse] 的块序列渲染成
 * 384 宽白底黑字位图，走图片通道（mode=2）。
 *
 * 结构：先逐块折行成 [LayoutLine]（含 x 缩进/行高/基线字体），量高后一次绘出。
 * 文字像素在 Robolectric LEGACY 下不可测，视觉交 runPreviewCheck 自检；
 * 分割线（drawLine）、引用竖线（drawLine）可像素断言。
 */
object MarkdownRenderer {

    const val W = WIDTH_DOTS
    private const val MARGIN = 10f
    private const val BODY_SIZE = 20f
    private const val H1_SIZE = 32f
    private const val H2_SIZE = 27f
    private const val H3_SIZE = 24f
    private const val CODE_SIZE = 16f
    private const val LINE_GAP = 6f
    private const val BLOCK_GAP = 12f
    private const val LIST_INDENT = 22f
    private const val QUOTE_INDENT = 16f
    private const val CODE_INDENT = 14f
    private const val MAX_HEIGHT = RasterEncoder.MAX_TEXT_HEIGHT

    /** 文本片段：文字 + 专属 Paint（粗/斜/行内码在 spansOf 派生） */
    data class Span(val text: String, val paint: Paint)

    /** 一行布局：span 序列 + 起始 x + 行高 + 基线基准字体 + 辅助标记 */
    data class LayoutLine(
        val spans: List<Span>,
        val x: Float,
        val lineHeight: Float,
        val font: Paint,
        val hr: Boolean = false,
        val quote: Boolean = false,
        val gapBefore: Float = 0f,
    )

    fun render(blocks: List<MarkdownParser.Block>): Bitmap {
        val usable = W - MARGIN * 2
        val body = basePaint(BODY_SIZE)
        val code = basePaint(CODE_SIZE, mono = true)

        val lines = mutableListOf<LayoutLine>()
        var firstBlock = true
        for (block in blocks) {
            val blockGap = if (firstBlock) 0f else BLOCK_GAP
            firstBlock = false
            val blockLines = mutableListOf<LayoutLine>()
            when (block) {
                is MarkdownParser.Block.Heading -> {
                    val size = when (block.level) {
                        1 -> H1_SIZE; 2 -> H2_SIZE; 3 -> H3_SIZE; else -> BODY_SIZE + 2f
                    }
                    val h = basePaint(size, bold = true)
                    // 标题额外行距：视觉上更疏朗，也让标题行高高于正文（字体度量不可依赖时仍成立）
                    val extra = when (block.level) {
                        1 -> 8f; 2 -> 6f; 3 -> 4f; else -> 2f
                    }
                    val lh = h.fontMetrics.let { it.bottom - it.top } + LINE_GAP + extra
                    wrapSpans(spansOf(block.inline, h, code), usable).forEach { sp ->
                        blockLines.add(LayoutLine(sp, MARGIN, lh, h))
                    }
                }
                is MarkdownParser.Block.Paragraph -> {
                    val lh = body.fontMetrics.let { it.bottom - it.top } + LINE_GAP
                    wrapSpans(spansOf(block.inline, body, code), usable).forEach { sp ->
                        blockLines.add(LayoutLine(sp, MARGIN, lh, body))
                    }
                }
                is MarkdownParser.Block.BulletList -> {
                    val lh = body.fontMetrics.let { it.bottom - it.top } + LINE_GAP
                    val marker = "•  "
                    val markerW = body.measureText(marker)
                    block.items.forEach { item ->
                        val wrapped = wrapSpans(spansOf(item, body, code), usable - markerW)
                        wrapped.forEachIndexed { i, sp ->
                            val full = if (i == 0) listOf(Span(marker, body)) + sp else sp
                            blockLines.add(LayoutLine(full, MARGIN, lh, body))
                        }
                    }
                }
                is MarkdownParser.Block.OrderedList -> {
                    val lh = body.fontMetrics.let { it.bottom - it.top } + LINE_GAP
                    block.items.forEachIndexed { idx, item ->
                        val marker = "${block.start + idx}. "
                        val markerW = body.measureText(marker)
                        val wrapped = wrapSpans(spansOf(item, body, code), usable - markerW)
                        wrapped.forEachIndexed { i, sp ->
                            val full = if (i == 0) listOf(Span(marker, body)) + sp else sp
                            blockLines.add(LayoutLine(full, MARGIN, lh, body))
                        }
                    }
                }
                is MarkdownParser.Block.CodeBlock -> {
                    val lh = code.fontMetrics.let { it.bottom - it.top } + LINE_GAP
                    val content = if (block.content.isEmpty()) listOf("") else block.content.split("\n")
                    content.forEach { rawLine ->
                        val wrapped = wrapSpans(listOf(Span(rawLine, code)), usable - CODE_INDENT)
                        if (wrapped.isEmpty()) {
                            // 空行保留行高（否则代码块内空行被吞，行距塌缩）
                            blockLines.add(LayoutLine(emptyList(), MARGIN + CODE_INDENT, lh, code))
                        } else {
                            wrapped.forEach { sp ->
                                blockLines.add(LayoutLine(sp, MARGIN + CODE_INDENT, lh, code))
                            }
                        }
                    }
                }
                is MarkdownParser.Block.Blockquote -> {
                    val lh = body.fontMetrics.let { it.bottom - it.top } + LINE_GAP
                    block.lines.forEach { line ->
                        wrapSpans(spansOf(MarkdownParser.parseInline(line), body, code), usable - QUOTE_INDENT).forEach { sp ->
                            blockLines.add(LayoutLine(sp, MARGIN + QUOTE_INDENT, lh, body, quote = true))
                        }
                    }
                }
                is MarkdownParser.Block.ThematicBreak -> {
                    blockLines.add(LayoutLine(emptyList(), MARGIN, 4f, body, hr = true))
                }
            }
            blockLines.forEachIndexed { i, l ->
                lines.add(if (i == 0) l.copy(gapBefore = blockGap) else l)
            }
        }

        // 量高（含块间距与行高）
        var totalH = 0
        for (l in lines) totalH += (l.gapBefore + l.lineHeight).toInt()
        val truncated = totalH > MAX_HEIGHT
        val h = minOf(totalH, MAX_HEIGHT).coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val hrPaint = Paint().apply { color = Color.BLACK; strokeWidth = 3f }
        val quotePaint = Paint().apply { color = Color.BLACK; strokeWidth = 2f }
        var y = 0f
        for (l in lines) {
            y += l.gapBefore
            if (y.toInt() >= h) break
            if (l.hr) {
                canvas.drawLine(MARGIN, y + l.lineHeight / 2f, W - MARGIN, y + l.lineHeight / 2f, hrPaint)
            } else if (l.spans.isNotEmpty()) {
                val fm = l.font.fontMetrics
                val baseline = y - fm.top
                var x = l.x
                for (span in l.spans) {
                    canvas.drawText(span.text, x, baseline, span.paint)
                    x += span.paint.measureText(span.text)
                }
                if (l.quote) {
                    val barX = l.x - QUOTE_INDENT + 2f
                    canvas.drawLine(barX, y, barX, y + l.lineHeight, quotePaint)
                }
            }
            y += l.lineHeight
        }
        if (truncated) {
            canvas.drawText("…内容过长，已截断", MARGIN, h - 6f, basePaint(BODY_SIZE))
        }
        return bmp
    }

    // ── 折行 / inline → span ──

    /** 把 span 序列按可用宽度折成行；换行符强制断行（中文逐字符） */
    private fun wrapSpans(spans: List<Span>, usable: Float): List<List<Span>> {
        val lines = mutableListOf<List<Span>>()
        var line = mutableListOf<Span>()
        var lineW = 0f
        fun flush() {
            if (line.isNotEmpty()) { lines.add(line.toList()); line = mutableListOf(); lineW = 0f }
        }
        for (span in spans) {
            val segs = span.text.split('\n')
            segs.forEachIndexed { si, seg ->
                if (si > 0) flush()
                var cur = ""
                for (ch in seg) {
                    if (cur.isNotEmpty() && lineW + span.paint.measureText(cur + ch) > usable) {
                        line.add(Span(cur, span.paint))
                        flush()
                        cur = ch.toString()
                    } else {
                        cur += ch
                    }
                }
                if (cur.isNotEmpty()) {
                    if (line.isNotEmpty() && lineW + span.paint.measureText(cur) > usable) flush()
                    line.add(Span(cur, span.paint))
                    lineW += span.paint.measureText(cur)
                }
            }
        }
        flush()
        return lines
    }

    /** inline 树 → span 列表（粗/斜派生新 Paint，行内码换等宽） */
    private fun spansOf(inlines: List<MarkdownParser.Inline>, base: Paint, code: Paint): List<Span> {
        val out = mutableListOf<Span>()
        for (inline in inlines) {
            when (inline) {
                is MarkdownParser.Inline.Text -> out.add(Span(inline.text, base))
                is MarkdownParser.Inline.Code -> out.add(Span(inline.text, code))
                is MarkdownParser.Inline.Link -> out.add(Span(inline.text, base))
                is MarkdownParser.Inline.Bold -> out.addAll(spansOf(inline.content, styled(base, bold = true), code))
                is MarkdownParser.Inline.Italic -> out.addAll(spansOf(inline.content, styled(base, italic = true), code))
            }
        }
        return out
    }

    private fun basePaint(size: Float, bold: Boolean = false, mono: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            color = Color.BLACK
            isFakeBoldText = bold
            if (mono) typeface = Typeface.MONOSPACE
        }

    private fun styled(base: Paint, bold: Boolean = false, italic: Boolean = false): Paint =
        Paint(base).apply {
            isFakeBoldText = bold
            textSkewX = if (italic) -0.2f else 0f
        }
}
