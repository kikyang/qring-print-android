package com.qring.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 公式排版（2026-08-18 加） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MathLayoutTest {

    private val measure: MathMeasurer = { text, size, bold ->
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size.toFloat()
            isFakeBoldText = bold
        }
        p.measureText(text)
    }

    @Test
    fun `普通文本盒宽高大于0`() {
        val box = MathLayout.layout(listOf(MRun("x")), 20, false, measure)
        assertTrue(box.w > 0)
        assertTrue(box.h > 0)
        assertTrue(box.ascent > 0)
    }

    @Test
    fun `分数盒高度大于分子或分母`() {
        val frac = MFrac(listOf(MRun("1")), listOf(MRun("2")))
        val box = MathLayout.layout(listOf(frac), 20, false, measure)
        assertTrue(box.h > 20)
    }

    @Test
    fun `上下标盒宽大于底`() {
        val script = MScript(listOf(MRun("a")), listOf(MRun("2")), null)
        val box = MathLayout.layout(listOf(script), 20, false, measure)
        assertTrue(box.w > 0)
    }

    @Test
    fun `矩阵渲染非空白`() {
        val matrix = MMatrix(listOf(listOf(listOf(MRun("1")), listOf(MRun("2")))))
        val bmp = MathLayout.renderToBitmap(listOf(matrix), 20, false)
        assertTrue(bmp.width > 0)
        assertTrue(bmp.height > 0)
    }

    @Test
    fun `渲染分数位图非空白`() {
        val frac = MFrac(listOf(MRun("1")), listOf(MRun("2")))
        val bmp = MathLayout.renderToBitmap(listOf(frac), 20, false)
        var black = 0
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                if (bmp.getPixel(x, y) != android.graphics.Color.WHITE) black++
            }
        }
        assertTrue("应该有黑色像素", black > 0)
    }
}
