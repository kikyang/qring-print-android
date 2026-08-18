package com.qring.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.roundToInt

typealias MathMeasurer = (text: String, size: Int, bold: Boolean) -> Float

/**
 * OMML 公式子集排版器（2026-08-18 移植自 yiran168/suda-win-web mathLayout.ts）。
 * 覆盖：分数、上下标、根式、括号、上划线、大型运算符、矩阵。
 */
sealed class MathNode
data class MRun(val text: String) : MathNode()
data class MFrac(val num: List<MathNode>, val den: List<MathNode>) : MathNode()
data class MScript(val base: List<MathNode>, val sup: List<MathNode>?, val sub: List<MathNode>?) : MathNode()
data class MRad(val deg: List<MathNode>?, val body: List<MathNode>) : MathNode()
data class MDelim(val beg: String, val end: String, val body: List<MathNode>) : MathNode()
data class MBar(val body: List<MathNode>) : MathNode()
data class MNary(val chr: String, val sub: List<MathNode>?, val sup: List<MathNode>?, val body: List<MathNode>) : MathNode()
data class MMatrix(val rows: List<List<List<MathNode>>>) : MathNode()

sealed class MathItem
data class MathTextItem(val x: Int, val y: Int, val text: String, val size: Int, val bold: Boolean) : MathItem()
data class MathRectItem(val x: Int, val y: Int, val w: Int, val h: Int) : MathItem()

data class MathBox(val w: Int, val h: Int, val ascent: Int, val items: List<MathItem>)

object MathLayout {

    private const val MIN_SIZE = 10
    private const val FRAC_SCALE = 0.72
    private const val SCRIPT_SCALE = 0.68
    private const val LIMIT_SCALE = 0.6
    private const val MATRIX_SCALE = 0.85

    fun layout(nodes: List<MathNode>, size: Int, bold: Boolean, measure: MathMeasurer): MathBox =
        seq(nodes, size, bold, measure)

    fun renderToBitmap(nodes: List<MathNode>, size: Int = 28, bold: Boolean = false): Bitmap {
        val measure: MathMeasurer = { text, sz, b ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = sz.toFloat(); color = Color.BLACK; isFakeBoldText = b
            }
            p.measureText(text)
        }
        val box = layout(nodes, size, bold, measure)
        val bmp = Bitmap.createBitmap(max(1, box.w + 4), max(1, box.h + 4), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        drawBox(canvas, box, 2, 2)
        return bmp
    }

    /** 供文档排版器把公式盒画到指定位置（dx/dy 为盒左上角） */
    fun renderBox(canvas: Canvas, box: MathBox, dx: Int, dy: Int) = drawBox(canvas, box, dx, dy)

