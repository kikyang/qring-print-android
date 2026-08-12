package com.qring.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自定义画布：文字/图片/条码元素拖拽排版（2026-08-12 合成自
 * bzhou830/QringPrint uniapp、snowboys/QrintPrint-Windows、lztttt/QrintPrint-Android
 * 三方共识的画布功能；区别于 DrawCanvasView 的自由涂鸦——这里是元素排版）。
 *
 * 逻辑坐标系 384 点宽（与打印头一致），纵向无限，渲染时自动裁剪到内容底。
 * 打印走 RasterEncoder.encode(bmp, NONE, THRESHOLD_IMAGE) 阈值通道（条码页同款）。
 */
class CanvasElement(
    var kind: Int,             // 0=文字 1=图片 2=条码
    var x: Float, var y: Float, // 左上角（384 逻辑坐标）
    var w: Float, var h: Float,
) {
    var text: String = ""
    var fontSize: Float = 24f
    var bold: Boolean = false

    var image: Bitmap? = null      // 图片元素内容（不随模板持久化）

    var barcodeType: BarcodeGenerator.BarcodeType = BarcodeGenerator.TYPES[0]
    var barcodeContent: String = ""

    companion object {
        const val KIND_TEXT = 0
        const val KIND_IMAGE = 1
        const val KIND_BARCODE = 2
    }
}

object CanvasEditor {

    const val WIDTH = 384
    private const val MIN_W = 40f
    private const val MIN_H = 24f

    // ═══════════════ 渲染（画布预览/打印共用同一管线） ═══════════════

    /** 合成 384 宽位图，高度裁剪到内容底（下边缘 8 点留白） */
    fun render(elements: List<CanvasElement>): Bitmap {
        val maxBottom = (elements.maxOfOrNull { it.y + it.h } ?: 0f).coerceAtLeast(100f)
        val h = (maxBottom + 8).toInt()
        val bmp = Bitmap.createBitmap(WIDTH, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        for (el in elements) {
            canvas.save()
            canvas.clipRect(0f, 0f, WIDTH.toFloat(), h.toFloat())
            drawElement(canvas, el)
            canvas.restore()
        }
        return bmp
    }

    /** 画单个元素（384 逻辑坐标；CanvasLayout 预览时通过 canvas.scale 复用） */
    fun drawElement(canvas: Canvas, el: CanvasElement) {
        when (el.kind) {
            CanvasElement.KIND_TEXT -> {
                if (el.text.isEmpty()) return
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = el.fontSize
                    typeface = if (el.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                }
                // 按元素宽度换行，超出高度的文字截断（与预览一致）
                val lines = wrapText(el.text, el.w, paint)
                var lineY = el.y + el.fontSize
                for (line in lines) {
                    if (lineY > el.y + el.h) break
                    canvas.drawText(line, el.x, lineY, paint)
                    lineY += el.fontSize * 1.25f
                }
            }
            CanvasElement.KIND_IMAGE -> {
                val img = el.image ?: return
                val fit = fitRect(img.width, img.height, el.w, el.h)
                canvas.drawBitmap(img, null, RectF(el.x, el.y, el.x + fit.width(), el.y + fit.height()), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            CanvasElement.KIND_BARCODE -> {
                if (el.barcodeContent.isBlank()) {
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 12f }
                    canvas.drawText("[条码]", el.x + 4, el.y + el.fontSize.coerceIn(12f, 32f), p)
                    return
                }
                val bmp = runCatching { BarcodeGenerator.encodeBitmap(el.barcodeType, el.barcodeContent) }.getOrNull()
                    ?: run {
                        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; textSize = 12f }
                        canvas.drawText("[条码内容无效]", el.x + 4, el.y + 16f, p)
                        return
                    }
                val fit = fitRect(bmp.width, bmp.height, el.w, el.h)
                canvas.drawBitmap(bmp, null, RectF(el.x, el.y, el.x + fit.width(), el.y + fit.height()), Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }
    }

    /** 按可用宽度自动换行 */
    private fun wrapText(text: String, maxWidth: Float, paint: Paint): List<String> {
        val lines = ArrayList<String>()
        for (para in text.split('\n')) {
            if (para.isEmpty()) { lines.add(""); continue }
            val sb = StringBuilder()
            for (ch in para) {
                if (paint.measureText(sb.toString() + ch) > maxWidth && sb.isNotEmpty()) {
                    lines.add(sb.toString())
                    sb.clear()
                }
                sb.append(ch)
            }
            if (sb.isNotEmpty()) lines.add(sb.toString())
        }
        return lines
    }

    /** 等比缩放适配 */
    private fun fitRect(srcW: Int, srcH: Int, dstW: Float, dstH: Float): RectF {
        if (srcW <= 0 || srcH <= 0) return RectF(0f, 0f, dstW, dstH)
        val s = minOf(dstW / srcW, dstH / srcH)
        val w = srcW * s
        val h = srcH * s
        return RectF(0f, 0f, w, h)
    }

    // ═══════════════ 模板存取（SharedPreferences JSON；图片元素不持久化） ═══════════════

    private const val PREFS = "canvas_templates"
    private const val KEY_LIST = "names"
    private const val KEY_PREFIX = "tpl_"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun templateNames(context: Context): List<String> =
        prefs(context).getStringSet(KEY_LIST, emptySet())!!.sorted()

    fun saveTemplate(context: Context, name: String, elements: List<CanvasElement>): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        val arr = JSONArray()
        for (el in elements) {
            if (el.kind == CanvasElement.KIND_IMAGE) continue // 图片不可持久化（内容来自相册 URI）
            val o = JSONObject().apply {
                put("k", el.kind)
                put("x", el.x); put("y", el.y); put("w", el.w); put("h", el.h)
                put("t", el.text)
                put("f", el.fontSize)
                put("b", el.bold)
                if (el.kind == CanvasElement.KIND_BARCODE) {
                    put("bt", el.barcodeType.label)
                    put("bc", el.barcodeContent)
                }
            }
            arr.put(o)
        }
        if (arr.length() == 0) return false
        prefs(context).edit().apply {
            putString(KEY_PREFIX + n, arr.toString())
            val names = templateNames(context).toMutableSet().apply { add(n) }
            putStringSet(KEY_LIST, names)
        }.commit()
        return true
    }

    fun loadTemplate(context: Context, name: String): List<CanvasElement> {
        val raw = prefs(context).getString(KEY_PREFIX + name, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<CanvasElement>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val el = CanvasElement(
                o.optInt("k", CanvasElement.KIND_TEXT),
                o.optDouble("x", 0.0).toFloat(), o.optDouble("y", 0.0).toFloat(),
                o.optDouble("w", 300.0).toFloat(), o.optDouble("h", 40.0).toFloat(),
            )
            el.text = o.optString("t")
            el.fontSize = o.optDouble("f", 24.0).toFloat()
            el.bold = o.optBoolean("b")
            if (el.kind == CanvasElement.KIND_BARCODE) {
                el.barcodeType = BarcodeGenerator.TYPES.firstOrNull { it.label == o.optString("bt") }
                    ?: BarcodeGenerator.TYPES[0]
                el.barcodeContent = o.optString("bc")
            }
            out.add(el)
        }
        return out
    }

    fun deleteTemplate(context: Context, name: String) {
        prefs(context).edit().apply {
            remove(KEY_PREFIX + name)
            val names = templateNames(context).toMutableSet().apply { remove(name) }
            putStringSet(KEY_LIST, names)
        }.commit()
    }
}
