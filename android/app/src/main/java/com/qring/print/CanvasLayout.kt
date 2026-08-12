package com.qring.print

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * 画布拖拽编辑视图（2026-08-12，合成自 bzhou830/snowboys/lztttt 画布概念）。
 *
 * - 显示逻辑坐标系 384 点宽（按控件宽度等比缩放），元素内容复用 CanvasEditor.drawElement
 * - 点击命中（自顶向下），拖动改位置（clamp 到 0..384）
 * - 选中元素画虚线框；位置变化与选中状态通过 [onSelect] 回调通知外部刷新编辑面板
 * - 不做双指缩放（工具条提供 放大/缩小 按钮，按 10% 步进）
 */
class CanvasLayout(context: Context) : View(context) {

    val elements = ArrayList<CanvasElement>()
    var selected: CanvasElement? = null
    var onSelect: ((CanvasElement?) -> Unit)? = null

    private val selectPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.rgb(7, 193, 96)
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    private var scale = 1f          // 控件宽 / 384
    private var dragging: CanvasElement? = null
    private var lastX = 0f
    private var lastY = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        scale = w / CanvasEditor.WIDTH.toFloat()
        // 高度固定 240dp（预览区），内容超高时以打印预览为准
        val h = (context.resources.displayMetrics.density * 240).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        canvas.save()
        canvas.scale(scale, scale)
        // 画布边界浅灰线（提示打印宽度）
        val border = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.LTGRAY }
        canvas.drawRect(0f, 0f, CanvasEditor.WIDTH.toFloat(), height / scale, border)
        for (el in elements) {
            CanvasEditor.drawElement(canvas, el)
            if (el == selected) {
                canvas.drawRect(el.x - 2f, el.y - 2f, el.x + el.w + 2f, el.y + el.h + 2f, selectPaint)
            }
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x / scale
                val y = event.y / scale
                val hit = elements.asReversed().firstOrNull { x in it.x..(it.x + it.w) && y in it.y..(it.y + it.h) }
                if (hit != selected) {
                    selected = hit
                    onSelect?.invoke(hit)
                    invalidate()
                }
                dragging = hit
                lastX = x
                lastY = y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val el = dragging ?: return true
                val x = event.x / scale
                val y = event.y / scale
                el.x = (el.x + (x - lastX)).coerceIn(0f, CanvasEditor.WIDTH - el.w)
                el.y = (el.y + (y - lastY)).coerceAtLeast(0f)
                lastX = x
                lastY = y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = null
                invalidate()
            }
        }
        return true
    }

    /** 追加元素（默认放底部居中），返回是否画到了边界内 */
    fun addElement(el: CanvasElement) {
        el.x = (CanvasEditor.WIDTH - el.w) / 2f
        val maxBottom = elements.maxOfOrNull { it.y + it.h } ?: 0f
        el.y = maxBottom + 8f
        elements.add(el)
        selected = el
        onSelect?.invoke(el)
        invalidate()
    }

    /** 选中元素放大/缩小 10%（不越过画布边界，不小于 MIN 尺寸） */
    fun scaleSelected(factor: Float) {
        val el = selected ?: return
        val nw = (el.w * factor).coerceAtLeast(40f)
        val nh = (el.h * factor).coerceAtLeast(24f)
        val dx = (el.w - nw) / 2
        val dy = (el.h - nh) / 2
        el.x = (el.x + dx).coerceIn(0f, CanvasEditor.WIDTH - nw)
        el.y = (el.y + dy).coerceAtLeast(0f)
        el.w = nw
        el.h = nh
        invalidate()
    }

    /** 置顶：移到列表末尾（后画者在上） */
    fun toFront() {
        val el = selected ?: return
        elements.remove(el)
        elements.add(el)
        invalidate()
    }

    fun removeSelected() {
        val el = selected ?: return
        elements.remove(el)
        selected = null
        onSelect?.invoke(null)
        invalidate()
    }

    fun clear() {
        elements.clear()
        selected = null
        onSelect?.invoke(null)
        invalidate()
    }
}
