package com.qring.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * DocLayout 简化排版器（2026-08-18 移植自 flowTypeset.ts 的核心思路）。
 *
 * 能力：
 * - 段落：文字 + 公式混排，按 384 宽自动折行
 * - 表格：以 ` | ` 分隔的文本行呈现（保留内容，后续可升级为真正表格线）
 * - 公式：使用 [MathLayout] 渲染为行内盒
 */
object DocLayoutRenderer {

    const val W = WIDTH_DOTS
    private const val MARGIN = 8f
    private const val BODY_SIZE = 20f
    private const val LINE_GAP = 4f
    private const val BLOCK_GAP = 10f
    private const val MAX_HEIGHT = RasterEncoder.MAX_TEXT_HEIGHT

    private sealed class El {
        data class Text(val text: String, val paint: Paint) : El()
        data class Math(val box: MathBox) : El()
    }

    private data class Line(val els: List<El>, val height: Float, val gapBefore: Float = 0f)

    fun render(layout: DocLayout): Bitmap {
        val usable = W - MARGIN * 2
        val body = basePaint(BODY_SIZE)
        val lines = mutableListOf<Line>()
        var first = true
        for (block in layout.blocks) {
            val gap = if (first) 0f else BLOCK_GAP
            first = false
            when (block) {
                is DocParagraph -> lines.addAll(paragraphLines(block, body, usable, gap))
                is DocTable -> lines.addAll(tableLines(block, body, usable, gap))
            }
        }

        var total = 0f
        for (l in lines) total += l.gapBefore + l.height
        val truncated = total > MAX_HEIGHT
        val h = minOf(total.toInt(), MAX_HEIGHT).coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        var y = 0f
        for (line in lines) {
            y += line.gapBefore
            if (y.toInt() >= h) break
            drawLine(canvas, line.els, MARGIN, y, line.height)
            y += line.height
        }
        if (truncated) {
            canvas.drawText("…内容过长，已截断", MARGIN, h - 6f, basePaint(BODY_SIZE))
        }
        return bmp
    }

    private fun paragraphLines(p: DocParagraph, body: Paint, usable: Float, gap: Float): List<Line> {
        val elements = mutableListOf<El>()
        for (inline in p.inlines) {
            when (inline) {
                is DocText -> {
                    val size = if (inline.size > 0) inline.size.toFloat() else BODY_SIZE
                    val paint = if (inline.bold) basePaint(size, bold = true) else basePaint(size)
                    elements.add(El.Text(inline.text, paint))
                }
                is DocMath -> {
                    val measure: MathMeasurer = { text, sz, b ->
                        basePaint(sz.toFloat(), bold = b).measureText(text)
                    }
                    val box = MathLayout.layout(inline.nodes, BODY_SIZE.toInt(), false, measure)
                    elements.add(El.Math(box))
                }
            }
        }
        return wrap(elements, usable, gap)
    }

    private fun tableLines(t: DocTable, body: Paint, usable: Float, gap: Float): List<Line> {
        val lines = mutableListOf<Line>()
        for (row in t.rows) {
            val rowText = row.joinToString(" | ") { cell ->
                cell.blocks.joinToString(" ") { block ->
                    when (block) {
                        is DocParagraph -> block.inlines.joinToString("") { inline ->
                            when (inline) {
                                is DocText -> inline.text
                                is DocMath -> mathToText(inline.nodes)
                            }
                        }
                        is DocTable -> "…"
                    }
                }
            }
            if (rowText.isNotBlank()) {
                val el = El.Text(rowText, body)
                lines.addAll(wrap(listOf(el), usable, gap))
            }
        }
        return lines
    }

    private fun wrap(elements: List<El>, usable: Float, gap: Float): List<Line> {
        val out = mutableListOf<Line>()
        var line = mutableListOf<El>()
        var lineW = 0f
        fun flush() {
            if (line.isEmpty()) return
            val heights = line.map { el ->
                when (el) {
                    is El.Text -> {
                        val fm = el.paint.fontMetrics
                        (fm.bottom - fm.top) + LINE_GAP
                    }
                    is El.Math -> (el.box.h + LINE_GAP).toFloat()
                }
            }
            out.add(Line(line.toList(), heights.maxOrNull() ?: 0f, gap))
            line = mutableListOf()
            lineW = 0f
        }
        for (el in elements) {
            when (el) {
                is El.Math -> {
                    if (line.isNotEmpty() && lineW + el.box.w > usable) flush()
                    line.add(el); lineW += el.box.w
                }
                is El.Text -> {
                    for (ch in el.text) {
                        val w = el.paint.measureText(ch.toString())
                        if (line.isNotEmpty() && lineW + w > usable) flush()
                        line.add(El.Text(ch.toString(), el.paint)); lineW += w
                    }
                }
            }
        }
        flush()
        return out
    }

    private fun drawLine(canvas: Canvas, els: List<El>, x0: Float, y: Float, height: Float) {
        var x = x0
        for (el in els) {
            when (el) {
                is El.Text -> {
                    val fm = el.paint.fontMetrics
                    val baseline = y - fm.top
                    canvas.drawText(el.text, x, baseline, el.paint)
                    x += el.paint.measureText(el.text)
                }
                is El.Math -> {
                    val box = el.box
                    val top = y + (height - box.h) / 2f
                    MathLayout.renderBox(canvas, box, x.toInt(), top.toInt())
                    x += box.w
                }
            }
        }
    }

    private fun mathToText(nodes: List<MathNode>): String =
        nodes.joinToString(" ") { node ->
            when (node) {
                is MRun -> node.text
                is MFrac -> "(${mathToText(node.num)}/${mathToText(node.den)})"
                is MScript -> mathToText(node.base)
                is MRad -> "√(${mathToText(node.body)})"
                is MDelim -> "${node.beg}${mathToText(node.body)}${node.end}"
                is MBar -> mathToText(node.body)
                is MNary -> "${node.chr}(${mathToText(node.body)})"
                is MMatrix -> "[${node.rows.joinToString(";") { row -> row.joinToString(",") { mathToText(it) } }}]"
            }
        }

    private fun basePaint(size: Float, bold: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            color = Color.BLACK
            isFakeBoldText = bold
            typeface = Typeface.DEFAULT
        }
}
