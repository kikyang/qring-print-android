package com.qring.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.util.Random

/**
 * 口算题生成（2026-08-11 借鉴 lztttt/QrintPrint-Android 的 math 功能）。
 *
 * 随机生成不重复算式（加法/减法/乘法/除法/混合），2 列大字号排版成 384 宽位图，
 * 走图片通道打印（m=2 + 行合并）。答案留横线，打印后给孩子手写。
 */
object MathWorksheet {

    enum class Op(val label: String) {
        ADD("加法"),
        SUB("减法"),
        MUL("乘法"),
        DIV("除法"),
        MIX("混合"),
    }

    /**
     * 生成口算题位图（384 宽白底，标题 + 2 列算式 + 答案横线）。
     * @param count 题数（偶数最整齐；奇数最后一行单列）
     */
    fun build(op: Op, count: Int): Bitmap {
        val exprs = generate(op, count.coerceIn(2, 40))
        val fontSize = 42f
        val lineHeight = 88
        val colWidth = WIDTH_DOTS / 2
        val rows = (exprs.size + 1) / 2
        val height = 40 + rows * lineHeight + 20

        val bmp = Bitmap.createBitmap(WIDTH_DOTS, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 30f
            color = Color.BLACK
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("口算练习（${op.label}）", 20f, 42f, titlePaint)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSize
            color = Color.BLACK
        }
        val linePaint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 4f
        }
        exprs.forEachIndexed { i, e ->
            val col = i % 2
            val row = i / 2
            val x = (24 + col * colWidth).toFloat()
            val y = 100f + row * lineHeight
            val text = "$e ="
            canvas.drawText(text, x, y, paint)
            // 答案横线（算式后留白）
            val textW = paint.measureText(text)
            canvas.drawLine(x + textW + 12, y + 8, x + colWidth - 24, y + 8, linePaint)
        }
        return bmp
    }

    /** 生成不重复算式（去重，防同卷重复题） */
    private fun generate(op: Op, count: Int): List<String> {
        val rnd = Random()
        val seen = HashSet<String>()
        val out = ArrayList<String>(count)
        var guard = 0
        while (out.size < count && guard++ < count * 100) {
            val e = when (op) {
                Op.ADD -> { val a = rnd.nextInt(20) + 1; val b = rnd.nextInt(20 - a) + 1; "$a + $b" }
                Op.SUB -> { val a = rnd.nextInt(20) + 1; val b = rnd.nextInt(a + 1); "$a - $b" }
                Op.MUL -> { val a = rnd.nextInt(9) + 1; val b = rnd.nextInt(9) + 1; "$a × $b" }
                Op.DIV -> { val b = rnd.nextInt(8) + 2; val q = rnd.nextInt(9) + 1; "${b * q} ÷ $b" }
                Op.MIX -> when (rnd.nextInt(4)) {
                    0 -> { val a = rnd.nextInt(20) + 1; val b = rnd.nextInt(20 - a) + 1; "$a + $b" }
                    1 -> { val a = rnd.nextInt(20) + 1; val b = rnd.nextInt(a + 1); "$a - $b" }
                    2 -> { val a = rnd.nextInt(9) + 1; val b = rnd.nextInt(9) + 1; "$a × $b" }
                    else -> { val b = rnd.nextInt(8) + 2; val q = rnd.nextInt(9) + 1; "${b * q} ÷ $b" }
                }
            }
            if (seen.add(e)) out.add(e)
        }
        return out
    }
}
