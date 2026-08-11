package com.qring.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * 错因模板生成器 —— 仿主流错题打印 APP 的"错因分析卡"版式。
 *
 * 版式（384 点宽）：
 *   ┌────────────────────────────┐
 *   │  ★ 错因分析                 │  标题（加粗）
 *   │  ───────────────────────   │
 *   │  [题目图片区（如有）]        │
 *   │  错因：概念不清 / 计算错误   │
 *   │  知识点：xxx                │
 *   │  订正：                     │
 *   │  ─────────────             │  订正横线区
 *   │  ─────────────             │
 *   │  举一反三：                 │
 *   │  ─────────────             │
 *   └────────────────────────────┘
 */
object TemplateBuilder {

    private const val W = WIDTH_DOTS   // QringProtocol 顶层常量：384 点
    private const val MARGIN = 10f
    private const val BODY_SIZE = 20f
    private const val TITLE_SIZE = 26f
    private const val LINE_GAP = 14f
    /** 手写区行高：80 点 ≈ 10mm（203dpi），正常书写够用 */
    private const val WRITE_GAP = 80f

    /**
     * 生成错题卡位图。
     *
     * @param reason 错因文字
     * @param knowledge 知识点文字
     * @param problemImage 题目图片（可空；非空时等比缩到整幅宽后插入标题下方）
     */
    fun build(
        reason: String,
        knowledge: String,
        problemImage: Bitmap? = null,
    ): Bitmap {
        val usable = (W - MARGIN * 2)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TITLE_SIZE; isFakeBoldText = true; color = Color.BLACK
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = BODY_SIZE; color = Color.BLACK
        }
        val titleH = titlePaint.fontMetrics.let { it.bottom - it.top }
        val bodyH = bodyPaint.fontMetrics.let { it.bottom - it.top }

        fun wrap(text: String): List<String> {
            val lines = mutableListOf<String>()
            var cur = ""
            for (ch in text) {
                if (ch == '\n') { lines.add(cur); cur = ""; continue }
                if (bodyPaint.measureText(cur + ch) <= usable) cur += ch
                else { lines.add(cur); cur = ch.toString() }
            }
            if (cur.isNotEmpty()) lines.add(cur)
            return lines
        }
        val reasonLines = wrap("错因：$reason")
        val knowLines = wrap("知识点：$knowledge")
        val reasonH = reasonLines.size * bodyH
        val knowH = knowLines.size * bodyH

        // ── 量总高 ──
        var y = MARGIN
        y += titleH + LINE_GAP * 2f / 3
        if (problemImage != null) {
            y += (problemImage.height * usable / problemImage.width) + LINE_GAP
        }
        y += reasonH + LINE_GAP
        y += knowH + LINE_GAP
        y += bodyH + LINE_GAP / 2            // "订正："标签
        y += 3 * WRITE_GAP                   // 手写区 3 行（80 点/行）
        y += LINE_GAP + bodyH + LINE_GAP / 2 // "举一反三："标签
        y += 2 * WRITE_GAP                   // 手写区 2 行
        val height = (y + MARGIN).toInt().coerceAtLeast(1)

        // ── 绘制 ──
        val bmp = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        y = MARGIN - titlePaint.fontMetrics.ascent  // 基线
        canvas.drawText("★ 错因分析", MARGIN, y, titlePaint)
        y += titleH + LINE_GAP * 2f / 3

        if (problemImage != null) {
            val imgH = maxOf(1, (problemImage.height * usable / problemImage.width).toInt())
            val scaled = Bitmap.createScaledBitmap(problemImage, usable.toInt(), imgH, true)
            canvas.drawBitmap(scaled, MARGIN, y, null)
            y += imgH + LINE_GAP
        }

        fun drawLines(texts: List<String>) {
            for (t in texts) {
                canvas.drawText(t, MARGIN, y - bodyPaint.fontMetrics.ascent, bodyPaint)
                y += bodyH
            }
        }
        drawLines(reasonLines)
        y += LINE_GAP
        drawLines(knowLines)

        // 订正标签 + 手写横线区（行高 80 点 ≈ 10mm；练习本风格线在行底）
        y += LINE_GAP
        canvas.drawText("订正：", MARGIN, y - bodyPaint.fontMetrics.ascent, bodyPaint)
        y += bodyH + LINE_GAP / 2
        val linePaint = Paint().apply { color = Color.BLACK; strokeWidth = 3f }
        repeat(3) {
            val lineY = y + WRITE_GAP - 4f
            canvas.drawLine(MARGIN, lineY, (W - MARGIN), lineY, linePaint)
            y += WRITE_GAP
        }
        // 举一反三标签 + 手写横线区
        y += LINE_GAP
        canvas.drawText("举一反三：", MARGIN, y - bodyPaint.fontMetrics.ascent, bodyPaint)
        y += bodyH + LINE_GAP / 2
        repeat(2) {
            val lineY = y + WRITE_GAP - 4f
            canvas.drawLine(MARGIN, lineY, (W - MARGIN), lineY, linePaint)
            y += WRITE_GAP
        }

        return bmp
    }
}
