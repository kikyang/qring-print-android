package com.qring.print

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import kotlin.math.min

/**
 * 手动裁剪对话框（2026-08-18 加，v0.7.2）：
 * 拖动裁剪框，支持 自由 / 1:1 / 3:4 / 4:3 比例，完成后返回裁剪位图。
 */
object ImageCropDialog {

    fun show(context: Context, source: Bitmap, onResult: (Bitmap) -> Unit) {
        val cropView = CropView(context, source)
        val ratioGroup = Design.segmentGroup(
            listOf("自由" to 0f, "1:1" to 1f, "3:4" to 3f / 4f, "4:3" to 4f / 3f),
            defaultIndex = 0,
        ) { i ->
            val ratio = listOf(0f, 1f, 3f / 4f, 4f / 3f)[i]
            cropView.setRatio(ratio)
        }
        val resetBtn = Design.ghostButton("↺ 重置")
        resetBtn.setOnClickListener { cropView.reset() }
        val cancelBtn = Design.outlineButton("取消")
        val okBtn = Design.primaryButton("✅ 完成")
        okBtn.setOnClickListener {
            val cropped = cropView.crop()
            if (cropped != null) onResult(cropped)
            (okBtn.tag as? AlertDialog)?.dismiss()
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(resetBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(cancelBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Design.dp(8) })
            addView(okBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Design.dp(8) })
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Design.dp(12), Design.dp(12), Design.dp(12), Design.dp(12))
            addView(cropView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Design.dp(420)))
            addView(ratioGroup)
            addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Design.dp(8) })
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("裁剪图片")
            .setView(container)
            .setNegativeButton("取消", null)
            .create()
        okBtn.tag = dialog
        cancelBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private class CropView(context: Context, private val source: Bitmap) : View(context) {
        private val imageRect = RectF()
        private val cropRect = RectF()
        private val overlayPaint = Paint().apply { color = 0x99000000.toInt() }
        private val borderPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = Design.dp(2).toFloat()
        }
        private val gridPaint = Paint().apply {
            color = 0x66FFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = Design.dp(1).toFloat()
        }
        private var ratio = 0f
        private var mode = 0
        private var lastX = 0f
        private var lastY = 0f

        init { reset() }

        fun reset() {
            cropRect.set(0f, 0f, 1f, 1f)
            ratio = 0f
            invalidate()
        }

        fun setRatio(r: Float) {
            ratio = r
            if (r <= 0f) {
                cropRect.set(0f, 0f, 1f, 1f)
            } else {
                val cx = cropRect.centerX()
                val cy = cropRect.centerY()
                var w = cropRect.width()
                var h = w / r
                if (h > 1f) { h = 1f; w = h * r }
                var left = cx - w / 2
                var top = cy - h / 2
                left = left.coerceIn(0f, 1f - w)
                top = top.coerceIn(0f, 1f - h)
                cropRect.set(left, top, left + w, top + h)
            }
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val pad = Design.dp(8).toFloat()
            val availW = w - pad * 2
            val availH = h - pad * 2
            val scale = min(availW / source.width, availH / source.height)
            val dw = source.width * scale
            val dh = source.height * scale
            val left = (w - dw) / 2
            val top = (h - dh) / 2
            imageRect.set(left, top, left + dw, top + dh)
            cropRect.set(0f, 0f, 1f, 1f)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(source, null, imageRect, Paint(Paint.FILTER_BITMAP_FLAG))
            val r = RectF(
                imageRect.left + cropRect.left * imageRect.width(),
                imageRect.top + cropRect.top * imageRect.height(),
                imageRect.left + cropRect.right * imageRect.width(),
                imageRect.top + cropRect.bottom * imageRect.height(),
            )
            canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, r.top, overlayPaint)
            canvas.drawRect(imageRect.left, r.bottom, imageRect.right, imageRect.bottom, overlayPaint)
            canvas.drawRect(imageRect.left, r.top, r.left, r.bottom, overlayPaint)
            canvas.drawRect(r.right, r.top, imageRect.right, r.bottom, overlayPaint)
            canvas.drawRect(r, borderPaint)
            for (i in 1..2) {
                val x = r.left + r.width() * i / 3
                val y = r.top + r.height() * i / 3
                canvas.drawLine(x, r.top, x, r.bottom, gridPaint)
                canvas.drawLine(r.left, y, r.right, y, gridPaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y
            val r = RectF(
                imageRect.left + cropRect.left * imageRect.width(),
                imageRect.top + cropRect.top * imageRect.height(),
                imageRect.left + cropRect.right * imageRect.width(),
                imageRect.top + cropRect.bottom * imageRect.height(),
            )
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mode = hitMode(r, x, y)
                    lastX = x; lastY = y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = x - lastX
                    val dy = y - lastY
                    val dw = imageRect.width()
                    val dh = imageRect.height()
                    when (mode) {
                        1 -> {
                            var nl = (cropRect.left + dx / dw).coerceIn(0f, cropRect.right - 0.05f)
                            if (ratio > 0f) {
                                val nh = (cropRect.right - nl) / ratio
                                if (nh <= 1f) {
                                    var nt = cropRect.top + (cropRect.height() - nh) / 2
                                    nt = nt.coerceIn(0f, 1f - nh)
                                    cropRect.set(nl, nt, cropRect.right, nt + nh)
                                }
                            } else cropRect.left = nl
                        }
                        2 -> {
                            var nt = (cropRect.top + dy / dh).coerceIn(0f, cropRect.bottom - 0.05f)
                            if (ratio > 0f) {
                                val nw = (cropRect.bottom - nt) * ratio
                                if (nw <= 1f) {
                                    var nl = cropRect.left + (cropRect.width() - nw) / 2
                                    nl = nl.coerceIn(0f, 1f - nw)
                                    cropRect.set(nl, nt, nl + nw, cropRect.bottom)
                                }
                            } else cropRect.top = nt
                        }
                        3 -> {
                            var nr = (cropRect.right + dx / dw).coerceIn(cropRect.left + 0.05f, 1f)
                            if (ratio > 0f) {
                                val nh = (nr - cropRect.left) / ratio
                                if (nh <= 1f) {
                                    var nt = cropRect.top + (cropRect.height() - nh) / 2
                                    nt = nt.coerceIn(0f, 1f - nh)
                                    cropRect.set(cropRect.left, nt, nr, nt + nh)
                                }
                            } else cropRect.right = nr
                        }
                        4 -> {
                            var nb = (cropRect.bottom + dy / dh).coerceIn(cropRect.top + 0.05f, 1f)
                            if (ratio > 0f) {
                                val nw = (nb - cropRect.top) * ratio
                                if (nw <= 1f) {
                                    var nl = cropRect.left + (cropRect.width() - nw) / 2
                                    nl = nl.coerceIn(0f, 1f - nw)
                                    cropRect.set(nl, cropRect.top, nl + nw, nb)
                                }
                            } else cropRect.bottom = nb
                        }
                        else -> {
                            val mx = dx / dw
                            val my = dy / dh
                            var nl = cropRect.left + mx
                            var nt = cropRect.top + my
                            if (nl < 0f) nl = 0f
                            if (nt < 0f) nt = 0f
                            if (nl + cropRect.width() > 1f) nl = 1f - cropRect.width()
                            if (nt + cropRect.height() > 1f) nt = 1f - cropRect.height()
                            cropRect.offsetTo(nl, nt)
                        }
                    }
                    lastX = x; lastY = y
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun hitMode(r: RectF, x: Float, y: Float): Int {
            val touch = Design.dp(16).toFloat()
            if (x < r.left + touch && y > r.top && y < r.bottom) return 1
            if (x > r.right - touch && y > r.top && y < r.bottom) return 3
            if (y < r.top + touch && x > r.left && x < r.right) return 2
            if (y > r.bottom - touch && x > r.left && x < r.right) return 4
            return 0
        }

        fun crop(): Bitmap? {
            val left = (cropRect.left * source.width).toInt().coerceIn(0, source.width - 1)
            val top = (cropRect.top * source.height).toInt().coerceIn(0, source.height - 1)
            val right = (cropRect.right * source.width).toInt().coerceIn(left + 1, source.width)
            val bottom = (cropRect.bottom * source.height).toInt().coerceIn(top + 1, source.height)
            if (right - left < 2 || bottom - top < 2) return null
            return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        }
    }
}
