package com.qring.print

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 批量模板占位符绑定测试（2026-08-27 加）：
 * 基本替换 / 流水号开关 / 列名优先 / 未匹配占位符保留 / 空值 / 值含占位符样式文本。
 */
@RunWith(RobolectricTestRunner::class)
class BatchTemplateTest {

    private val row = mapOf(
        "姓名" to "小明",
        "电话" to "13800138000",
        "备注" to "",
    )

    @Test
    fun `基本替换 多列`() {
        val out = BatchTemplate.bind("姓名：{{姓名}}\n电话：{{电话}}", row, serial = 1, serialEnabled = false)
        assertEquals("姓名：小明\n电话：13800138000", out)
    }

    @Test
    fun `流水号 开启 从1递增`() {
        assertEquals("第1条", BatchTemplate.bind("第{{序号}}条", row, serial = 1, serialEnabled = true))
        assertEquals("第2条", BatchTemplate.bind("第{{序号}}条", row, serial = 2, serialEnabled = true))
    }

    @Test
    fun `流水号 关闭 占位符保留`() {
        assertEquals("第{{序号}}条", BatchTemplate.bind("第{{序号}}条", row, serial = 1, serialEnabled = false))
    }

    @Test
    fun `列名为序号 列值优先于流水号`() {
        val r = mapOf("序号" to "A-01", "姓名" to "小明")
        // 先按列名替换 {{序号}} → A-01，流水号注入不再命中
        assertEquals("A-01 小明", BatchTemplate.bind("{{序号}} {{姓名}}", r, serial = 5, serialEnabled = true))
    }

    @Test
    fun `空值替换为空`() {
        assertEquals("备注[]", BatchTemplate.bind("备注[{{备注}}]", row, serial = 1, serialEnabled = false))
    }

    @Test
    fun `未匹配占位符 保留原文`() {
        // 列名写错（"名字" 不存在），保留 {{名字}} 提醒用户
        assertEquals("{{名字}}", BatchTemplate.bind("{{名字}}", row, serial = 1, serialEnabled = false))
    }

    @Test
    fun `同名列出现多次 全部替换`() {
        assertEquals("小明|小明", BatchTemplate.bind("{{姓名}}|{{姓名}}", row, serial = 1, serialEnabled = false))
    }

    @Test
    fun `空模板原样返回`() {
        assertEquals("", BatchTemplate.bind("", row, serial = 1, serialEnabled = true))
    }
}
