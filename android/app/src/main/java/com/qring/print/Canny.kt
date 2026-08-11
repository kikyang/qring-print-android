package com.qring.print

import kotlin.math.abs
import kotlin.math.pow

/**
 * Canny 边缘检测（移植自 xyprt/Canny，2026-08-11，反编译逐行翻译）。
 *
 * 关键细节（复刻时必须保持）：
 * - 梯度幅度用**平方和** gx²+gy²（无 sqrt），双阈值也基于平方值
 * - Sobel 越界 clamp 取边缘；NMS 越界邻居取 0（两者不同！）
 * - 透明像素（!isGlyph）亮度强制 255、不进 ridge、不做阈值——永远不会成为边缘
 * - 阈值随 sensitivity(0..100) 指数式降低：s 越大边缘越多
 *
 * true = 黑点（打印墨点）。
 */
object Canny {

    private const val NOISE_FLOOR = 50f

    /**
     * @param sensitivity 0..100，默认 88（xyprt 默认）：99 百分位 × pow(0.010714286, s/100) × 0.28
     * @param thickness 线宽 1..3（经 [Outline.thicken] 膨胀）
     * @param smooth 先 Morphology.smooth（prune×2 + despeckle<5）
     */
    fun detect(
        argb: IntArray,
        isGlyph: BooleanArray,
        width: Int,
        height: Int,
        sensitivity: Int,
        thickness: Int,
        smooth: Boolean = false,
    ): BooleanArray {
        val n = width * height

        // (1) 亮度：仅 isGlyph 像素参与，非 glyph 强制 255
        val lum = FloatArray(n)
        for (i in 0 until n) {
            lum[i] = if (isGlyph[i]) {
                val c = argb[i]
                ((c shr 16) and 0xFF) * 0.299f + ((c shr 8) and 0xFF) * 0.587f + (c and 0xFF) * 0.114f
            } else {
                255f
            }
        }

        // (2) 高斯模糊：3x3 可分离核 [1,2,1]/4，边界复制（clamp）
        val blur = gaussian3(lum, width, height)

        // (3) Sobel 梯度（越界 clamp）+ 平方幅度 + 方向量化
        val mag2 = FloatArray(n)
        val dir = IntArray(n)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val gx = sobelX(blur, x, y, width, height)
                val gy = sobelY(blur, x, y, width, height)
                val i = y * width + x
                mag2[i] = gx * gx + gy * gy
                dir[i] = quantizeDir(gx, gy)
            }
        }

        // (4) 非极大值抑制（邻居越界取 0 → 图像边界像素天然通过）
        val nms = FloatArray(n)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val m = mag2[i]
                if (m <= 0f) continue
                val (dx, dy) = when (dir[i]) {
                    0 -> 1 to 0      // 梯度近水平 → 水平方向比较
                    1 -> 1 to -1     // 反对角
                    2 -> 0 to 1      // 梯度近垂直 → 垂直方向比较
                    else -> 1 to 1   // 对角
                }
                if (m >= magAt(mag2, x + dx, y + dy, width, height) &&
                    m >= magAt(mag2, x - dx, y - dy, width, height)
                ) {
                    nms[i] = m
                }
            }
        }

        // (5) 自适应双阈值（基于幅度直方图 99 百分位）
        val base = BooleanArray(n)
        val ridge = ArrayList<Float>(n / 2)
        for (i in 0 until n) {
            if (isGlyph[i] && nms[i] > 0f) ridge.add(nms[i])
        }
        if (ridge.isNotEmpty()) {
            ridge.sort()
            val s = sensitivity.coerceIn(0, 100)
            val strongRef = ridge[((ridge.size - 1) * 0.99f).toInt().coerceIn(0, ridge.size - 1)]
            val hiFrac = 0.010714286f.pow(s / 100f) * 0.28f
            val loRatio = 0.125f.pow(s / 100f) * 0.4f
            val hi = maxOf(strongRef * hiFrac, NOISE_FLOOR)
            val lo = maxOf(hi * loRatio, 15.000001f)

            // (6) 滞后连接：强点入栈，从强点 8 邻域扩散弱点
            val weak = BooleanArray(n)
            val stack = ArrayDeque<Int>()
            for (i in 0 until n) {
                if (!isGlyph[i]) continue
                if (nms[i] >= hi) {
                    base[i] = true
                    stack.addLast(i)
                } else if (nms[i] >= lo) {
                    weak[i] = true
                }
            }
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                val cx = i % width
                val cy = i / width
                for (oy in -1..1) {
                    for (ox in -1..1) {
                        if (ox == 0 && oy == 0) continue
                        val nx = cx + ox
                        val ny = cy + oy
                        if (nx in 0 until width && ny in 0 until height) {
                            val j = ny * width + nx
                            if (weak[j] && !base[j]) {
                                base[j] = true
                                stack.addLast(j)
                            }
                        }
                    }
                }
            }
        }

        // (7) 后处理：可选平滑 + 加粗
        val cleaned = if (smooth) Morphology.smooth(base, width, height) else base
        return Outline.thicken(cleaned, isGlyph, width, height, thickness.coerceIn(1, 3))
    }

    /** 3x3 可分离高斯 [1,2,1]/4，边界复制（clamp） */
    private fun gaussian3(src: FloatArray, w: Int, h: Int): FloatArray {
        val tmp = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val l = src[y * w + (x - 1).coerceAtLeast(0)]
                val c = src[y * w + x]
                val r = src[y * w + (x + 1).coerceAtMost(w - 1)]
                tmp[y * w + x] = (c * 2f + l + r) / 4f
            }
        }
        val out = FloatArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val u = tmp[(y - 1).coerceAtLeast(0) * w + x]
                val c = tmp[y * w + x]
                val d = tmp[(y + 1).coerceAtMost(h - 1) * w + x]
                out[y * w + x] = (c * 2f + u + d) / 4f
            }
        }
        return out
    }

    /** Sobel X，越界坐标 clamp（复制边缘） */
    private fun sobelX(p: FloatArray, x: Int, y: Int, w: Int, h: Int): Float {
        fun v(xx: Int, yy: Int) = p[yy.coerceIn(0, h - 1) * w + xx.coerceIn(0, w - 1)]
        return (v(x + 1, y - 1) + v(x + 1, y) * 2f + v(x + 1, y + 1)) -
            (v(x - 1, y - 1) + v(x - 1, y) * 2f + v(x - 1, y + 1))
    }

    /** Sobel Y，越界坐标 clamp（复制边缘） */
    private fun sobelY(p: FloatArray, x: Int, y: Int, w: Int, h: Int): Float {
        fun v(xx: Int, yy: Int) = p[yy.coerceIn(0, h - 1) * w + xx.coerceIn(0, w - 1)]
        return (v(x - 1, y + 1) + v(x, y + 1) * 2f + v(x + 1, y + 1)) -
            (v(x - 1, y - 1) + v(x, y - 1) * 2f + v(x + 1, y - 1))
    }

    /** NMS 邻居取值：越界返回 0（与 Sobel 的 clamp 不同！） */
    private fun magAt(mag: FloatArray, x: Int, y: Int, w: Int, h: Int): Float {
        if (x < 0 || y < 0 || x >= w || y >= h) return 0f
        return mag[y * w + x]
    }

    /** 方向量化：tan22.5° / tan67.5° 分界 */
    private fun quantizeDir(gx: Float, gy: Float): Int {
        val ax = abs(gx)
        val ay = abs(gy)
        if (ay <= 0.4142f * ax) return 0
        if (ay >= 2.4142f * ax) return 2
        return if ((gx > 0f) == (gy > 0f)) 3 else 1
    }
}
