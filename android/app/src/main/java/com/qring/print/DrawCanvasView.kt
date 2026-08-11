package com.qring.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View

/**
 * 画布涂鸦（2026-08-11 借鉴 lztttt/QrintPrint-Android 的 CanvasView）。
 *
 * 白底黑笔手指绘图，路径列表管理（清空/撤销），内容转 384 宽位图后
 * 进入图片打印通道（与图片打印合并：可拼接/预览/打印）。
 */
class DrawCanvasView(context: Context) : View(context) {

    private val paths = ArrayList<Path>()
    private val current = Path()
    private var drawing = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 14f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        for (p in paths) canvas.drawPath(p, paint)
        if (drawing) canvas.drawPath(current, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                current.reset()
                current.moveTo(x, y)
                drawing = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                current.lineTo(x, y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                paths.add(Path(current))  // Path(Path) 复制，后续修改不影响已存笔画
                current.reset()
                drawing = false
                invalidate()
                return true
            }
        }
        return true
    }

    fun undo() {
        if (paths.isNotEmpty()) {
            paths.removeAt(paths.size - 1)
            invalidate()
        }
    }

    fun clear() {
        paths.clear()
        current.reset()
        invalidate()
    }

    /** 画布内容 → 384 宽位图（白底黑笔，等比缩放） */
    fun toBitmap(): Bitmap {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val outW = WIDTH_DOTS
        val outH = (h.toLong() * outW / w).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val scale = outW.toFloat() / w
        canvas.scale(scale, scale)
        for (p in paths) canvas.drawPath(p, paint)
        return bmp
    }
}
