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
            // 蛇形扫描（2026-09-09，吸收上游 lztttt v1.6.0）：偶数行左→右，奇数行右→左。
            // 单向扫描时每行的量化误差总是朝同一侧堆积，照片中间调会出现蠕虫纹/竖条纹；
            // 交替方向让误差在左右两侧交替抵消，灰底更干净（输出仍保持总体墨量密度）。
            val ltr = y % 2 == 0
            val step = if (ltr) 1 else -1
            var x = if (ltr) 0 else width - 1
            while (x in 0 until width) {
                val index = y * width + x
                val oldValue = buffer[index]
                val newValue = if (oldValue < PIVOT) 0f else 255f
                out[index] = if (newValue == 0f) 1 else 0
                val error = oldValue - newValue

                // 主方向（当前扫描方向）上的相邻像素；反方向时整组权重镜像
                val next1 = x + step
                val next2 = x + 2 * step

                if (mode == DitherMode.FLOYD_STEINBERG) {
                    // 左→右：        X   7/16
                    //           3/16 5/16 1/16
                    // 右→左时整组权重镜像（7/16 朝左，3/16 落到右下方）
                    if (next1 in 0 until width) buffer[index + step] += error * 7 / 16
                    if (y + 1 < height) {
                        if (x - step in 0 until width) buffer[index + width - step] += error * 3 / 16
                        buffer[index + width] += error * 5 / 16
                        if (next1 in 0 until width) buffer[index + width + step] += error * 1 / 16
                    }
                } else {
                    //       X   1/8  1/8
                    //  1/8 1/8  1/8
                    //       1/8
                    // 只扩散 6/8，剩下 2/8 丢弃 —— 这正是 Atkinson 对比度更高的原因
                    val share = error / 8
                    if (next1 in 0 until width) buffer[index + step] += share
                    if (next2 in 0 until width) buffer[index + 2 * step] += share
                    if (y + 1 < height) {
                        if (x - step in 0 until width) buffer[index + width - step] += share
                        buffer[index + width] += share
                        if (next1 in 0 until width) buffer[index + width + step] += share
                    }
                    if (y + 2 < height) {
                        buffer[index + 2 * width] += share
                    }
                }
                x += step
            }
        }
        return out
    }
}