    private fun drawBox(canvas: Canvas, box: MathBox, dx: Int, dy: Int) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val rectPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        for (item in box.items) {
            when (item) {
                is MathTextItem -> {
                    textPaint.textSize = item.size.toFloat()
                    textPaint.isFakeBoldText = item.bold
                    canvas.drawText(item.text, (dx + item.x).toFloat(), (dy + item.y).toFloat(), textPaint)
                }
                is MathRectItem -> canvas.drawRect(
                    (dx + item.x).toFloat(), (dy + item.y).toFloat(),
                    (dx + item.x + item.w).toFloat(), (dy + item.y + item.h).toFloat(), rectPaint
                )
            }
        }
    }

    private fun seq(nodes: List<MathNode>, size: Int, bold: Boolean, measure: MathMeasurer): MathBox =
        concat(nodes.flatMap { layoutNode(it, size, bold, measure) })

    private fun layoutNode(n: MathNode, size: Int, bold: Boolean, measure: MathMeasurer): List<MathBox> {
        val s = max(MIN_SIZE, size)
        return when (n) {
            is MRun -> if (n.text.isEmpty()) emptyList() else listOf(runBox(n.text, s, bold, measure))
            is MFrac -> listOf(frac(n, s, bold, measure))
            is MScript -> listOf(script(n, s, bold, measure))
            is MRad -> listOf(rad(n, s, bold, measure))
            is MDelim -> listOf(delim(n, s, bold, measure))
            is MBar -> listOf(bar(n, s, bold, measure))
            is MNary -> listOf(nary(n, s, bold, measure))
            is MMatrix -> listOf(matrix(n, s, bold, measure))
        }
    }

    private fun runBox(text: String, size: Int, bold: Boolean, measure: MathMeasurer): MathBox {
        val w = max(1, measure(text, size, bold).roundToInt())
        val ascent = (size * 1.05).roundToInt()
        return MathBox(w, (size * 1.35).roundToInt(), ascent, listOf(MathTextItem(0, ascent, text, size, bold)))
    }

    private fun concat(boxes: List<MathBox>): MathBox {
        if (boxes.isEmpty()) return MathBox(1, 4, 2, emptyList())
        val ascent = boxes.maxOf { it.ascent }
        val descent = boxes.maxOf { it.h - it.ascent }
        var x = 0
        val items = mutableListOf<MathItem>()
        for (b in boxes) {
            items.addAll(translate(b.items, x, ascent - b.ascent))
            x += b.w
        }
        return MathBox(x, ascent + descent, ascent, items)
    }

    private fun translate(items: List<MathItem>, dx: Int, dy: Int): List<MathItem> =
        items.map {
            when (it) {
                is MathTextItem -> it.copy(x = it.x + dx, y = it.y + dy)
                is MathRectItem -> it.copy(x = it.x + dx, y = it.y + dy)
            }
        }

    private fun frac(n: MFrac, s: Int, bold: Boolean, measure: MathMeasurer): MathBox {
        val num = seq(n.num, (s * FRAC_SCALE).roundToInt(), bold, measure)
        val den = seq(n.den, (s * FRAC_SCALE).roundToInt(), bold, measure)
        val gap = max(3, (s * 0.18).roundToInt())
        val barH = max(2, (s * 0.08).roundToInt())
        val w = max(num.w, den.w) + 6
        val barY = num.h + gap
        val h = num.h + gap + barH + gap + den.h
        val ascent = barY + barH + (s * 0.3).roundToInt()
        val items = mutableListOf<MathItem>()
        items.addAll(translate(num.items, ((w - num.w) / 2).coerceAtLeast(0), 0))
        items.add(MathRectItem(0, barY, w, barH))
        items.addAll(translate(den.items, ((w - den.w) / 2).coerceAtLeast(0), barY + barH + gap))
        return MathBox(w, h, ascent, items)
    }

    private fun script(n: MScript, s: Int, bold: Boolean, measure: MathMeasurer): MathBox {
        val base = seq(n.base, s, bold, measure)
        val supB = if (n.sup != null && n.sup.isNotEmpty()) seq(n.sup, (s * SCRIPT_SCALE).roundToInt(), bold, measure) else null
        val subB = if (n.sub != null && n.sub.isNotEmpty()) seq(n.sub, (s * SCRIPT_SCALE).roundToInt(), bold, measure) else null
        val baseDesc = base.h - base.ascent
        val shiftUp = (base.ascent * 0.55).roundToInt()
        val shiftDown = max(2, (baseDesc * 0.9).roundToInt())
        val scriptX = base.w + 2
        val scriptW = max(supB?.w ?: 0, subB?.w ?: 0)
        val ascent = maxOf(base.ascent, supB?.let { shiftUp + it.ascent } ?: 0, subB?.let { it.ascent - shiftDown } ?: 0)
        val descent = maxOf(baseDesc, subB?.let { shiftDown + it.h - it.ascent } ?: 0, supB?.let { it.h - it.ascent - shiftUp } ?: 0)
        val items = mutableListOf<MathItem>()
        items.addAll(translate(base.items, 0, ascent - base.ascent))
        if (supB != null) items.addAll(translate(supB.items, scriptX, ascent - shiftUp - supB.ascent))
        if (subB != null) items.addAll(translate(subB.items, scriptX, ascent + shiftDown - subB.ascent))
        return MathBox(scriptX + scriptW, ascent + descent, ascent, items)
    }

    private fun rad(n: MRad, s: Int, bold: Boolean, measure: MathMeasurer): MathBox {
        val body = seq(n.body, s, bold, measure)
        val lineH = max(2, (s * 0.08).roundToInt())
        val radSize = max(s, (body.h * 0.95).roundToInt())
        val degB = if (n.deg != null && n.deg.isNotEmpty()) seq(n.deg, (s * 0.55).roundToInt(), bold, measure) else null
        val degW = if (degB != null) (degB.w * 0.7).roundToInt() else 0
        val signX = degW
        val signW = measure("√", radSize, false).roundToInt() + 2
        val bodyX = signX + signW + 2
        val bodyY = lineH + 1
        val h = bodyY + body.h
        val ascent = bodyY + body.ascent
        val items = mutableListOf<MathItem>()
        items.add(MathTextItem(signX, ascent, "√", radSize, false))
        items.add(MathRectItem(bodyX - 2, 0, body.w + 3, lineH))
        items.addAll(translate(body.items, bodyX, bodyY))
        if (degB != null) items.addAll(translate(degB.items, 0, 0))
        return MathBox(bodyX + body.w + 1, h, ascent, items)
    }

    private fun delim(n: MDelim, s: Int, bold: Boolean, measure: MathMeasurer): MathBox {
        val body = seq(n.body, s, bold, measure)
        val beg = n.beg.ifEmpty { "(" }
        val end = n.end.ifEmpty { ")" }
        val glyphSize = max(s, (body.h * 0.92).roundToInt())
        val begW = measure(beg, glyphSize, false).roundToInt() + 1
        val endW = measure(end, glyphSize, false).roundToInt() + 1
        val ascent = max(body.ascent, (glyphSize * 0.92).roundToInt())
        val descent = max(body.h - body.ascent, (glyphSize * 0.3).roundToInt())
        val items = mutableListOf<MathItem>()
        items.add(MathTextItem(0, ascent, beg, glyphSize, false))
        items.addAll(translate(body.items, begW + 2, ascent - body.ascent))
        items.add(MathTextItem(begW + 2 + body.w + 2, ascent, end, glyphSize, false))
        return MathBox(begW + body.w + endW + 4, ascent + descent, ascent, items)
    }

    private fun bar(n: MBar, s: Int, bold: Boolean, measure: MathMeasurer): MathBox {
        val body = seq(n.body, s, bold, measure)
        val barH = max(2, (s * 0.07).roundToInt())
        val gap = 2
        val items = mutableListOf<MathItem>()
        items.add(MathRectItem(0, 0, body.w, barH))
        items.addAll(translate(body.items, 0, gap + barH))
        return MathBox(body.w, body.h + gap + barH, body.ascent + gap + barH, items)
    }

    private fun nary(n: MNary, s: Int, bold: Boolean, measure: MathMeasurer): MathBox {
        val chr = n.chr.ifEmpty { "∑" }
        val chrSize = (s * 1.6).roundToInt()
        val chrW = measure(chr, chrSize, false).roundToInt()
        val chrAscent = chrSize
        val chrH = (chrSize * 1.3).roundToInt()
        val subB = if (n.sub != null && n.sub.isNotEmpty()) seq(n.sub, (s * LIMIT_SCALE).roundToInt(), bold, measure) else null
        val supB = if (n.sup != null && n.sup.isNotEmpty()) seq(n.sup, (s * LIMIT_SCALE).roundToInt(), bold, measure) else null
        val body = seq(n.body, s, bold, measure)
        val colW = maxOf(chrW, subB?.w ?: 0, supB?.w ?: 0)
        val topH = if (supB != null) supB.h + 1 else 0
        val botH = if (subB != null) subB.h + 1 else 0
        val ascent = topH + chrAscent
        val h = topH + chrH + botH
        val items = mutableListOf<MathItem>()
        items.add(MathTextItem(((colW - chrW) / 2).coerceAtLeast(0), ascent, chr, chrSize, false))
        if (supB != null) items.addAll(translate(supB.items, ((colW - supB.w) / 2).coerceAtLeast(0), 0))
        if (subB != null) items.addAll(translate(subB.items, ((colW - subB.w) / 2).coerceAtLeast(0), topH + chrH + 1))
        val bodyX = colW + 3
        items.addAll(translate(body.items, bodyX, ascent - body.ascent))
        return MathBox(bodyX + body.w, max(h, ascent + body.h - body.ascent), ascent, items)
    }

    private fun matrix(n: MMatrix, s: Int, bold: Boolean, measure: MathMeasurer): MathBox {
        if (n.rows.isEmpty()) return runBox("[]", s, bold, measure)
        val cellSize = (s * MATRIX_SCALE).roundToInt()
        val cells = n.rows.map { row -> row.map { seq(it, cellSize, bold, measure) } }
        val cols = cells.maxOfOrNull { it.size } ?: 1
        val colW = IntArray(cols) { j -> (cells.mapNotNull { it.getOrNull(j)?.w }.maxOrNull() ?: 0) + 14 }
        val rowH = IntArray(cells.size) { i -> (cells[i].maxOfOrNull { it.h } ?: 0) + 8 }
        val gridW = colW.sum()
        val gridH = rowH.sum()
        val bracketSize = max(s, (gridH * 0.98).roundToInt())
        val brW = measure("[", bracketSize, false).roundToInt() + 2
        val ascent = (gridH / 2 + s * 0.25).roundToInt()
        val items = mutableListOf<MathItem>()
        items.add(MathTextItem(0, ascent, "[", bracketSize, false))
        items.add(MathTextItem(brW + gridW + 2, ascent, "]", bracketSize, false))
        var y = 0
        for (i in cells.indices) {
            var x = brW + 2
            for (j in cells[i].indices) {
                val c = cells[i][j]
                items.addAll(translate(c.items,
                    x + ((colW[j] - c.w) / 2).coerceAtLeast(0),
                    y + ((rowH[i] - c.h) / 2).coerceAtLeast(0)))
                x += colW[j]
            }
            y += rowH[i]
        }
        val h = max(gridH, ascent + (s * 0.4).roundToInt())
        return MathBox(brW * 2 + gridW + 4, h, ascent, items)
    }
}
