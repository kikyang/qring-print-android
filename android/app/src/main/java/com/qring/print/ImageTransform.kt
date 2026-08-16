package com.qring.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix

/**
 * 照片旋转 + 缩放（2026-08-14 加）。
 *
 * 关键约束：底层 [RasterEncoder.extractGrayPublic] 恒把位图缩到 384 宽，所以
 * 缩放必须在 Bitmap 层完成（否则被底层采样抵消）。本工具保证输出恒 384 宽白底位图：
 * - 缩放 >100%：放大后居中裁掉两侧（细节放大）
 * - 缩放 <100%：缩小后白底居中，两侧留白
 * - 缩放 =100% 且已是 384 宽：返回原实例（默认零回归，与旧行为完全一致）
 */
object ImageTransform {

    const val SCALE_MIN = 50
    const val SCALE_MAX = 200

    /** 旋转 0/90/180/270；0 度短路返回原实例（不产生新分配） */
    fun rotate(bmp: Bitmap, deg: Int): Bitmap {
        val d = ((deg % 360) + 360) % 360
        if (d == 0) return bmp
        val m = Matrix().apply { postRotate(d.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /**
     * 缩放到 384 宽（比例按 [SCALE_MIN]~[SCALE_MAX] 夹取）。
     * >100% 中心裁边；<100% 白底居中留白；=100% 且宽已 384 时原样返回。
     */
    fun fitToPaperWidth(bmp: Bitmap, scalePercent: Int): Bitmap {
        val scale = scalePercent.coerceIn(SCALE_MIN, SCALE_MAX)
        if (scale == 100 && bmp.width == WIDTH_DOTS) return bmp
        val cw = WIDTH_DOTS * scale / 100
        val sh = maxOf(1, (bmp.height.toLong() * cw / maxOf(bmp.width, 1)).toInt())
        val scaled = Bitmap.createScaledBitmap(bmp, cw, sh, true)
        return when {
            cw == WIDTH_DOTS -> scaled
            cw > WIDTH_DOTS -> {
                // 放大后居中裁 384 宽（细节放大）
                val left = (cw - WIDTH_DOTS) / 2
                Bitmap.createBitmap(scaled, left, 0, WIDTH_DOTS, sh)
            }
            else -> {
                // 缩小后白底水平居中，两侧留白
                val out = Bitmap.createBitmap(WIDTH_DOTS, sh, Bitmap.Config.ARGB_8888)
                val cv = Canvas(out)
                cv.drawColor(Color.WHITE)
                cv.drawBitmap(scaled, ((WIDTH_DOTS - cw) / 2).toFloat(), 0f, null)
                out
            }
        }
    }

    /** 旋转 → 缩放（组合入口） */
    fun apply(bmp: Bitmap, rotationDeg: Int, scalePercent: Int): Bitmap =
        fitToPaperWidth(rotate(bmp, rotationDeg), scalePercent)
}
