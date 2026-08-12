package com.qring.print

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议层单元测试（2026-08-12 补）：状态位解析、指令字节构造、走纸/光栅头拆分。
 * 纯 JVM 无 Android 依赖——防协议回归（X1 实测过的字节序列）。
 */
class QringProtocolTest {

    // ── 状态解析 ────────────────────────────────────────────────

    @Test
    fun `状态0表示健康`() {
        val s = parseStatus(0)
        assertTrue(s.isHealthy)
        assertFalse(s.printing)
        assertFalse(s.coverOpen)
        assertFalse(s.noPaper)
        assertFalse(s.lowBattery)
        assertFalse(s.overheat)
    }

    @Test
    fun `各个状态位独立解析`() {
        assertTrue(parseStatus(ST_PRINTING).printing)
        assertTrue(parseStatus(ST_COVER_OPEN).coverOpen)
        assertTrue(parseStatus(ST_NO_PAPER).noPaper)
        assertTrue(parseStatus(ST_LOW_BATTERY).lowBattery)
        assertTrue(parseStatus(ST_OVERHEAT).overheat)
    }

    @Test
    fun `多状态位组合解析`() {
        val s = parseStatus(ST_PRINTING or ST_NO_PAPER)
        assertTrue(s.printing)
        assertTrue(s.noPaper)
        assertFalse(s.isHealthy)
    }

    @Test
    fun `FaultCode 从字节解析与回退`() {
        assertEquals(FaultCode.NO_PAPER, FaultCode.from(0x01))
        assertEquals(FaultCode.COVER_OPEN, FaultCode.from(0x02))
        assertEquals(FaultCode.OVERHEAT, FaultCode.from(0x03))
        assertEquals(FaultCode.LOW_BATTERY, FaultCode.from(0x04))
        assertNull(FaultCode.from(0x99))
    }

    // ── 体检文案优先级（回归：开盖必须排在缺纸前面） ──────────────

    @Test
    fun `开盖与缺纸同时置位时提示开盖`() {
        val msg = faultMessage(parseStatus(ST_COVER_OPEN or ST_NO_PAPER))
        assertEquals("机器未合盖，请检查机器", msg)
    }

    @Test
    fun `缺纸单独提示缺纸`() {
        assertEquals("机器缺纸，请检查纸张装配", faultMessage(parseStatus(ST_NO_PAPER)))
    }

    @Test
    fun `过热单独提示过热`() {
        assertEquals("机器过热，请稍候再尝试打印", faultMessage(parseStatus(ST_OVERHEAT)))
    }

    @Test
    fun `健康状态无提示`() {
        assertNull(faultMessage(parseStatus(0)))
    }

    // ── 指令构造 ────────────────────────────────────────────────

    @Test
    fun `浓度指令字节序列`() {
        assertArrayEquals(
            byteArrayOf(0x10, 0xFF.toByte(), 0x10, 0x00, 2),
            cmdThickness(2)
        )
    }

    @Test
    fun `自动关机时间大端编码`() {
        // 300 秒 = 0x012C
        assertArrayEquals(
            byteArrayOf(0x10, 0xFF.toByte(), 0x12, 0x01, 0x2C),
            cmdShutdownTime(300)
        )
        // 60 秒 = 0x003C
        assertArrayEquals(
            byteArrayOf(0x10, 0xFF.toByte(), 0x12, 0x00, 0x3C),
            cmdShutdownTime(60)
        )
    }

    @Test
    fun `走纸单字节上限拆分`() {
        assertEquals(1, cmdFeed(1).size)
        assertEquals(1, cmdFeed(255).size)
        // 256 → 255 + 1 两条
        val two = cmdFeed(256)
        assertEquals(2, two.size)
        assertArrayEquals(byteArrayOf(0x1B, 0x4A, 0xFF.toByte()), two[0])
        assertArrayEquals(byteArrayOf(0x1B, 0x4A, 0x01), two[1])
        // 300 → 255 + 45
        val three = cmdFeed(300)
        assertEquals(2, three.size)
        assertArrayEquals(byteArrayOf(0x1B, 0x4A, 0xFF.toByte()), three[0])
        assertArrayEquals(byteArrayOf(0x1B, 0x4A, 0x2D), three[1])
    }

    @Test
    fun `走纸0返回空列表`() {
        assertTrue(cmdFeed(0).isEmpty())
    }

    @Test
    fun `光栅头字节序与宽度拆分`() {
        // 384 点 = 48 字节，小端：48, 0
        val h1 = cmdRasterHeader(WIDTH_BYTES, 100, 2)
        assertEquals(8, h1.size)
        assertArrayEquals(byteArrayOf(0x1D, 0x76, 0x30, 0x02, 48, 0, 100, 0), h1)
        // 高度 300 → 300, 0；宽度 256 字节 → 0, 1
        val h2 = cmdRasterHeader(256, 300, 3)
        assertArrayEquals(byteArrayOf(0x1D, 0x76, 0x30, 0x03, 0, 1, 44, 1), h2)
    }

    @Test
    fun `光栅mode超出2位被截断`() {
        val h = cmdRasterHeader(48, 10, 7)   // 7 & 3 = 3
        assertEquals(3, h[3].toInt())
    }

    // ── 关键常量（X1 实测序列，防手滑改坏） ──────────────────────

    @Test
    fun `查询命令常量`() {
        assertArrayEquals(byteArrayOf(0x10, 0xFF.toByte(), 0x40), CMD_STATUS)
        assertArrayEquals(byteArrayOf(0x10, 0xFF.toByte(), 0x50, 0xF1.toByte()), CMD_BATTERY)
        assertArrayEquals(byteArrayOf(0x10, 0xFF.toByte(), 0x20, 0xF1.toByte()), CMD_FW_VERSION)
        assertEquals(12, CMD_WAKEUP.size)
        assertTrue(CMD_WAKEUP.all { it == 0.toByte() })
    }

    @Test
    fun `宽度常量匹配打印头`() {
        assertEquals(384, WIDTH_DOTS)
        assertEquals(48, WIDTH_BYTES)
        assertEquals(48 * 8, WIDTH_DOTS)
    }
}
