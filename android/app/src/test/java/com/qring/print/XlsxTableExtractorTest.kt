package com.qring.print

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * XlsxTableExtractor 结构化解析测试（2026-08-27 加）：
 * 内存构造最小 xlsx（zip 包：sharedStrings.xml + sheet1.xml），
 * 验证首行列名 + 记录 Map + 跳列占位。
 */
@RunWith(RobolectricTestRunner::class)
class XlsxTableExtractorTest {

    /** 构造最小 xlsx 的 zip 字节（用真实 contentTypes/rels 最小子集） */
    private fun buildXlsx(
        shared: List<String>,
        rowsXml: String,
    ): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            // 最小工作簿骨架（不校验 contentTypes，extract 只读 sharedStrings + sheet1）
            put("xl/sharedStrings.xml", buildSharedXml(shared))
            put("xl/worksheets/sheet1.xml", rowsXml)
        }
        return bos.toByteArray()
    }

    private fun buildSharedXml(shared: List<String>): String {
        val items = shared.joinToString("") { "<si><t>${it.replace("&", "&amp;").replace("<", "&lt;")}</t></si>" }
        return """<?xml version="1.0"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${shared.size}" uniqueCount="${shared.size}">$items</sst>"""
    }

    private fun cell(ref: String, type: String?, v: String): String =
        if (type == null) "<c r=\"$ref\"><v>$v</v></c>"
        else "<c r=\"$ref\" t=\"$type\"><v>$v</v></c>"

    private fun extract(bytes: ByteArray): XlsxTableExtractor.Table {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 写入缓存文件，用 file:// Uri
        val f = java.io.File(context.cacheDir, "test_${System.nanoTime()}.xlsx")
        f.writeBytes(bytes)
        val uri = Uri.fromFile(f)
        return XlsxTableExtractor.extract(context, uri)
    }

    @Test
    fun `基本解析 首行列名 字符串单元格`() {
        val rows = "<sheetData>" +
            "<row><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c><c r=\"C1\" t=\"s\"><v>2</v></c></row>" +
            "<row><c r=\"A2\" t=\"s\"><v>3</v></c><c r=\"B2\" t=\"s\"><v>4</v></c><c r=\"C2\" t=\"s\"><v>5</v></c></row>" +
            "</sheetData>"
        val bytes = buildXlsx(listOf("姓名", "成绩", "科目", "小明", "90", "数学"), rows)
        val t = extract(bytes)
        assertEquals(listOf("姓名", "成绩", "科目"), t.columns)
        assertEquals(1, t.rows.size)
        assertEquals("小明", t.rows[0]["姓名"])
        assertEquals("90", t.rows[0]["成绩"])
        assertEquals("数学", t.rows[0]["科目"])
    }

    @Test
    fun `数字单元格 直取`() {
        val rows = "<sheetData>" +
            "<row><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>" +
            "<row><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"B2\"><v>95</v></c></row>" +
            "</sheetData>"
        val bytes = buildXlsx(listOf("姓名", "成绩", "小红"), rows)
        val t = extract(bytes)
        assertEquals("95", t.rows[0]["成绩"])
        assertEquals("小红", t.rows[0]["姓名"])
    }

    @Test
    fun `跳列占位 A到C 中间空`() {
        val rows = "<sheetData>" +
            "<row><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"C1\" t=\"s\"><v>1</v></c></row>" +
            "<row><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"C2\" t=\"s\"><v>3</v></c></row>" +
            "</sheetData>"
        val bytes = buildXlsx(listOf("姓名", "备注", "小明", "好"), rows)
        val t = extract(bytes)
        assertEquals(listOf("姓名", "备注"), t.columns)  // 空白列名跳过，不产生"列N"占位
        assertEquals("小明", t.rows[0]["姓名"])
        assertEquals("好", t.rows[0]["备注"])
    }

    @Test
    fun `空工作表 抛异常`() {
        val rows = "<sheetData></sheetData>"
        val bytes = buildXlsx(emptyList(), rows)
        try {
            extract(bytes)
            assertTrue("空表应抛异常", false)
        } catch (e: Exception) {
            assertTrue(true)
        }
    }

    @Test
    fun `多列等宽 记录对齐`() {
        val rows = "<sheetData>" +
            "<row><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c><c r=\"C1\" t=\"s\"><v>2</v></c></row>" +
            "<row><c r=\"A2\" t=\"s\"><v>3</v></c><c r=\"B2\" t=\"s\"><v>4</v></c><c r=\"C2\" t=\"s\"><v>5</v></c></row>" +
            "<row><c r=\"A3\" t=\"s\"><v>6</v></c><c r=\"B3\" t=\"s\"><v>7</v></c><c r=\"C3\" t=\"s\"><v>8</v></c></row>" +
            "</sheetData>"
        val bytes = buildXlsx(listOf("a", "b", "c", "1", "2", "3", "4", "5", "6"), rows)
        val t = extract(bytes)
        assertEquals(2, t.rows.size)
        assertEquals("4", t.rows[1]["a"])
        assertEquals("6", t.rows[1]["c"])
    }
}
