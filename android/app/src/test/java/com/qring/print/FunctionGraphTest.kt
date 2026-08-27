package com.qring.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 函数图像渲染测试（2026-08-27 加）：
 * 尺寸 / 表达式非法 / X 范围错误 / 无有效值 / 常量函数 / 像素非空白。
 */
@RunWith(RobolectricTestRunner::class)
class FunctionGraphTest {

    @Test
    fun `渲染尺寸 384 宽`() {
        val bmp = FunctionGraph.render("x^2", -10.0, 10.0)
        assertEquals(384, bmp.width)
        assertEquals(384, bmp.height)
    }

    @Test
    fun `渲染输出非空白 边框可见`() {
        // LEGACY 下只有 drawRect（坐标轴边框）写像素，drawLine/drawText 为空操作；
        // 因此这里只断言「画布有输出」（边框描边像素），曲线视觉正确性交 runPreviewCheck。
        val bmp = FunctionGraph.render("x^2", -10.0, 10.0)
        var nonWhite = 0
        for (y in 0 until bmp.height step 2) {
            for (x in 0 until bmp.width step 2) {
                if (bmp.getPixel(x, y) != android.graphics.Color.WHITE) nonWhite++
            }
        }
        assertTrue("边框应渲染出非白像素，实际 $nonWhite", nonWhite > 200)
    }

    @Test
    fun `线性函数`() {
        val bmp = FunctionGraph.render("2*x+1", -5.0, 5.0)
        assertEquals(384, bmp.width)
    }

    @Test
    fun `三角函数`() {
        val bmp = FunctionGraph.render("sin(x)", -10.0, 10.0)
        assertEquals(384, bmp.width)
    }

    @Test
    fun `常量函数 单值范围`() {
        val bmp = FunctionGraph.render("1", -10.0, 10.0)
        assertEquals(384, bmp.width)
    }

    @Test
    fun `表达式非法 抛异常`() {
        try {
            FunctionGraph.render("x^2+", -10.0, 10.0)
            assertTrue("非法表达式应抛异常", false)
        } catch (e: Exception) {
            assertTrue(true)
        }
    }

    @Test
    fun `X范围错误 抛异常`() {
        try {
            FunctionGraph.render("x", 10.0, -10.0)
            assertTrue("xMin>xMax 应抛异常", false)
        } catch (e: Exception) {
            assertTrue(true)
        }
    }

    @Test
    fun `范围内无有效值 抛异常`() {
        try {
            FunctionGraph.render("ln(x)", -5.0, -1.0)   // 负域全 NaN
            assertTrue("应抛异常", false)
        } catch (e: Exception) {
            assertTrue(true)
        }
    }

    @Test
    fun `渐近线 1除以x 不崩溃`() {
        val bmp = FunctionGraph.render("1/x", -10.0, 10.0)   // x=0 处无穷大，应断线不崩
        assertEquals(384, bmp.width)
    }
}
