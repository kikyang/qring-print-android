package com.qring.print

import com.google.zxing.BarcodeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 条码生成测试（2026-08-27 加）：
 * 13 种 zxing 可写码制在列 / 各码制合法内容校验通过且可编码 / EAN-UPC 校验位补全与核验 /
 * 输入清洗（Code39 大写、ITF 补位、Codabar 去空白）/ 非法内容被拒。
 */
@RunWith(RobolectricTestRunner::class)
class BarcodeGeneratorTest {

    private val types = BarcodeGenerator.TYPES

    private fun type(f: BarcodeFormat): BarcodeGenerator.BarcodeType =
        types.first { it.format == f }

    // 各码制典型合法内容
    private val samples: Map<BarcodeFormat, String> = mapOf(
        BarcodeFormat.QR_CODE to "https://example.com/test",
        BarcodeFormat.CODE_128 to "ABC-123",
        BarcodeFormat.CODE_39 to "ABC-123",
        BarcodeFormat.CODE_93 to "ABC-123",
        BarcodeFormat.EAN_13 to "400638133393",   // 12 位，自动补校验位
        BarcodeFormat.EAN_8 to "9638507",          // 7 位，自动补校验位
        BarcodeFormat.UPC_A to "03600029145",      // 11 位，自动补校验位
        BarcodeFormat.UPC_E to "0425261",         // 7 位，自动补校验位(应为 0)
        BarcodeFormat.ITF to "123456",
        BarcodeFormat.CODABAR to "A12345B",
        BarcodeFormat.DATA_MATRIX to "Hello 123",
        BarcodeFormat.AZTEC to "Hello 123",
        BarcodeFormat.PDF_417 to "Hello 123",
    )

    @Test
    fun `TYPES 含全部 13 种可写码制 且格式唯一`() {
        assertEquals(13, types.size)
        assertEquals(13, types.map { it.format }.toSet().size)
        types.forEach { assertTrue("${it.label} 提示文案非空", it.hint.isNotBlank()) }
    }

    @Test
    fun `各码制 合法内容 校验通过且可编码`() {
        for (t in types) {
            val sample = samples[t.format] ?: continue
            assertNull("${t.label} 应校验通过：$sample", BarcodeGenerator.validate(t, sample))
            val bmp = BarcodeGenerator.encodeBitmap(t, sample)
            assertTrue("${t.label} 应产出非空位图", bmp.width > 0 && bmp.height > 0)
        }
    }

    @Test
    fun `EAN-13 校验位 补全 核验 错误`() {
        val t = type(BarcodeFormat.EAN_13)
        assertNull(BarcodeGenerator.validate(t, "400638133393"))          // 12 位，自动补校验
        assertNull(BarcodeGenerator.validate(t, "4006381333931"))         // 13 位，校验位正确
        val bad = BarcodeGenerator.validate(t, "4006381333932")           // 13 位，校验位错
        assertNotNull(bad); assertTrue(bad!!.contains("校验位应为 1"))
        assertEquals("EAN-13 需 12~13 位数字", BarcodeGenerator.validate(t, "40063813339"))  // 11 位
    }

    @Test
    fun `EAN-8 校验位 补全 核验`() {
        val t = type(BarcodeFormat.EAN_8)
        assertNull(BarcodeGenerator.validate(t, "9638507"))                // 7 位
        assertNull(BarcodeGenerator.validate(t, "96385074"))               // 8 位，正确
        val bad = BarcodeGenerator.validate(t, "96385078")                 // 8 位，错(应为 4)
        assertNotNull(bad); assertTrue(bad!!.contains("校验位应为 4"))
    }

    @Test
    fun `UPC-A 校验位 补全 核验`() {
        val t = type(BarcodeFormat.UPC_A)
        assertNull(BarcodeGenerator.validate(t, "03600029145"))            // 11 位
        assertNull(BarcodeGenerator.validate(t, "036000291452"))           // 12 位，正确
        val bad = BarcodeGenerator.validate(t, "036000291453")             // 12 位，错(应为 2)
        assertNotNull(bad); assertTrue(bad!!.contains("校验位应为 2"))
    }

    @Test
    fun `UPC-E 校验位 补全 核验`() {
        val t = type(BarcodeFormat.UPC_E)
        assertNull(BarcodeGenerator.validate(t, "0425261"))              // 7 位，自动补
        assertNull(BarcodeGenerator.validate(t, "04252610"))             // 8 位，校验位正确(0)
        val bad = BarcodeGenerator.validate(t, "04252611")               // 8 位，错(应为 0)
        assertNotNull(bad); assertTrue(bad!!.contains("校验位应为 0"))
    }

    @Test
    fun `clean 输入清洗`() {
        assertEquals("ABC-123", BarcodeGenerator.clean(type(BarcodeFormat.CODE_39), "abc-123"))
        assertEquals("ABCDEF", BarcodeGenerator.clean(type(BarcodeFormat.CODE_93), "abcdef"))
        assertEquals("012345", BarcodeGenerator.clean(type(BarcodeFormat.ITF), "12345"))  // 奇数补前导0
        assertEquals("A12345B", BarcodeGenerator.clean(type(BarcodeFormat.CODABAR), " a12345b "))
        assertEquals("400638133393", BarcodeGenerator.clean(type(BarcodeFormat.EAN_13), "400638-133393"))
    }

    @Test
    fun `非法内容 被拒`() {
        // 空内容
        assertEquals("内容为空", BarcodeGenerator.validate(type(BarcodeFormat.QR_CODE), "  "))
        // Code128 非 ASCII
        assertNotNull(BarcodeGenerator.validate(type(BarcodeFormat.CODE_128), "中文内容"))
        // Code39 含非法字符（*）
        assertNotNull(BarcodeGenerator.validate(type(BarcodeFormat.CODE_39), "AB*C"))
        // EAN-13 长度不足（纯字母被清洗成空串 → 空；用数字长度用例）
        assertEquals("EAN-13 需 12~13 位数字", BarcodeGenerator.validate(type(BarcodeFormat.EAN_13), "123"))
        assertEquals("内容为空", BarcodeGenerator.validate(type(BarcodeFormat.EAN_13), "abcdefghijk"))
        // 二维码超长（>2000 字节）
        val long = "a".repeat(2600)
        assertNotNull(BarcodeGenerator.validate(type(BarcodeFormat.QR_CODE), long))
        // DataMatrix 超长
        assertNotNull(BarcodeGenerator.validate(type(BarcodeFormat.DATA_MATRIX), long))
    }

    @Test
    fun `各码制 校验与编码 输入一致`() {
        // 校验通过的内容，编码一定使用清洗后版本（不会因清洗导致编码失败）
        for (t in types) {
            val sample = samples[t.format] ?: continue
            val cleaned = BarcodeGenerator.clean(t, sample)
            assertEquals("${t.label} 校验通过", null, BarcodeGenerator.validate(t, sample))
            val bmp = BarcodeGenerator.encodeBitmap(t, cleaned)
            assertTrue("${t.label} 清洗后编码成功", bmp.width > 0 && bmp.height > 0)
        }
    }
}
