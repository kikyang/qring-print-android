package com.qring.print

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 虚拟打印机协议引擎单测（2026-08-13 加）：FakePrinter 的字节流状态机。
 * 纯 JVM 无 Android 依赖——覆盖查询应答、光栅解析（含分包边界）、
 * 故障注入、坏头校验、未知字节容错。
 */
class FakePrinterTest {

    private val status = byteArrayOf(0x10, 0xFF.toByte(), 0x40)
    private val batteryCmd = byteArrayOf(0x10, 0xFF.toByte(), 0x50, 0xF1.toByte())
    private val modelCmd = byteArrayOf(0x10, 0xFF.toByte(), 0x20, 0xF0.toByte())
    private val fwCmd = byteArrayOf(0x10, 0xFF.toByte(), 0x20, 0xF1.toByte())
    private val snCmd = byteArrayOf(0x10, 0xFF.toByte(), 0x20, 0xF2.toByte())
    private val btVerCmd = byteArrayOf(0x10, 0xFF.toByte(), 0x30, 0x10)
    private val btMacCmd = byteArrayOf(0x10, 0xFF.toByte(), 0x30, 0x12)
    private val deviceInfoCmd = byteArrayOf(0x10, 0xFF.toByte(), 0x70)
    private val enable = byteArrayOf(0x10, 0xFF.toByte(), 0xF1.toByte(), 0x02)
    private val stop = byteArrayOf(0x10, 0xFF.toByte(), 0xF1.toByte(), 0x45)

