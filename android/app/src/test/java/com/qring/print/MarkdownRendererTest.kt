package com.qring.print

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Markdown 渲染器测试（2026-08-14 加）：恒 384 宽、标题增高、分割线可像素断言。
 * LEGACY 下 drawLine 可靠渲染，文字不做像素断言（视觉交 runPreviewCheck）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarkdownRendererTest {

    private fun countBlack(bmp: Bitmap): Int {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return px.count { it and 0xFF < 128 }
    }

    @Test
    fun `恒384宽且标题比正文高`() {
        val body = MarkdownRenderer.render(MarkdownParser.parse("普通文字"))
        val title = MarkdownRenderer.render(MarkdownParser.parse("# 大标题"))
        assertEquals(384, body.width)
        assertEquals(384, title.width)
        assertTrue("标题字号更大应更高", title.height > body.height)
    }

    @Test
    fun `分割线产生黑色像素且高于纯文字`() {
        val plain = MarkdownRenderer.render(MarkdownParser.parse("只有一行文字"))
        val withHr = MarkdownRenderer.render(MarkdownParser.parse("上面文字\n\n---\n\n下面文字"))
        val plainBlack = countBlack(plain)
        val hrBlack = countBlack(withHr)
        assertTrue("分割线应产生黑色像素: hr=$hrBlack plain=$plainBlack", hrBlack > plainBlack + 5)
    }

    @Test
    fun `全功能混排渲染不崩恒384宽`() {
        val md = "# 标题\n\n列表：\n- 一\n- 二\n\n1. 三\n\n> 引用\n\n```\ncode\n```\n\n---\n"
        val bmp = MarkdownRenderer.render(MarkdownParser.parse(md))
        assertEquals(384, bmp.width)
        assertTrue("高度应为正", bmp.height > 0)
    }

    @Test
    fun `空输入渲染出最小画布`() {
        val bmp = MarkdownRenderer.render(MarkdownParser.parse(""))
        assertEquals(384, bmp.width)
        assertTrue(bmp.height >= 1)
    }

    @Test
    fun `代码块缩进不崩`() {
        val bmp = MarkdownRenderer.render(MarkdownParser.parse("```python\nprint('hi')\n```"))
        assertEquals(384, bmp.width)
        assertTrue(bmp.height > 0)
    }
}
