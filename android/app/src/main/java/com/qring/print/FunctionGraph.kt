package com.qring.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * 函数图像渲染（2026-08-27 加，函数图像打印用）。
 *
 * 把 y=f(x) 在 [xMin,xMax] 的曲线渲染到 384 宽位图：网格 + 刻度数字 + 坐标轴 + 曲线。
 * 曲线逐点采样，遇非有限值或大跳变（渐近线）断线，避免画满全图的假连线。
 * 返回白底黑线位图，交给 RasterEncoder.encode（NONE + THRESHOLD_TEXT）走模板打印链路。
 */
object FunctionGraph {

    const val WIDTH = 384

    /** 渲染函数图像。@throws Exception 表达式非法 / X 范围错误 / 范围内无有效值 */
    fun render(expr: String, xMin: Double, xMax: Double, height: Int = 384): Bitmap {
        ExpressionEvaluator.validate(expr)?.let { throw IllegalArgumentException("表达式错误：$it") }
        if (!xMin.isFinite() || !xMax.isFinite() || xMin >= xMax) {
            throw IllegalArgumentException("X 范围错误：起点需小于终点")
        }

        // 采样求 y 范围（2000 点，丢弃非有限值）
        val N = 2000
        val xs = DoubleArray(N)
        val ys = DoubleArray(N)
        var finite = 0
        var yLo = Double.POSITIVE_INFINITY
        var yHi = Double.NEGATIVE_INFINITY
        for (k in 0 until N) {
            val x = xMin + (xMax - xMin) * k / (N - 1)
            val y = try { ExpressionEvaluator.evaluate(expr, x) } catch (e: Exception) { Double.NaN }
            xs[k] = x
            ys[k] = y
            if (y.isFinite()) {
                finite++
                if (y < yLo) yLo = y
                if (y > yHi) yHi = y
            }
        }
        if (finite == 0) throw IllegalArgumentException("函数在所选范围没有有效值")

        var pad = (yHi - yLo) * 0.08
        if (pad <= 0) pad = maxOf(1.0, abs(yHi) * 0.1)
        val yMin = yLo - pad
        val yMax = yHi + pad

        // 绘图区边距（左留刻度数字，下留 X 轴数字）
        val left = 34.0; val right = 12.0; val top = 12.0; val bottom = 30.0
        val plotW = WIDTH - left - right
        val plotH = height - top - bottom

        val bmp = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val cv = Canvas(bmp)
        val gridPaint = Paint().apply { color = 0xFFD0D0D0.toInt(); strokeWidth = 1f }
        val axisPaint = Paint().apply { color = 0xFF404040.toInt(); strokeWidth = 1.5f }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF606060.toInt(); textSize = 11f
        }
        val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = 2f
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }

        fun px(x: Double) = left + (x - xMin) / (xMax - xMin) * plotW
        fun py(y: Double) = top + (yMax - y) / (yMax - yMin) * plotH

        // 网格 + 刻度（nice step：约 6 个区间）
        val stepX = niceStep(xMax - xMin, 6)
        val stepY = niceStep(yMax - yMin, 6)
        var gx = ceil(xMin / stepX) * stepX
        while (gx <= xMax) {
            val pxv = px(gx)
            cv.drawLine(pxv.toFloat(), top.toFloat(), pxv.toFloat(), (top + plotH).toFloat(), gridPaint)
            drawTick(cv, textPaint, fmt(gx), pxv.toFloat(), (top + plotH + 5).toFloat(), Paint.Align.CENTER)
            gx += stepX
        }
        var gy = ceil(yMin / stepY) * stepY
        while (gy <= yMax) {
            val pyv = py(gy)
            cv.drawLine(left.toFloat(), pyv.toFloat(), (left + plotW).toFloat(), pyv.toFloat(), gridPaint)
            drawTick(cv, textPaint, fmt(gy), (left - 5).toFloat(), pyv.toFloat() + 4f, Paint.Align.RIGHT)
            gy += stepY
        }

        // 坐标轴（0 线在范围内才画）
        if (xMin <= 0 && xMax >= 0) {
            cv.drawLine(px(0.0).toFloat(), top.toFloat(), px(0.0).toFloat(), (top + plotH).toFloat(), axisPaint)
        }
        if (yMin <= 0 && yMax >= 0) {
            cv.drawLine(left.toFloat(), py(0.0).toFloat(), (left + plotW).toFloat(), py(0.0).toFloat(), axisPaint)
        }
        // 边框
        cv.drawRect(left.toFloat(), top.toFloat(), (left + plotW).toFloat(), (top + plotH).toFloat(), axisPaint)

        // 曲线：逐段连线（2000 段 drawLine，Robolectric LEGACY 下可靠渲染），
        // 遇非有限 / 大跳变 / 水平出界断开（不连线 = 渐近线留白）
        var prevX = Double.NaN
        var prevY = Double.NaN
        for (k in 0 until N) {
            val y = ys[k]
            if (!y.isFinite()) { prevX = Double.NaN; prevY = Double.NaN; continue }
            val xPx = px(xs[k])
            val yPx = py(y)
            if (xPx < left - 2 || xPx > left + plotW + 2) { prevX = Double.NaN; prevY = Double.NaN; continue }
            if (!prevY.isNaN() && abs(yPx - prevY) > plotH * 0.5) { prevX = Double.NaN; prevY = Double.NaN; continue }
            if (!prevX.isNaN() && !prevY.isNaN()) {
                cv.drawLine(prevX.toFloat(), prevY.toFloat(), xPx.toFloat(), yPx.toFloat(), curvePaint)
            }
            prevX = xPx
            prevY = yPx
        }
        return bmp
    }

    /** 刻度数字（约 6 个区间 → nice 步长） */
    private fun niceStep(range: Double, target: Int): Double {
        if (range <= 0 || !range.isFinite()) return 1.0
        val rough = range / target
        if (rough <= 0) return 1.0
        val mag = 10.0.pow(floor(log10(rough)))
        val f = rough / mag
        val nice = when {
            f <= 1.0 -> 1.0
            f <= 2.0 -> 2.0
            f <= 5.0 -> 5.0
            else -> 10.0
        }
        return nice * mag
    }

    private fun fmt(v: Double): String {
        if (v == 0.0) return "0"
        if (abs(v) >= 1e6 || abs(v) < 1e-4) return "%.1e".format(v)
        var s = "%.6g".format(v)
        if (s.contains('.') && !s.contains('e')) s = s.trimEnd('0').trimEnd('.')
        return s
    }

    private fun drawTick(cv: Canvas, paint: Paint, text: String, x: Float, y: Float, align: Paint.Align) {
        val p = Paint(paint)
        p.textAlign = align
        cv.drawText(text, x, y, p)
    }
}
