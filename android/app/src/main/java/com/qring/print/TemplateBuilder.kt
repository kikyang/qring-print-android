package com.qring.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint

/**
 * 错题卡模板生成器 —— 仿主流错题打印 APP 的"错因分析卡"版式。
 *
 * 提供三种版式（384 点宽）：
 *  1. [build] 标准错因卡：
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
 *
 *  2. [buildReview] 复习友好版（2026-08-13 加）：
 *   题目图在前，订正区在纸后段，中间虚线撕纸线分开——
 *   学生可沿虚线撕开，只留题目图重复做（遮答案重做），订正区单独收。
 *   卡面顶部带复习进度栏（第1/2/3次勾选格 + 日期空栏），纸面维持复习节奏。
 *   ┌────────────────────────────┐
 *   │  ★ 错题卡·复习版            │
 *   │  复习 □第1次____ □第2次____ │  进度栏（勾选格 + 日期横线）
 *   │  ───────────────────────   │
 *   │  [题目图片区]               │  题目图在前
 *   │  ┄┄┄✂ 沿此撕开 ┄┄┄┄      │  撕纸线（虚线）
 *   │  错因：xxx                  │  订正区在纸后段
 *   │  知识点：xxx                │
 *   │  订正： ──── ──── ────     │
 *   │  举一反三： ──── ────       │
 *   └────────────────────────────┘
 *
 *  3. [buildReworkSheet] 重做卷（2026-08-13 加）：
 *   选 N 张错题图 → 题目区在前（多图顺序拼排）、订正区在后的卷子格式，
 *   中间撕纸线。一张卷子承载一批错题的"重做"动作。
 *   ┌────────────────────────────┐
 *   │  ★ 重做卷（N 题）           │
 *   │  [题目图1]                 │  题目区在前
 *   │  [题目图2]                 │
 *   │  ┄┄┄✂ 沿此撕开 ┄┄┄┄      │  撕纸线
 *   │  订正： ──── ──── ────     │  订正区在后
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

    private fun titlePaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TITLE_SIZE; isFakeBoldText = true; color = Color.BLACK
    }
    private fun bodyPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = BODY_SIZE; color = Color.BLACK
    }
    private fun linePaint(): Paint = Paint().apply { color = Color.BLACK; strokeWidth = 3f }
    private fun dashPaint(): Paint = Paint().apply {
        color = Color.BLACK; strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    /** 按可用宽度自动换行（中文逐字符） */
    private fun wrap(text: String, paint: Paint, usable: Float): List<String> {
        val lines = mutableListOf<String>()
        var cur = ""
        for (ch in text) {
            if (ch == '\n') { lines.add(cur); cur = ""; continue }
            if (paint.measureText(cur + ch) <= usable) cur += ch
            else { lines.add(cur); cur = ch.toString() }
        }
        if (cur.isNotEmpty()) lines.add(cur)
        return lines
    }

    // ═══════════════════ 标准错因卡（2026-08-11 原版式，行为不变） ═══════════════════

    /**
     * 生成标准错题卡位图。
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
        val titlePaint = titlePaint()
        val bodyPaint = bodyPaint()
        val titleH = titlePaint.fontMetrics.let { it.bottom - it.top }
        val bodyH = bodyPaint.fontMetrics.let { it.bottom - it.top }

        fun wrapBody(text: String) = wrap(text, bodyPaint, usable)
        val reasonLines = wrapBody("错因：$reason")
        val knowLines = wrapBody("知识点：$knowledge")
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
        y += bodyH + LINE_GAP / 2             // "订正："标签
        y += 3 * WRITE_GAP                    // 手写区 3 行（80 点/行）
        // 举一反三块前留 2 个 LINE_GAP：drawWriteLines 内部还会再加 1 个 LINE_GAP，
        // 若调用处只加 1 个，标签顶会落在订正末行之上造成压线（2026-08-13 布局校验发现）
        y += 2 * LINE_GAP + bodyH + LINE_GAP / 2  // "举一反三："标签
        y += 2 * WRITE_GAP                    // 手写区 2 行
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

        drawWriteLines(canvas, y, bodyPaint, label = "订正：", rows = 3)
        drawWriteLines(canvas, y + (3 * WRITE_GAP) + 2 * LINE_GAP, bodyPaint, label = "举一反三：", rows = 2)

        return bmp
    }

    /**
     * 复习友好版错题卡（2026-08-13 加）。
     *
     * 与 [build] 的区别：
     * - 顶部进度栏：复习第1/2/3次勾选格 + 日期横线（纸面维持复习节奏，App 不记任何东西）
     * - 题目图在前、订正区在纸后段，中间虚线撕纸线（可沿虚线撕开，只留题目重做）
     */
    fun buildReview(
        reason: String,
        knowledge: String,
        problemImage: Bitmap? = null,
    ): Bitmap {
        val usable = (W - MARGIN * 2)
        val titlePaint = titlePaint()
        val bodyPaint = bodyPaint()
        val titleH = titlePaint.fontMetrics.let { it.bottom - it.top }
        val bodyH = bodyPaint.fontMetrics.let { it.bottom - it.top }

        fun wrapBody(text: String) = wrap(text, bodyPaint, usable)
        val reasonLines = wrapBody("错因：$reason")
        val knowLines = wrapBody("知识点：$knowledge")
        val reasonH = reasonLines.size * bodyH
        val knowH = knowLines.size * bodyH
        val tearH = bodyH + 12f   // 撕纸线：上方剪刀字样(bodyH) + 下方间距

        // ── 量总高 ──
        var y = MARGIN
        y += titleH + LINE_GAP * 2f / 3
        y += bodyH + LINE_GAP                    // 进度栏一行
        if (problemImage != null) {
            y += (problemImage.height * usable / problemImage.width) + LINE_GAP
        }
        y += tearH                                // 撕纸线（题目区之后）
        y += reasonH + LINE_GAP
        y += knowH + LINE_GAP
        y += bodyH + LINE_GAP / 2                 // "订正："标签
        y += 3 * WRITE_GAP
        y += 2 * LINE_GAP + bodyH + LINE_GAP / 2  // "举一反三："标签（前留 2 个 LINE_GAP，同标准卡）
        y += 2 * WRITE_GAP
        val height = (y + MARGIN).toInt().coerceAtLeast(1)

        // ── 绘制 ──
        val bmp = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        y = MARGIN - titlePaint.fontMetrics.ascent
        canvas.drawText("★ 错题卡·复习版", MARGIN, y, titlePaint)
        y += titleH + LINE_GAP * 2f / 3

        // 进度栏：复习 □第1次____ □第2次____ □第3次____
        drawProgressBar(canvas, y, bodyPaint)
        y += bodyH + LINE_GAP

        if (problemImage != null) {
            val imgH = maxOf(1, (problemImage.height * usable / problemImage.width).toInt())
            val scaled = Bitmap.createScaledBitmap(problemImage, usable.toInt(), imgH, true)
            canvas.drawBitmap(scaled, MARGIN, y, null)
            y += imgH + LINE_GAP
        }

        // 撕纸线：题目区与订正区之间
        drawTearLine(canvas, y, bodyPaint)
        y += tearH

        fun drawLines(texts: List<String>) {
            for (t in texts) {
                canvas.drawText(t, MARGIN, y - bodyPaint.fontMetrics.ascent, bodyPaint)
                y += bodyH
            }
        }
        drawLines(reasonLines)
        y += LINE_GAP
        drawLines(knowLines)

        drawWriteLines(canvas, y, bodyPaint, label = "订正：", rows = 3)
        drawWriteLines(canvas, y + (3 * WRITE_GAP) + 2 * LINE_GAP, bodyPaint, label = "举一反三：", rows = 2)

        return bmp
    }

    /**
     * 重做卷（2026-08-13 加）：选 N 张错题图 → 题目区在前、订正区在后的卷子格式。
     *
     * 布局：标题 → N 张题目图顺序拼排（等比缩到整幅宽）→ 撕纸线 → 订正区（N 行手写横线）。
     * 学生沿撕纸线撕开，只留题目区遮答案重做。
     */
    fun buildReworkSheet(images: List<Bitmap>): Bitmap {
        require(images.isNotEmpty()) { "重做卷需要至少 1 张题目图" }
        val usable = (W - MARGIN * 2)
        val titlePaint = titlePaint()
        val bodyPaint = bodyPaint()
        val titleH = titlePaint.fontMetrics.let { it.bottom - it.top }
        val bodyH = bodyPaint.fontMetrics.let { it.bottom - it.top }
        val tearH = bodyH + 12f
        val imgHs = images.map { maxOf(1, (it.height * usable / it.width).toInt()) }

        // ── 量总高 ──
        var y = MARGIN
        y += titleH + LINE_GAP * 2f / 3
        y += imgHs.sum() + LINE_GAP * images.size   // 题目图 + 图间间距
        y += tearH
        y += bodyH + LINE_GAP / 2                    // "订正："标签
        y += images.size * WRITE_GAP                  // 每题一行手写区
        val height = (y + MARGIN).toInt().coerceAtLeast(1)

        // ── 绘制 ──
        val bmp = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        y = MARGIN - titlePaint.fontMetrics.ascent
        canvas.drawText("★ 重做卷（${images.size} 题）", MARGIN, y, titlePaint)
        y += titleH + LINE_GAP * 2f / 3

        // 题目区：顺序拼排
        images.forEach { img ->
            val imgH = maxOf(1, (img.height * usable / img.width).toInt())
            canvas.drawBitmap(Bitmap.createScaledBitmap(img, usable.toInt(), imgH, true), MARGIN, y, null)
            y += imgH + LINE_GAP
        }

        drawTearLine(canvas, y, bodyPaint)
        y += tearH

        // 订正区：N 行横线，左侧带序号
        canvas.drawText("订正：", MARGIN, y - bodyPaint.fontMetrics.ascent, bodyPaint)
        y += bodyH + LINE_GAP / 2
        val linePaint = linePaint()
        images.indices.forEach { i ->
            val lineY = y + WRITE_GAP - 4f
            canvas.drawText("${i + 1}.", MARGIN, lineY - 8f, bodyPaint)
            canvas.drawLine(MARGIN + bodyPaint.measureText("1.") + 6f, lineY, (W - MARGIN), lineY, linePaint)
            y += WRITE_GAP
        }

        return bmp
    }

    // ═══════════════════ 共享绘制 ═══════════════════

    /** 进度栏：复习 □第1次____ □第2次____ □第3次____（勾选格 + 日期横线） */
    private fun drawProgressBar(canvas: Canvas, topY: Float, bodyPaint: Paint) {
        val baseline = topY - bodyPaint.fontMetrics.ascent
        canvas.drawText("复习", MARGIN, baseline, bodyPaint)
        var x = MARGIN + bodyPaint.measureText("复习") + 10f
        val boxSize = 14f
        val linePaint = linePaint()
        repeat(3) { i ->
            // 勾选方格
            canvas.drawRect(x, baseline - boxSize, x + boxSize, baseline, linePaint)
            x += boxSize + 5f
            // 第 N 次
            val label = "第${i + 1}次"
            canvas.drawText(label, x, baseline, bodyPaint)
            x += bodyPaint.measureText(label) + 5f
            // 日期横线
            canvas.drawLine(x, baseline - 4f, x + 34f, baseline - 4f, linePaint)
            x += 34f + 12f
        }
    }

    /** 撕纸线：虚线 + 中间"✂ 沿此撕开"字样 */
    private fun drawTearLine(canvas: Canvas, y: Float, bodyPaint: Paint) {
        val text = "✂ 沿此撕开"
        canvas.drawText(text, (W - bodyPaint.measureText(text)) / 2f, y - 8f, bodyPaint)
        canvas.drawLine(MARGIN, y, W - MARGIN, y, dashPaint())
    }

    /** 标签 + 手写横线区（行高 80 点 ≈ 10mm；练习本风格线在行底） */
    private fun drawWriteLines(canvas: Canvas, topY: Float, bodyPaint: Paint, label: String, rows: Int) {
        var y = topY
        y += LINE_GAP
        canvas.drawText(label, MARGIN, y - bodyPaint.fontMetrics.ascent, bodyPaint)
        y += bodyPaint.fontMetrics.let { it.bottom - it.top } + LINE_GAP / 2
        val linePaint = linePaint()
        repeat(rows) {
            val lineY = y + WRITE_GAP - 4f
            canvas.drawLine(MARGIN, lineY, (W - MARGIN), lineY, linePaint)
            y += WRITE_GAP
        }
    }
}
