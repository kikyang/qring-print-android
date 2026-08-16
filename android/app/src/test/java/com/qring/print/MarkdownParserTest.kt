package com.qring.print

import com.qring.print.MarkdownParser.Block
import com.qring.print.MarkdownParser.Inline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 解析器纯 JVM 测试（2026-08-14 加）：零 Android 依赖，全量可断言。
 */
class MarkdownParserTest {

    // ── 块级 ──

    @Test
    fun `标题分级解析`() {
        val blocks = MarkdownParser.parse("# 一级\n\n## 二级\n\n### 三级\n\n###### 六级")
        assertEquals(4, blocks.size)
        val h1 = blocks[0] as Block.Heading
        assertEquals(1, h1.level)
        assertEquals(listOf(Inline.Text("一级")), h1.inline)
        assertEquals(2, (blocks[1] as Block.Heading).level)
        assertEquals(3, (blocks[2] as Block.Heading).level)
        assertEquals(6, (blocks[3] as Block.Heading).level)
    }

    @Test
    fun `无空格井号不解析为标题`() {
        val blocks = MarkdownParser.parse("#没空格")
        assertEquals(1, blocks.size)
        assertTrue("应作为段落", blocks[0] is Block.Paragraph)
    }

    @Test
    fun `代码块内含井号不误判标题`() {
        val blocks = MarkdownParser.parse("```kotlin\n# 不是标题\nval x = 1\n```")
        assertEquals(1, blocks.size)
        val code = blocks[0] as Block.CodeBlock
        assertEquals("kotlin", code.lang)
        assertEquals("# 不是标题\nval x = 1", code.content)
    }

    @Test
    fun `未闭合fence兜底为代码块`() {
        val blocks = MarkdownParser.parse("```\nval x = 1")
        assertEquals(1, blocks.size)
        assertEquals("val x = 1", (blocks[0] as Block.CodeBlock).content)
    }

    @Test
    fun `无序列表三种符号收集`() {
        val blocks = MarkdownParser.parse("- 一\n* 二\n+ 三")
        assertEquals(1, blocks.size)
        val list = blocks[0] as Block.BulletList
        assertEquals(3, list.items.size)
        assertEquals(listOf(Inline.Text("一")), list.items[0])
    }

    @Test
    fun `有序列表从起始数字编号`() {
        val blocks = MarkdownParser.parse("3. 三\n4. 四")
        assertEquals(1, blocks.size)
        val list = blocks[0] as Block.OrderedList
        assertEquals(3, list.start)
        assertEquals(2, list.items.size)
    }

    @Test
    fun `分割线不混为列表项`() {
        val blocks = MarkdownParser.parse("- a\n- b\n\n---\n\n- c")
        assertEquals(3, blocks.size)
        assertTrue("`---` 应为分割线", blocks[1] is Block.ThematicBreak)
        val first = blocks[0] as Block.BulletList
        assertEquals(2, first.items.size)
        assertEquals(1, (blocks[2] as Block.BulletList).items.size)
    }

    @Test
    fun `引用连续行合并`() {
        val blocks = MarkdownParser.parse("> 第一行\n> 第二行")
        assertEquals(1, blocks.size)
        val q = blocks[0] as Block.Blockquote
        assertEquals(listOf("第一行", "第二行"), q.lines)
    }

    @Test
    fun `空串与纯空白解析为空列表`() {
        assertTrue(MarkdownParser.parse("").isEmpty())
        assertTrue(MarkdownParser.parse("   \n  \n").isEmpty())
    }

    @Test
    fun `连续非空行并入一段`() {
        val blocks = MarkdownParser.parse("第一行\n第二行")
        assertEquals(1, blocks.size)
        val p = blocks[0] as Block.Paragraph
        assertEquals(listOf(Inline.Text("第一行\n第二行")), p.inline)
    }

    // ── inline ──

    @Test
    fun `粗体内嵌斜体一层嵌套`() {
        val inline = MarkdownParser.parseInline("**粗 *斜* 码**")
        assertEquals(1, inline.size)
        val bold = inline[0] as Inline.Bold
        assertEquals(3, bold.content.size)
        assertEquals(Inline.Text("粗 "), bold.content[0])
        val italic = bold.content[1] as Inline.Italic
        assertEquals(listOf(Inline.Text("斜")), italic.content)
        assertEquals(Inline.Text(" 码"), bold.content[2])
    }

    @Test
    fun `行内代码与链接`() {
        val inline = MarkdownParser.parseInline("用 `code` 和 [文字](https://example.com)")
        assertEquals(4, inline.size)
        assertEquals(Inline.Text("用 "), inline[0])
        assertEquals(Inline.Code("code"), inline[1])
        assertEquals(Inline.Text(" 和 "), inline[2])
        val link = inline[3] as Inline.Link
        assertEquals("文字", link.text)
        assertEquals("https://example.com", link.url)
    }

    @Test
    fun `不成对星号按字面保留`() {
        val inline = MarkdownParser.parseInline("a*b")
        assertTrue(inline.all { it is Inline.Text })
    }
}
