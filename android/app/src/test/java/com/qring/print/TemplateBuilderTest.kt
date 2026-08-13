package com.qring.print

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 错题卡模板测试（2026-08-13 加，复习友好版 + 重做卷）：
 * 纯布局层验证——尺寸正确、内容区在纸面内、进度栏/撕纸线/订正区按预期渲染。
 */
@RunWith(RobolectricTestRunner::class)
class TemplateBuilderTest {

    private fun solidImg(color: Int, w: Int = 400, h: Int = 200): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.eraseColor(color)
        return b
    }

    @Test
    fun `标准卡 无图 尺寸正确`() {
        val bmp = TemplateBuilder.build("概念不清", "一元二次方程")
        assertEquals(WIDTH_DOTS, bmp.width)
        assertTrue("标准卡高度至少容纳标题+错因+知识点+订正区", bmp.height > 200)
    }

    @Test
    fun `标准卡 带图 高度比无图更高`() {
        val noImg = TemplateBuilder.build("a", "b")
        val withImg = TemplateBuilder.build("a", "b", solidImg(0xFF000000.toInt()))
        assertTrue("带图应更高", withImg.height > noImg.height)
    }

    @Test
    fun `复习版 尺寸正确且高于标准卡`() {
        val std = TemplateBuilder.build("概念不清", "一元二次方程")
        val review = TemplateBuilder.buildReview("概念不清", "一元二次方程")
        assertEquals(WIDTH_DOTS, review.width)
        assertTrue("复习版含进度栏+撕纸线，应高于标准卡", review.height > std.height)
    }

    @Test
    fun `复习版 带图 高度正确`() {
        val bmp = TemplateBuilder.buildReview("a", "b", solidImg(0xFF000000.toInt()))
        assertEquals(WIDTH_DOTS, bmp.width)
        assertTrue(bmp.height > 300)
    }

    @Test
    fun `重做卷 尺寸正确且随题数增长`() {
        val one = TemplateBuilder.buildReworkSheet(listOf(solidImg(0xFF000000.toInt())))
        val two = TemplateBuilder.buildReworkSheet(listOf(solidImg(0xFF000000.toInt()), solidImg(0xFF222222.toInt())))
        assertEquals(WIDTH_DOTS, one.width)
        assertEquals(WIDTH_DOTS, two.width)
        assertTrue("重做卷 2 题应比 1 题高", two.height > one.height)
    }

    @Test
    fun `重做卷 空列表 抛异常`() {
        try {
            TemplateBuilder.buildReworkSheet(emptyList())
            assertTrue("空列表应抛异常", false)
        } catch (e: IllegalArgumentException) {
            // 预期
        }
    }

    @Test
    fun `复习版 有可见内容 非全白`() {
        val bmp = TemplateBuilder.buildReview("计算错误", "勾股定理")
        var black = 0
        for (x in 0 until bmp.width step 4) {
            for (y in 0 until bmp.height step 4) {
                if (bmp.getPixel(x, y) and 0xFF < 128) black++
            }
        }
        assertTrue("复习版应有文字/线条内容（进度栏/标题/撕纸线/横线）", black > 20)
    }

    @Test
    fun `重做卷 有可见内容 非全白`() {
        val bmp = TemplateBuilder.buildReworkSheet(listOf(solidImg(0xFF000000.toInt())))
        var black = 0
        for (x in 0 until bmp.width step 4) {
            for (y in 0 until bmp.height step 4) {
                if (bmp.getPixel(x, y) and 0xFF < 128) black++
            }
        }
        assertTrue("重做卷应有题目图+标题+横线内容", black > 100)
    }
}
