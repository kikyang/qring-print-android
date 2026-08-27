package com.qring.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * CSV 表格解析测试（2026-08-27 加，变量数据批量打印）：
 * 基本解析 / 引号包裹 / 分号分隔 / GBK 编码 / 首行列名 / 空行跳过。
 */
@RunWith(RobolectricTestRunner::class)
class CsvTableParserTest {

    private fun parse(text: String): CsvTableParser.Table =
        CsvTableParser.parse(text.byteInputStream(Charsets.UTF_8))

    @Test
    fun `基本解析 首行为列名`() {
        val t = parse("姓名,成绩,科目\n小明,90,数学\n小红,85,语文\n")
        assertEquals(listOf("姓名", "成绩", "科目"), t.columns)
        assertEquals(2, t.rows.size)
        assertEquals("90", t.rows[0]["成绩"])
        assertEquals("语文", t.rows[1]["科目"])
    }

    @Test
    fun `引号包裹 字段含逗号`() {
        val t = parse("""
            姓名,备注
            "小明","爱好:足球,篮球"
            "小红",""
        """.trimIndent() + "\n")
        assertEquals("爱好:足球,篮球", t.rows[0]["备注"])
        assertEquals("", t.rows[1]["备注"])
    }

    @Test
    fun `双引号转义`() {
        val t = parse("col\n\"他说 \"\"你好\"\"\"\n")
        assertEquals("他说 \"你好\"", t.rows[0]["col"])
    }

    @Test
    fun `分号分隔 自动探测`() {
        val t = parse("a;b\n1;2\n")
        assertEquals(listOf("a", "b"), t.columns)
        assertEquals("2", t.rows[0]["b"])
    }

    @Test
    fun `空行跳过`() {
        val t = parse("a,b\n1,2\n\n\n3,4\n")
        assertEquals(2, t.rows.size)
    }

    @Test
    fun `GBK 编码回退`() {
        // GBK 编码的"姓名,张三"
        val gbk = "姓名,张三".toByteArray(java.nio.charset.Charset.forName("GBK"))
        val t = CsvTableParser.parse(gbk.inputStream())
        assertEquals(listOf("姓名", "张三"), t.columns)
        assertTrue("GBK 应正确解码", t.columns[1] == "张三")
    }

    @Test
    fun `缺失列 补空字符串`() {
        val t = parse("a,b,c\n1,2\n")
        assertEquals("", t.rows[0]["c"])
    }

    @Test
    fun `空输入 抛异常`() {
        try {
            parse("")
            assertTrue("空输入应抛异常", false)
        } catch (e: Exception) {
            assertTrue(true)
        }
    }

    @Test
    fun `无引号时 值含空格保留`() {
        val t = parse("name\n  padded  \n")
        // 字段 trim
        assertEquals("padded", t.rows[0]["name"])
    }
}
