package com.qring.print

import kotlin.math.abs
import kotlin.math.pow

/**
 * 对比度调节（移植自 xyprt/QuickPrintRenderer 的 Contrast，2026-08-11）。
 *
 * 膝形 S 曲线，以 128±span 为枢轴：amount>0 拉深、amount<0 提亮，amount=0 恒等。
 * 与抖动模式配合使用（描边模式不经对比度，见 QuickPrintRenderer.toMono 管线顺序）。
 */
object Contrast {

    /**
     * @param amount -100..100；0 时原样返回同一数组引用
     * @return 0..255 的灰度
     */
    fun adjust(gray: FloatArray, amount: Int): FloatArray {
        if (amount == 0) return gray
        val c = amount.coerceIn(-100, 100) / 100f
        val m = abs(c)
        val span = (1f - (1f - m).pow(2.5f)) * 126f
        val threshold = (if (c >= 0f) span else -span) + 128f
        val slope = 0.6f * m + 1f
        return FloatArray(gray.size) { i ->
            ((gray[i] - threshold) * slope + 128f).coerceIn(0f, 255f)
        }
    }
}