    private fun ascii(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

    // ── 查询应答 ────────────────────────────────────────────────

    @Test
    fun `状态查询应答健康状态字节`() {
        val p = FakePrinter()
        assertArrayEquals(byteArrayOf(0x00), p.feed(status))
    }

    @Test
    fun `故障位注入进状态字节`() {
        val p = FakePrinter()
        p.noPaper = true
        assertArrayEquals(byteArrayOf(0x04), p.feed(status))
        p.coverOpen = true  // 开盖时缺纸位也置起（纸传感器看不到纸）
        assertArrayEquals(byteArrayOf(0x02 or 0x04), p.feed(status))
        p.noPaper = false
        p.overheat = true
        assertArrayEquals(byteArrayOf(0x02 or 0x10), p.feed(status))
    }

    @Test
    fun `电量应答第二字节是百分比`() {
        val p = FakePrinter()
        p.battery = 63
        val resp = p.feed(batteryCmd)
        assertEquals(2, resp.size)
        assertEquals(63, resp[1].toInt())
    }

    @Test
    fun `字符串查询应答`() {
        val p = FakePrinter()
        assertArrayEquals(ascii("Qring-X1"), p.feed(modelCmd))
        assertArrayEquals(ascii("V1.05"), p.feed(fwCmd))
        assertArrayEquals(ascii("X1SN000001"), p.feed(snCmd))
        assertArrayEquals(ascii("BT5.1"), p.feed(btVerCmd))
        assertArrayEquals(ascii("AA:BB:CC:DD:EE:FF"), p.feed(btMacCmd))
    }

    @Test
    fun `设备信息竖线分段解析用格式`() {
        val p = FakePrinter()
        val text = String(p.feed(deviceInfoCmd), Charsets.US_ASCII)
        val parts = text.split("|")
        assertEquals(6, parts.size)
        assertEquals("Qring-X1", parts[0])   // 型号
        assertEquals("V1.05", parts[3])       // 固件版本（客户端取第 4 段）
    }

    @Test
    fun `查询无响应模式静默`() {
        val p = FakePrinter()
        p.respondToQueries = false  // SPP 单向通道
        assertEquals(0, p.feed(status).size)
        assertEquals(0, p.feed(batteryCmd).size)
        assertEquals(0, p.feed(deviceInfoCmd).size)
    }

    // ── 命令解析与记录 ─────────────────────────────────────────

    @Test
    fun `命令按发送时序记录`() {
        val p = FakePrinter()
        p.feed(stop)
        p.feed(enable)
        p.feed(byteArrayOf(0x10, 0xFF.toByte(), 0x10, 0x00, 0x02))
        p.feed(ByteArray(12))          // WAKEUP 零字节：不记录
        p.feed(byteArrayOf(0x1B, 0x40))
        p.feed(byteArrayOf(0x1B, 0x4A, 0x0A))

        val seq = p.receivedCommands
        assertEquals(5, seq.size)
        assertArrayEquals(stop, seq[0])
        assertArrayEquals(enable, seq[1])
        assertArrayEquals(byteArrayOf(0x10, 0xFF.toByte(), 0x10, 0x00, 0x02), seq[2])
        assertArrayEquals(byteArrayOf(0x1B, 0x40), seq[3])
        assertArrayEquals(byteArrayOf(0x1B, 0x4A, 0x0A), seq[4])
    }

    @Test
    fun `命令跨分包边界解析`() {
        // 一条 10 FF 40 拆成 3 个 write（模拟 BLE 32B 分包下短命令也会拆）
        val p = FakePrinter()
        assertEquals(0, p.feed(byteArrayOf(0x10)).size)
        assertEquals(0, p.feed(byteArrayOf(0xFF.toByte())).size)
        assertArrayEquals(byteArrayOf(0x00), p.feed(byteArrayOf(0x40)))
    }

    // ── 光栅解析 ───────────────────────────────────────────────

    private fun rasterHeader(mode: Int, height: Int) = byteArrayOf(
        0x1D, 0x76, 0x30, mode.toByte(), 48, 0, height.toByte(), (height shr 8).toByte()
    )

    @Test
    fun `光栅块解析与数据完整接收`() {
        val p = FakePrinter()
        val h = 3
        val data = ByteArray(48 * h) { (it % 251).toByte() }
        // 头与数据分开发送（真实客户端：send(header) 后 send(data)）
        assertEquals(0, p.feed(rasterHeader(0, h)).size)
        assertEquals(0, p.feed(data).size)

        assertEquals(1, p.rasterBlocks.size)
        val block = p.rasterBlocks[0]
        assertEquals(0, block.mode)
        assertEquals(48, block.widthBytes)
        assertEquals(h, block.height)
        assertArrayEquals(data, block.data)
    }

    @Test
    fun `光栅数据跨任意字节边界`() {
        val p = FakePrinter()
        val h = 2
        val data = ByteArray(48 * h) { it.toByte() }
        // 头拆 3 段、数据拆 5 段乱喂
        p.feed(byteArrayOf(0x1D))
        p.feed(byteArrayOf(0x76, 0x30))
        p.feed(byteArrayOf(0x00, 48, 0, h.toByte(), 0))
        for (i in 0 until 5) {
            p.feed(data.copyOfRange(i * 20, minOf((i + 1) * 20, data.size)))
        }
        assertEquals(1, p.rasterBlocks.size)
        assertArrayEquals(data, p.rasterBlocks[0].data)
    }

    @Test
    fun `多块光栅连续接收`() {
        val p = FakePrinter()
        val h1 = 64
        val h2 = 36
        p.feed(rasterHeader(0, h1))
        p.feed(ByteArray(48 * h1))
        p.feed(rasterHeader(0, h2))
        p.feed(ByteArray(48 * h2))
        assertEquals(2, p.rasterBlocks.size)
        assertEquals(64, p.rasterBlocks[0].height)
        assertEquals(36, p.rasterBlocks[1].height)
    }

    @Test
    fun `宽度错误记校验错误且数据照收`() {
        val p = FakePrinter()
        val bad = byteArrayOf(0x1D, 0x76, 0x30, 0x00, 47, 0, 1, 0)  // wb=47 ≠ 48
        p.feed(bad)
        p.feed(ByteArray(47))  // 数据按坏头声明的 47 字节收
        assertEquals(1, p.rasterBlocks.size)
        assertEquals(1, p.rasterErrors.size)
        assertTrue(p.rasterErrors[0].contains("widthBytes=47"))
    }

    @Test
    fun `高度0记校验错误`() {
        val p = FakePrinter()
        p.feed(byteArrayOf(0x1D, 0x76, 0x30, 0x00, 48, 0, 0, 0))
        assertTrue(p.rasterErrors.any { it.contains("height=0") })
    }

    // ── 打印任务 ACK / 故障帧 ─────────────────────────────────

    @Test
    fun `完整打印任务回ACK`() {
        val p = FakePrinter()
        p.feed(stop)
        p.feed(enable)
        p.feed(rasterHeader(0, 2))
        p.feed(ByteArray(48 * 2))
        val resp = p.feed(stop)
        assertArrayEquals(byteArrayOf(0xAA.toByte()), resp)
        assertEquals(1, p.acksSent.size)
    }

    @Test
    fun `裸STOP不应答`() {
        val p = FakePrinter()
        assertEquals(0, p.feed(stop).size)  // 无光栅任务：复位不回应答
    }

    @Test
    fun `打印中缺纸回故障帧`() {
        val p = FakePrinter()
        p.noPaper = true
        p.feed(enable)
        p.feed(rasterHeader(0, 1))
        p.feed(ByteArray(48))
        val resp = p.feed(stop)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x01), resp)  // FF 01 = 缺纸
    }

    @Test
    fun `开盖与缺纸同时置位时故障帧优先开盖`() {
        val p = FakePrinter()
        p.coverOpen = true
        p.noPaper = true
        p.feed(enable)
        p.feed(rasterHeader(0, 1))
        p.feed(ByteArray(48))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x02), p.feed(stop))
    }

    @Test
    fun `光栅校验错误不回ACK`() {
        val p = FakePrinter()
        p.feed(enable)
        p.feed(byteArrayOf(0x1D, 0x76, 0x30, 0x00, 47, 0, 1, 0))
        p.feed(ByteArray(47))
        assertEquals(0, p.feed(stop).size)  // 坏数据：打印机不应答，客户端超时
    }

    @Test
    fun `ackPrint关掉后打印结束无应答`() {
        val p = FakePrinter()
        p.ackPrint = false
        p.feed(enable)
        p.feed(rasterHeader(0, 1))
        p.feed(ByteArray(48))
        assertEquals(0, p.feed(stop).size)
    }

    @Test
    fun `reportFaults关掉后故障也不上报`() {
        val p = FakePrinter()
        p.noPaper = true
        p.reportFaults = false
        p.feed(enable)
        p.feed(rasterHeader(0, 1))
        p.feed(ByteArray(48))
        assertArrayEquals(byteArrayOf(0xAA.toByte()), p.feed(stop))
    }

    @Test
    fun `ENABLE置打印中位STOP清除`() {
        val p = FakePrinter()
        assertFalse(p.printing)
        p.feed(enable)
        assertTrue(p.printing)
        assertArrayEquals(byteArrayOf(0x01), p.feed(status))  // 状态字节带打印中位
        p.feed(stop)
        assertFalse(p.printing)
    }

    // ── 容错 ───────────────────────────────────────────────────

    @Test
    fun `未知字节与未知命令被容错吞掉`() {
        val p = FakePrinter()
        p.feed(byteArrayOf(0x55, 0x1B, 0x7F))                       // 无意义的单字节
        p.feed(byteArrayOf(0x10, 0xFF.toByte(), 0x99.toByte()))     // 未知 opcode
        assertTrue(p.unknownCount >= 3)
        // 容错后引擎还能正常应答
        assertArrayEquals(byteArrayOf(0x00), p.feed(status))
    }
}
