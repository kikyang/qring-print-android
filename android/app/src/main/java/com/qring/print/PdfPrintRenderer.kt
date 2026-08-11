package com.qring.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * PDF → 384 点宽位图（移植自 xyprt QuickPrintRenderer.pdf，2026-08-11）。
 *
 * 用系统 PdfRenderer（零依赖）逐页渲染：768px 宽（RENDER_MODE_FOR_PRINT）
 * → 自动裁白边 → 缩到 376 宽（384 纸宽留 4px 边）→ 页间 16px 拼接。
 *
 * 限制（同 xyprt）：最多 20 页、累计页高上限 58976，超出部分丢弃；
 * 中间位图每页渲染完立即 recycle 控内存。
 */
object PdfPrintRenderer {

    const val PDF_RENDER_WIDTH = 768
    const val CONTENT_WIDTH = 376          // 384 纸宽留 4px 边
    const val PAGE_GAP = 16
    const val MAX_PAGES = 20
    const val MAX_HEIGHT = 58976           // 累计页高（含 gap）上限
    const val RENDER_MODE = 2              // RENDER_MODE_FOR_PRINT

    /**
     * 渲染 PDF 为一张拼接位图（384 宽）。
     * @throws IllegalStateException 无法打开 / 没有可打印页面
     */
    fun renderToBitmap(context: Context, uri: Uri, rotationDegrees: Int = 0): Bitmap {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("无法打开 PDF 文件")
        return render(pfd, rotationDegrees)
    }

    /**
     * 自检/内部文件用（contentResolver 不吃 file://）：直接开本地文件。
     */
    fun renderFromFile(file: java.io.File, rotationDegrees: Int = 0): Bitmap {
        val pfd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        return render(pfd, rotationDegrees)
    }

    private fun render(pfd: android.os.ParcelFileDescriptor, rotationDegrees: Int): Bitmap {
        val parts = ArrayList<Bitmap>()
        try {
            PdfRenderer(pfd).use { renderer ->
                val pageCount = minOf(renderer.pageCount, MAX_PAGES)
                var acc = 0
                for (i in 0 until pageCount) {
                    val page = renderer.openPage(i)
                    try {
                        val w = PDF_RENDER_WIDTH
                        val h = max(1, (page.height.toFloat() * w / page.width).roundToInt())
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        try {
                            val canvas = Canvas(bmp)
                            canvas.drawColor(Color.WHITE)
                            page.render(bmp, null, null, RENDER_MODE)
                            val cropped = cropWhiteMargins(bmp)
                            bmp.recycle()
                            val rotated = rotate(cropped, rotationDegrees)
                            if (rotated !== cropped) cropped.recycle()
                            val fitted = fitToPaper(rotated)
                            if (fitted !== rotated) rotated.recycle()
                            // 总高上限：超出则丢弃本页及后续
                            if (acc + fitted.height + PAGE_GAP > MAX_HEIGHT) break
                            parts.add(fitted)
                            acc += fitted.height + PAGE_GAP
                        } catch (e: Exception) {
                            bmp.recycle()
                            throw e
                        }
                    } finally {
                        page.close()
                    }
                }
            }
        } finally {
            pfd.close()
        }
        if (parts.isEmpty()) throw IllegalStateException("PDF 没有可打印页面")
        return stack(parts)
    }

    /** 隔行隔列采样找内容边界（通道 <246 视为有内容），四周留 pad，≤94% 才裁 */
    private fun cropWhiteMargins(bmp: Bitmap): Bitmap {
        val w = bmp.width
        val h = bmp.height
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h step 2) {
            for (x in 0 until w step 2) {
                val p = bmp.getPixel(x, y)
                if (((p shr 16) and 0xFF) < 246 || ((p shr 8) and 0xFF) < 246 || (p and 0xFF) < 246) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return bmp // 全白页
        val padX = max((w * 0.018).roundToInt(), 8)
        val padY = max((h * 0.012).roundToInt(), 8)
        val left = (minX - padX).coerceAtLeast(0)
        val top = (minY - padY).coerceAtLeast(0)
        val right = (maxX + padX).coerceAtMost(w - 1)
        val bottom = (maxY + padY).coerceAtMost(h - 1)
        val cw = right - left + 1
        val ch = bottom - top + 1
        // 裁剪后尺寸 ≤ 原图 94% 才裁，否则原样返回（避免小留白也裁导致抖动）
        return if (cw <= w * 94 / 100 || ch <= h * 94 / 100) {
            Bitmap.createBitmap(bmp, left, top, cw, ch)
        } else {
            bmp
        }
    }

    private fun rotate(bmp: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return bmp
        val m = android.graphics.Matrix().apply { postRotate(d.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /** 缩到 376 宽，放进 384×h+8 白底画布（水平居中、垂直 4px 偏移） */
    private fun fitToPaper(src: Bitmap): Bitmap {
        val targetW = CONTENT_WIDTH
        val targetH = max(1, (src.height.toLong() * targetW / max(src.width, 1)).toInt())
        val scaled = Bitmap.createScaledBitmap(src, targetW, targetH, true)
        val out = Bitmap.createBitmap(WIDTH_DOTS, targetH + 8, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val xOnPaper = (WIDTH_DOTS - targetW) / 2
        canvas.drawBitmap(scaled, xOnPaper.toFloat(), 4f, null)
        if (scaled !== src) scaled.recycle()
        return out
    }

    /** 垂直拼接，页间 PAGE_GAP，边画边回收 */
    private fun stack(parts: List<Bitmap>): Bitmap {
        if (parts.size == 1) return parts[0]
        var totalH = parts.sumOf { it.height } + PAGE_GAP * (parts.size - 1)
        totalH = minOf(totalH, MAX_HEIGHT)
        val out = Bitmap.createBitmap(WIDTH_DOTS, totalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        var y = 0
        for (bmp in parts) {
            if (y + bmp.height > totalH) {
                bmp.recycle()
                continue
            }
            canvas.drawBitmap(bmp, 0f, y.toFloat(), null)
            y += bmp.height + PAGE_GAP
            bmp.recycle()
        }
        return out
    }
}
