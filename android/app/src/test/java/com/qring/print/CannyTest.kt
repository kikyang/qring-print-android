package com.qring.print

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Canny 边缘检测单元测试（2026-08-12 补）：
 * 纯色图无边缘、明暗分界有边缘、越界参数不崩。
 */
class CannyTest {

    private fun argbOf(gray: Int): Int = 0xFF000000.toInt() or (gray shl 16) or (gray shl 8) or gray

    @Test
    fun `纯黑图无边缘`() {
        val w = 32
        val h = 32
        val argb = IntArray(w * h) { argbOf(0) }
        val glyph = BooleanArray(w * h) { true }
        val edges = Canny.detect(argb, glyph, w, h, sensitivity = 88, thickness = 1)
        assertTrue("纯色图不应有边缘", edges.none { it })
    }

    @Test
    fun `纯白图无边缘`() {
        val w = 32
        val h = 32
        val argb = IntArray(w * h) { argbOf(255) }
        val glyph = BooleanArray(w * h) { true }
        val edges = Canny.detect(argb, glyph, w, h, sensitivity = 88, thickness = 1)
        assertTrue("纯白图不应有边缘", edges.none { it })
    }

    @Test
    fun `左右明暗分界检测到边缘`() {
        val w = 48
        val h = 24
        val argb = IntArray(w * h)
        val glyph = BooleanArray(w * h) { true }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                argb[i] = if (x < 24) argbOf(0) else argbOf(255)
                glyph[i] = true
            }
        }
        val edges = Canny.detect(argb, glyph, w, h, sensitivity = 88, thickness = 1)
        val edgeCount = edges.count { it }
        // 竖直分界线：每行边界附近有若干边缘点（高斯+Sobel 带宽约 3~5 像素宽）
        assertTrue("分界图应有边缘，实际 $edgeCount", edgeCount in 20..(h * 8))
    }

    @Test
    fun `非glyph像素不产生边缘`() {
        val w = 32
        val h = 32
        val argb = IntArray(w * h) { argbOf(0) }
        val glyph = BooleanArray(w * h) { false }   // 全部非 glyph → 亮度强制 255
        val edges = Canny.detect(argb, glyph, w, h, sensitivity = 88, thickness = 1)
        assertTrue(edges.none { it })
    }

    @Test
    fun `极小图与极端参数不崩溃`() {
        // 1x1 图
        val e1 = Canny.detect(intArrayOf(argbOf(100)), booleanArrayOf(true), 1, 1, sensitivity = 88, thickness = 1)
        assertTrue(e1.size == 1)
        // 极端灵敏度与线宽
        val argb = IntArray(16 * 16) { argbOf(it % 256) }
        val glyph = BooleanArray(16 * 16) { true }
        val e2 = Canny.detect(argb, glyph, 16, 16, sensitivity = 0, thickness = 3, smooth = true)
        assertTrue(e2.size == 16 * 16)
        val e3 = Canny.detect(argb, glyph, 16, 16, sensitivity = 100, thickness = 1)
        assertTrue(e3.size == 16 * 16)
    }
}
