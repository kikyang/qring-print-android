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

/** 自适应二值化算法（2026-08-16 加，对齐 lztttt v1.5.0 三算法） */
enum class EnhanceAlgorithm(val label: String) {
    SAUVOLA("标准"),
    WOLF("手写"),
    BRADLEY("快速"),
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

    /** 高分辨率处理时长边上限（控制积分图内存，2048 足够还原 384 打印细节） */
    const val HIGH_RES_MAX_EDGE = 2048

    /**
     * 高分辨率灰度光照补偿。
     * 返回保持处理分辨率的灰度图（长边 ≤ [HIGH_RES_MAX_EDGE]），白底黑字。
     * 调用方应再缩放至 384 宽后二值化，避免先降采样丢小字。
     */
    fun enhanceHighResGray(source: Bitmap, maxEdge: Int = HIGH_RES_MAX_EDGE): Bitmap {
        var work = source
        var owned = false
        val longEdge = maxOf(source.width, source.height)
        if (longEdge > maxEdge) {
            val scale = maxEdge.toFloat() / longEdge
            val nw = maxOf(1, Math.round(source.width * scale))
            val nh = maxOf(1, Math.round(source.height * scale))
            work = Bitmap.createScaledBitmap(source, nw, nh, true)
            owned = true
        }
        try {
            val w = work.width
            val h = work.height
            val pixels = IntArray(w * h)
            work.getPixels(pixels, 0, w, 0, 0, w, h)
            val gray = IntArray(w * h)
            for (i in pixels.indices) {
                val c = pixels[i]
                gray[i] = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            }
            val bgWindow = maxOf(75, minOf(w, h) / 8) or 1
            val bg = computeLocalMean(gray, w, h, bgWindow)
            val norm = IntArray(w * h)
            for (i in gray.indices) {
                val bgVal = bg[i].coerceAtLeast(1)
                norm[i] = (gray[i].toFloat() / bgVal.toFloat() * 255f).toInt().coerceIn(0, 255)
            }
            val colors = IntArray(w * h)
            for (i in norm.indices) {
                val v = norm[i]
                colors[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            result.setPixels(colors, 0, w, 0, 0, w, h)
            return result
        } finally {
            if (owned) work.recycle()
        }
    }

    /**
     * 一键增强：高分辨率光照补偿 → 缩到 384 宽 → 所选自适应二值化。
     * 默认 Sauvola 标准档，行为覆盖原「直方图拉伸 + Sauvola」，但小字保留更好。
     *
     * @param algorithm 0=Sauvola（标准印刷），1=Wolf（铅笔手写/白纸噪声），2=Bradley（最快）
     * @param strength 0=弱 1=标准 2=强；映射到窗口 21/25/31、k 0.15/0.20/0.30
     */
    fun enhanceToRaster(
        bitmap: Bitmap,
        algorithm: Int = 0,
        strength: Int = 1,
    ): RasterData {
        val highRes = enhanceHighResGray(bitmap)
        val w = highRes.width
        val h = highRes.height
        val highResOwned = highRes !== bitmap
        val scaled: Bitmap
        if (w == WIDTH_DOTS) {
            scaled = highRes
        } else {
            val th = maxOf(1, Math.round(h.toFloat() * WIDTH_DOTS / w))
            scaled = Bitmap.createScaledBitmap(highRes, WIDTH_DOTS, th, true)
            if (highResOwned) highRes.recycle()
        }
        try {
            val gray = RasterEncoder.extractGrayPublic(scaled)
            val windowSize = when (strength) {
                0 -> 21
                2 -> 31
                else -> 25
            }
            val k = when (strength) {
                0 -> 0.15
                2 -> 0.30
                else -> 0.20
            }
            val binary = enhanceGray(gray, windowSize = windowSize, k = k, algorithm = algorithm)
            return RasterEncoder.packPublic(binary, gray.width, gray.height)
        } finally {
            if (scaled !== highRes) scaled.recycle()
        }
    }

    /**
     * 在已缩放到打印宽度的灰度图上做光照补偿 + 自适应二值化。
     * @param algorithm 0=Sauvola 1=Wolf 2=Bradley
     */
    fun enhanceGray(
        gray: GrayImage,
        windowSize: Int = 25,
        k: Double = 0.20,
        algorithm: Int = 0,
        denoise: Boolean = true,
    ): ByteArray {
        val w = gray.width
        val h = gray.height
        val norm = normalizeBackground(gray.data, w, h, windowSize)
        val binary = when (algorithm) {
            1 -> wolfBinary(norm, w, h, windowSize, k)
            2 -> bradleyBinary(norm, w, h, windowSize, k)
            else -> sauvolaBinaryData(norm, w, h, windowSize, k)
        }
        if (denoise) denoiseBinary(binary, w, h)
        return binary
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

    // ── 私有：光照补偿与三种二值化（积分图实现，适配高分辨率）──

    private fun normalizeBackground(data: IntArray, w: Int, h: Int, baseWindow: Int): IntArray {
        val bgWindow = maxOf(baseWindow * 4, 75) or 1
        val bg = computeLocalMean(data, w, h, bgWindow)
        val norm = IntArray(w * h)
        for (i in data.indices) {
            val bgVal = bg[i].coerceAtLeast(1)
            norm[i] = (data[i].toFloat() / bgVal.toFloat() * 255f).toInt().coerceIn(0, 255)
        }
        return norm
    }

    private fun sauvolaBinaryData(norm: IntArray, w: Int, h: Int, windowSize: Int, k: Double): ByteArray {
        val sw = windowSize or 1
        val mean = computeLocalMean(norm, w, h, sw)
        val std = computeLocalStd(norm, w, h, sw, mean)
        val out = ByteArray(w * h)
        for (i in norm.indices) {
            val m = mean[i]
            val s = std[i]
            val t = m * (1.0 + k * (s / 128.0 - 1.0))
            out[i] = if (norm[i] < t) 1 else 0
        }
        return out
    }

    private fun wolfBinary(norm: IntArray, w: Int, h: Int, windowSize: Int, k: Double): ByteArray {
        val sw = windowSize or 1
        val mean = computeLocalMean(norm, w, h, sw)
        val std = computeLocalStd(norm, w, h, sw, mean)
        var minVal = 255
        for (v in norm) if (v < minVal) minVal = v
        val r = 128.0
        val out = ByteArray(w * h)
        for (i in norm.indices) {
            val m = mean[i]
            val s = std[i]
            val t = m - k * (m - minVal) * (1.0 - (s / r).coerceIn(0.0, 1.0))
            out[i] = if (norm[i] < t) 1 else 0
        }
        return out
    }

    private fun bradleyBinary(norm: IntArray, w: Int, h: Int, windowSize: Int, k: Double): ByteArray {
        val sw = windowSize or 1
        val mean = computeLocalMean(norm, w, h, sw)
        val t = (k * 100.0).coerceIn(1.0, 50.0)
        val out = ByteArray(w * h)
        for (i in norm.indices) {
            val m = mean[i]
            out[i] = if (norm[i] < m * (1.0 - t / 100.0)) 1 else 0
        }
        return out
    }

    private fun denoiseBinary(binary: ByteArray, w: Int, h: Int) {
        if (w < 3 || h < 3) return
        val copy = binary.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                if (copy[idx].toInt() == 1) {
                    var blackCount = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            if (copy[(y + dy) * w + (x + dx)].toInt() == 1) blackCount++
                        }
                    }
                    if (blackCount == 0) binary[idx] = 0
                }
            }
        }
    }

    /** 积分图局部均值 */
    private fun computeLocalMean(data: IntArray, w: Int, h: Int, window: Int): IntArray {
        val half = window / 2
        val cols = w + 1
        val integral = IntArray(cols * (h + 1))
        for (y in 1..h) {
            var rowSum = 0
            for (x in 1..w) {
                rowSum += data[(y - 1) * w + (x - 1)]
                integral[y * cols + x] = integral[(y - 1) * cols + x] + rowSum
            }
        }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y1 = (y - half).coerceAtLeast(0)
            val y2 = (y + half).coerceAtMost(h - 1)
            for (x in 0 until w) {
                val x1 = (x - half).coerceAtLeast(0)
                val x2 = (x + half).coerceAtMost(w - 1)
                val area = (x2 - x1 + 1) * (y2 - y1 + 1)
                out[y * w + x] = (integral[(y2 + 1) * cols + (x2 + 1)] -
                        integral[y1 * cols + (x2 + 1)] -
                        integral[(y2 + 1) * cols + x1] +
                        integral[y1 * cols + x1]) / area
            }
        }
        return out
    }

    /** 积分图局部标准差：Var = E(X²) - E(X)² */
    private fun computeLocalStd(data: IntArray, w: Int, h: Int, window: Int, mean: IntArray): IntArray {
        val half = window / 2
        val cols = w + 1
        val sq = LongArray(w * h)
        for (i in data.indices) sq[i] = data[i].toLong() * data[i]
        val integralSq = LongArray(cols * (h + 1))
        for (y in 1..h) {
            var rowSum = 0L
            for (x in 1..w) {
                rowSum += sq[(y - 1) * w + (x - 1)]
                integralSq[y * cols + x] = integralSq[(y - 1) * cols + x] + rowSum
            }
        }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y1 = (y - half).coerceAtLeast(0)
            val y2 = (y + half).coerceAtMost(h - 1)
            for (x in 0 until w) {
                val x1 = (x - half).coerceAtLeast(0)
                val x2 = (x + half).coerceAtMost(w - 1)
                val area = (x2 - x1 + 1) * (y2 - y1 + 1)
                val sumSq = integralSq[(y2 + 1) * cols + (x2 + 1)] -
                        integralSq[y1 * cols + (x2 + 1)] -
                        integralSq[(y2 + 1) * cols + x1] +
                        integralSq[y1 * cols + x1]
                val meanSq = sumSq.toDouble() / area
                val m = mean[y * w + x]
                val variance = (meanSq - m.toDouble() * m).coerceAtLeast(0.0)
                out[y * w + x] = kotlin.math.sqrt(variance).toInt()
            }
        }
        return out
    }
}
