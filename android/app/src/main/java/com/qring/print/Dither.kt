package com.qring.print

/**
 * 图像抖动 (dithering)。
 *
 * 热敏头是 1-bit 输出，只能打黑或不打。单纯按阈值二值化会把所有中间灰度
 * 一刀切成纯黑纯白，照片就丢光了层次。抖动通过把量化误差扩散到邻近像素，
 * 用点阵的疏密在视觉上模拟灰阶。
 *
 * 移植自 QringPrint 的 Dither.ets（纯计算，无平台依赖）。
 */

enum class DitherMode {
    /** 直接阈值二值化，不扩散误差。线稿/文字/二维码用这个最锐利 */
    NONE,
    /** Floyd-Steinberg：经典误差扩散，层次最细腻，照片首选 */
    FLOYD_STEINBERG,
    /** Atkinson：只扩散 6/8 误差，对比度更高、亮部更干净 */
    ATKINSON,
}

/** 灰度图。data 长度 = width * height，取值 0(黑) ~ 255(白) */
data class GrayImage(val data: IntArray, val width: Int, val height: Int)

object Dither {

    /**
     * 误差扩散用的中点阈值。
     * 抖动模式恒用 128：误差扩散的前提是量化点落在灰阶中点，
     * 用别的值（比如文字那套 212）会让整幅图整体压黑，失去抖动的意义。
     * 只有 NONE 模式才使用调用方传入的 threshold。
     */
    const val PIVOT = 128

    /**
     * 灰度 → 二值。返回每像素 1 字节：1 = 黑（要打印），0 = 白。
     *
     * @param threshold 仅 NONE 模式生效；抖动模式固定用 [PIVOT]。
     */
    fun toBinary(gray: GrayImage, mode: DitherMode, threshold: Int): ByteArray {
        val width = gray.width
        val height = gray.height
        val total = width * height
        val out = ByteArray(total)

        if (mode == DitherMode.NONE) {
            for (i in 0 until total) {
                out[i] = if (gray.data[i] < threshold) 1 else 0
            }
            return out
        }

        // 误差扩散会把值推到 0~255 之外，必须用带符号的浮点缓冲，不能原地改 IntArray
        val buffer = FloatArray(total)
        for (i in 0 until total) buffer[i] = gray.data[i].toFloat()

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val oldValue = buffer[index]
                val newValue = if (oldValue < PIVOT) 0f else 255f
                out[index] = if (newValue == 0f) 1 else 0
                val error = oldValue - newValue

                if (mode == DitherMode.FLOYD_STEINBERG) {
                    //        X   7/16
                    //  3/16 5/16 1/16
                    if (x + 1 < width) buffer[index + 1] += error * 7 / 16
                    if (y + 1 < height) {
                        if (x > 0) buffer[index + width - 1] += error * 3 / 16
                        buffer[index + width] += error * 5 / 16
                        if (x + 1 < width) buffer[index + width + 1] += error * 1 / 16
                    }
                } else {
                    //       X   1/8  1/8
                    //  1/8 1/8  1/8
                    //       1/8
                    // 只扩散 6/8，剩下 2/8 丢弃 —— 这正是 Atkinson 对比度更高的原因
                    val share = error / 8
                    if (x + 1 < width) buffer[index + 1] += share
                    if (x + 2 < width) buffer[index + 2] += share
                    if (y + 1 < height) {
                        if (x > 0) buffer[index + width - 1] += share
                        buffer[index + width] += share
                        if (x + 1 < width) buffer[index + width + 1] += share
                    }
                    if (y + 2 < height) {
                        buffer[index + 2 * width] += share
                    }
                }
            }
        }
        return out
    }
}
