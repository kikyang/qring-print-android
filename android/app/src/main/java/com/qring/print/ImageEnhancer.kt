package com.qring.print

import android.graphics.Bitmap
import android.graphics.Color

/** 消除笔模式：去掉照片里的红笔/蓝笔批改痕迹（印先森"消除笔"的离线简化版） */
enum class InkRemoveMode(val label: String) {
    NONE("关闭"),
    RED("去红笔"),
    BLUE("去蓝笔"),
    BOTH("红蓝都去"),
}

/**
 * 错题照片增强引擎 —— 仿"印先森一键去手写/去背景"的核心算法。
 *
 * 场景：手机拍的试卷/草稿纸有纸灰、阴影、不均匀光照，
 * 直接阈值二值化会打成一片灰。处理管线：
 *   1. 直方图截断拉伸：去灰雾、白底化（0.5% 截断）
 *   2. Sauvola 自适应二值化：对光照不均/阴影鲁棒，
 *      局部窗口内按均值+标准差动态定阈值
 *
 * 纯计算实现，不依赖平台图像库，方便单测。
 */
object ImageEnhancer {

    /** 直方图截断百分比（两端各裁掉这么多，去除极值噪点） */
    private const val CLIP = 0.005

    /** Sauvola 默认参数：窗口半宽 15（窗口 31×31），k=0.35 */
    private const val WINDOW_HALF = 15
    private const val K = 0.35

    /**
     * 消除笔：把红笔/蓝笔批改痕迹替换为白底（拍试卷去批注，仿印先森）。
     * 颜色判定用"通道差"而非绝对阈值，深浅红/蓝都能识别；
     * 灰色笔迹（黑笔/铅笔）不受影响。
     */
    fun removeInk(bitmap: Bitmap, mode: InkRemoveMode): Bitmap {
        if (mode == InkRemoveMode.NONE) return bitmap
        val w = bitmap.width
        val h = bitmap.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = bitmap.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val isRed = mode != InkRemoveMode.BLUE && r > 110 && r - g > 30 && r - b > 30
                val isBlue = mode != InkRemoveMode.RED && b > 110 && b - r > 25 && b - g > 25
                out.setPixel(x, y, if (isRed || isBlue) Color.WHITE else p)
            }
        }
        return out
    }

    /**
     * 自动裁白边：检测照片四周的纯白区域（桌面/多余留白）并裁掉。
     * 判定：某行/列所有像素灰度 > 240 视为空白；从四边向里推进，
     * 裁剪比例上限 40%（超过说明整体偏亮无白边，不裁），防误裁。
     */
    fun trimWhiteEdges(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 16 || h < 16) return bitmap

        val gray = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = bitmap.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                gray[y * w + x] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            }
        }

        fun colWhite(x: Int): Boolean {
            var y = 0
            while (y < h) { if (gray[y * w + x] <= 240) return false; y++ }
            return true
        }

        fun rowWhite(y: Int): Boolean {
            var x = 0
            while (x < w) { if (gray[y * w + x] <= 240) return false; x++ }
            return true
        }

        val maxTrim = (w * 0.40).toInt()
        var left = 0
        while (left < maxTrim && colWhite(left)) left++
        var right = 0
        while (right < maxTrim && colWhite(w - 1 - right)) right++
        val maxTrimV = (h * 0.40).toInt()
        var top = 0
        while (top < maxTrimV && rowWhite(top)) top++
        var bottom = 0
        while (bottom < maxTrimV && rowWhite(h - 1 - bottom)) bottom++

        if (left == 0 && right == 0 && top == 0 && bottom == 0) return bitmap
        val nw = w - left - right
        val nh = h - top - bottom
        if (nw < 16 || nh < 16) return bitmap
        return Bitmap.createBitmap(bitmap, left, top, nw, nh)
    }

    /**
     * 一键增强：灰度拉伸 + Sauvola 二值化。
     * @return 光栅数据，可直接打印
     */
    fun enhanceToRaster(bitmap: Bitmap): RasterData {
        val gray = RasterEncoder.extractGrayPublic(bitmap)
        val stretched = stretch(gray)
        val binary = sauvolaBinary(stretched)
        return RasterEncoder.packPublic(binary, stretched.width, stretched.height)
    }

    /**
     * 直方图截断拉伸：把灰度范围 [clip 分位, 1-clip 分位] 线性映射到 [0,255]。
     * 纸面发灰的照片拉出白底黑字。
     */
    fun stretch(gray: GrayImage, clip: Double = CLIP): GrayImage {
        val hist = IntArray(256)
        for (v in gray.data) hist[v]++
        val total = gray.data.size
        val loCount = (total * clip).toInt()
        val hiCount = (total * (1 - clip)).toInt()

        var lo = 0
        var acc = 0
        while (lo < 255 && acc <= loCount) { acc += hist[lo]; lo++ }
        var hi = 255
        acc = total
        while (hi > 0 && acc > hiCount) { acc -= hist[hi]; hi-- }
        if (hi <= lo) return gray  // 已无拉伸空间

        val lut = IntArray(256)
        for (i in 0..255) {
            lut[i] = when {
                i <= lo -> 0
                i >= hi -> 255
                else -> (255 * (i - lo) / (hi - lo))
            }
        }
        val out = IntArray(gray.data.size)
        for (i in gray.data.indices) out[i] = lut[gray.data[i]]
        return GrayImage(out, gray.width, gray.height)
    }

    /**
     * Sauvola 自适应二值化。
     * 对每个像素取局部窗口均值 m 和标准差 s：
     *   阈值 T = m × (1 + k × (s / 128 - 1))
     * 暗于 T 判黑（打印 1）。阴影/光照不均区域阈值自动跟随，不会整体压黑。
     */
    fun sauvolaBinary(gray: GrayImage, windowHalf: Int = WINDOW_HALF, k: Double = K): ByteArray {
        val w = gray.width
        val h = gray.height
        val out = ByteArray(w * h)

        // 滑动窗口直接累加（窗口小，O(W*H*window²) 对 384 宽足够快）
        for (y in 0 until h) {
            val y0 = maxOf(0, y - windowHalf)
            val y1 = minOf(h - 1, y + windowHalf)
            for (x in 0 until w) {
                val x0 = maxOf(0, x - windowHalf)
                val x1 = minOf(w - 1, x + windowHalf)

                var sum = 0L
                var sumSq = 0L
                val count = (y1 - y0 + 1) * (x1 - x0 + 1)
                for (yy in y0..y1) {
                    var rowBase = yy * w + x0
                    for (xx in x0..x1) {
                        val v = gray.data[rowBase]
                        sum += v
                        sumSq += v.toLong() * v
                        rowBase++
                    }
                }
                val mean = sum.toDouble() / count
                val variance = sumSq.toDouble() / count - mean * mean
                val std = if (variance > 0) kotlin.math.sqrt(variance) else 0.0

                val threshold = mean * (1 + k * (std / 128.0 - 1))
                out[y * w + x] = if (gray.data[y * w + x] < threshold) 1 else 0
            }
        }
        return out
    }
}
