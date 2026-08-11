package com.qring.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * 常用模板库（2026-08-11 加，仿喵喵机/印先森的模板打印）：
 * 课程表 / 单词表 / 每日计划。纯本地绘制，384 点宽，
 * 生成 Bitmap 后走图片通道（行合并 + m=2）打印。
 */
object TemplateLibrary {

    private const val W = WIDTH_DOTS
    private const val MARGIN = 8f

    private fun titlePaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        isFakeBoldText = true
        color = Color.BLACK
    }

    private fun bodyPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 16f
        color = Color.BLACK
    }

    private fun linePaint(): Paint = Paint().apply { color = Color.BLACK; strokeWidth = 2f }

    // ── 课程表：列 = 周一到周日（7 列），行 = 节次 + 表头 ──
    fun courseTable(rows: Int = 8): Bitmap {
        val days = listOf("一", "二", "三", "四", "五", "六", "日")
        val cellW = (W - MARGIN * 2) / (days.size + 1)
        val headerH = 34f
        val cellH = 30f
        val height = (headerH + rows * cellH + MARGIN * 2).toInt()

        val bmp = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val title = titlePaint()
        val body = bodyPaint()

        var y = MARGIN
        c.drawText("★ 课程表", MARGIN, y + 22f, title)
        y += 40f

        // 表头
        val headerY = y
        c.drawText("节次", MARGIN + 6f, headerY + 22f, body)
        days.forEachIndexed { i, d ->
            c.drawText("周$d", MARGIN + (i + 1) * cellW + 8f, headerY + 22f, body)
        }
        y += headerH

        // 节次行
        for (i in 1..rows) {
            c.drawText("$i", MARGIN + 10f, y + 20f, body)
            y += cellH
        }

        // 表格线（竖线）
        for (i in 0..days.size) {
            val x = MARGIN + i * cellW
            c.drawLine(x, headerY, x, y - cellH, linePaint())
        }
        // 横线
        var ly = headerY
        for (i in 0..rows) {
            c.drawLine(MARGIN, ly, MARGIN + (days.size + 1) * cellW, ly, linePaint())
            ly += (if (i == 0) headerH else cellH)
        }
        return bmp
    }

    // ── 单词表：序号 | 英文 | 中文，空白行 ──
    fun wordList(rows: Int = 14): Bitmap {
        val lineH = 60f   // ≈7.5mm，英文书写够用（2026-08-11 用户反馈原 34 点太窄）
        val height = (MARGIN * 2 + 46 + rows * lineH).toInt()
        val bmp = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val body = bodyPaint()

        c.drawText("★ 单词表", MARGIN, MARGIN + 24f, titlePaint())
        var y = MARGIN + 46f
        for (i in 1..rows) {
            // 练习本风格：横线在格子底边，序号坐在线上方（2026-08-11 用户反馈线在行顶）
            val lineY = y + lineH - 4f
            c.drawText("$i", MARGIN + 6f, lineY - 10f, body)
            c.drawLine(MARGIN + 70f, lineY, MARGIN + 200f, lineY, linePaint())
            c.drawLine(MARGIN + 214f, lineY, W - MARGIN, lineY, linePaint())
            y += lineH
        }
        return bmp
    }

    // ── 每日计划：时间 + 事项横线 ──
    fun dailyPlan(rows: Int = 10): Bitmap {
        val lineH = 80f   // ≈10mm，中文手写够用（2026-08-11 用户反馈原 38 点太窄）
        val height = (MARGIN * 2 + 46 + rows * lineH).toInt()
        val bmp = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val body = bodyPaint()

        c.drawText("★ 每日计划", MARGIN, MARGIN + 24f, titlePaint())
        var y = MARGIN + 46f
        val times = listOf("07:00", "08:00", "10:00", "12:00", "14:00", "16:00", "18:00", "20:00", "21:00", "22:00", "23:00", "24:00")
        for (i in 0 until rows) {
            // 练习本风格：横线在格子底边，时间坐在线上方
            val lineY = y + lineH - 4f
            c.drawText(times.getOrElse(i) { "${8 + i * 2}:00" }, MARGIN + 4f, lineY - 10f, body)
            c.drawLine(MARGIN + 76f, lineY, W - MARGIN, lineY, linePaint())
            y += lineH
        }
        return bmp
    }
}
