package com.qring.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * 打印自检页 —— 联调时验证打印质量与浓度档位。
 *
 * 内容：标题 → 5 个浓度档的粗横线对比 → 线条粗细测试（1/2/4/8 点）
 * → 16 级灰度渐变带 → 文字测试（字母数字/中文）。
 * 用不同厚度 cmdThickness(0-4) 各打印一页，对比选最佳浓度。
 */
object SelfTest {

    private const val W = WIDTH_DOTS

    /**
     * 生成自检页位图（一页）。打印时用指定的浓度档 thickness 即可对比。
     */
    fun build(): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val titlePaint = Paint(paint).apply {
            textSize = 30f; isFakeBoldText = true; color = Color.BLACK
        }
        val bodyPaint = Paint(paint).apply { textSize = 20f; color = Color.BLACK }
        val fm = bodyPaint.fontMetrics
        val lineH = (fm.bottom - fm.top).toInt()

        // ── 量高 ──
        val margin = 12f
        var y = margin
        y += (titlePaint.fontMetrics.bottom - titlePaint.fontMetrics.top).toInt()
        y += lineH * 2 + 16          // 两个标签行
        y += 4 * 4 + 6               // 浓度测试线（4 条 6px 粗线，单页浓度固定）
        y += lineH + 8               // 线条粗细标签
        y += (2 + 4 + 8 + 16) * 2 + 8  // 粗细线组（各两行）
        y += lineH + 8               // 渐变标签
        y += 32 + 8                  // 渐变带
        y += lineH * 3               // 文字测试行
        val height = (y + margin).toInt()

        val bmp = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        y = margin - titlePaint.fontMetrics.ascent
        canvas.drawText("★ 打印自检页", margin.toFloat(), y, titlePaint)
        y += (titlePaint.fontMetrics.bottom - titlePaint.fontMetrics.top).toInt() + 8

        // 浓度测试线：4 条 6px 粗线（单页打印浓度固定为 2——X1 已定稿浓度 2，
        // 原"浓度 0~2 各打一页对比"改单页后已无对比意义，2026-08-11）
        canvas.drawText("浓度 2 测试线（6px ×4）", margin.toFloat(), y - bodyPaint.fontMetrics.ascent, bodyPaint)
        y += lineH + 6
        val thickPaint = Paint().apply { color = Color.BLACK; strokeWidth = 6f }
        repeat(4) { row ->
            val yy = y + row * 4f
            canvas.drawLine(margin.toFloat(), yy, (W - margin).toFloat(), yy, thickPaint)
        }
        y += 4 * 4 + 6

        // 线条粗细：1/2/4/8 点
        canvas.drawText("线条粗细 1/2/4/8 点", margin.toFloat(), y - bodyPaint.fontMetrics.ascent, bodyPaint)
        y += lineH + 6
        for (t in listOf(1f, 2f, 4f, 8f)) {
            val p = Paint().apply { color = Color.BLACK; strokeWidth = t }
            canvas.drawLine(margin.toFloat(), y, (W - margin).toFloat(), y, p)
            y += (t * 2).toInt()
        }

        // 16 级灰度渐变带
        canvas.drawText("灰度渐变 0→255（16 级）", margin.toFloat(), y - bodyPaint.fontMetrics.ascent, bodyPaint)
        y += lineH + 4
        val bandW = (W - margin * 2) / 16f
        for (i in 0 until 16) {
            val gray = i * 255 / 15
            val p = Paint().apply { color = Color.rgb(gray, gray, gray) }
            canvas.drawRect(margin + i * bandW, y, margin + (i + 1) * bandW, y + 32f, p)
        }
        y += 32 + 8

        // 文字测试
        canvas.drawText("字母数字 ABCdef 0123456789 @#$%", margin.toFloat(), y - bodyPaint.fontMetrics.ascent, bodyPaint)
        y += lineH
        canvas.drawText("汉字：错题小印打印机测试", margin.toFloat(), y - bodyPaint.fontMetrics.ascent, bodyPaint)
        y += lineH
        canvas.drawText("缩进 3.5mm | 中英文混排 GOLF-2026", margin.toFloat(), y - bodyPaint.fontMetrics.ascent, bodyPaint)

        return bmp
    }
}
