package com.qring.print

import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothDevice

/**
 * 虚拟打印机端到端测试（2026-08-13 加）：
 * 把「连接 → 探测 → 唤醒 → 体检 → 光栅分包 → 状态拦截」整条链路
 * 在 JVM 里跑真代码——打印时序走 PrintJobRunner（与 BLE/SPP 真机连接同一份代码），
 * 只有收发层换成 FakePrinter 协议引擎。
 *
 * 收益：实物联调从 7 步全未知缩成 2 个物理未知量（GATT 写特征 + 热敏头物理行为）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FakePrinterE2ETest {

    private lateinit var device: BluetoothDevice

    @Before
    fun setUp() {
        device = ShadowBluetoothDevice.newInstance("AA:BB:CC:DD:EE:FF")
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        PrinterHolder.init(ctx)
        PrinterHolder.injectForTest(null, null)  // 清上次注入
        PrintJobRunner.ackTimeoutMs = PrintJobRunner.DEFAULT_ACK_TIMEOUT_MS  // 恢复默认
    }

    @After
    fun tearDown() {
        PrinterHolder.injectForTest(null, null)
        PrintJobRunner.ackTimeoutMs = PrintJobRunner.DEFAULT_ACK_TIMEOUT_MS
    }

    /** 构造 48 字节/行、每行第 (y % 8) 列置黑的已知光栅（端到端可校验内容） */
    private fun knownRaster(rows: Int): RasterData {
        val data = ByteArray(WIDTH_BYTES * rows)
        for (y in 0 until rows) {
            val bit = y % 8
            data[y * WIDTH_BYTES + (bit shr 3)] =
                (data[y * WIDTH_BYTES + (bit shr 3)].toInt() or (0x80 shr (bit and 7))).toByte()
        }
        return RasterData(WIDTH_BYTES, rows, data)
    }

    // ── 全链路 happy path ─────────────────────────────────────

    @Test
    fun `连接到打印全链路`() = runBlocking {
        val printer = FakePrinter()
        val conn: PrinterConnection = FakePrinterConnection(printer)

        // 连接 → 设备信息
        assertTrue(conn.connect(device))
        assertTrue(conn.connected)
        assertEquals("Qring-X1", conn.deviceModel)
        assertEquals("V1.05", conn.firmwareVersion)
        assertEquals("BT5.1", conn.btVersion)
        assertEquals("AA:BB:CC:DD:EE:FF", conn.btMac)

        // 状态 / 电量查询
        val status = conn.queryStatus()
        assertNotNull(status)
        assertTrue(status!!.isHealthy)
        assertEquals(80, conn.queryBattery())

        // 体检放行
        assertNull(conn.preflightCheck())

        // 打印（文字通道 m=0，不合并行）
        val raster = knownRaster(10)
        val prePrint = printer.receivedCommands.size  // 连接阶段查询命令不入打印时序断言
        val result = conn.printRaster(raster, mode = 0)
        assertTrue("打印应成功: ${result.message}", result.ok)

        // 打印机侧收到完整时序：STOP→ENABLE→浓度→ESC@→前走纸→光栅头→数据→后走纸→STOP
        val seq = printer.receivedCommands.drop(prePrint)
        assertArrayEquals(CMD_STOP, seq[0])
        assertArrayEquals(CMD_ENABLE, seq[1])
        assertArrayEquals(byteArrayOf(0x10, 0xFF.toByte(), 0x10, 0x00, 0x02), seq[2])  // 默认浓度 2
        assertArrayEquals(byteArrayOf(0x1B, 0x40), seq[3])                              // ESC @
        assertArrayEquals(byteArrayOf(0x1B, 0x4A, 10), seq[4])                          // 前走纸 10
        assertArrayEquals(
            byteArrayOf(0x1D, 0x76, 0x30, 0x00, 48, 0, 10, 0), seq[5]                   // GS v 0 头
        )
        assertArrayEquals(byteArrayOf(0x1B, 0x4A, 100), seq[6])                         // 后走纸 100
        assertArrayEquals(CMD_STOP, seq[7])

        // 光栅数据端到端一致（打印机收到的 == 客户端发的）
        assertEquals(1, printer.rasterBlocks.size)
        val block = printer.rasterBlocks[0]
        assertEquals(48, block.widthBytes)
        assertEquals(10, block.height)
        assertArrayEquals(raster.data, block.data)
        assertArrayEquals(byteArrayOf(0xAA.toByte()), printer.acksSent[0])

        // 打印完成后 refreshAll 已刷状态/电量
        assertNotNull(conn.lastStatus)
        assertNotNull(conn.batteryPercent)
    }

    @Test
    fun `图片通道行合并减半后双打`() = runBlocking {
        val printer = FakePrinter()
        val conn: PrinterConnection = FakePrinterConnection(printer)
        conn.connect(device)

        // 4 行光栅：行1 字节0=0x01，行3 字节0=0x02，其余全 0
        val data = ByteArray(48 * 4)
        data[48 + 0] = 0x01
        data[48 * 3 + 0] = 0x02
        val raster = RasterData(48, 4, data)

        val result = conn.printRaster(raster, mode = 2, halveRows = true)
        assertTrue("打印应成功: ${result.message}", result.ok)

        // halveRows：2 行 OR 1 行 → (行0|行1)=0x01，(行2|行3)=0x02
        assertEquals(1, printer.rasterBlocks.size)
        val block = printer.rasterBlocks[0]
        assertEquals(2, block.height)
        assertEquals(0x01, block.data[0].toInt())
        assertEquals(0x02, block.data[48].toInt())
    }

    @Test
    fun `光栅按64行分块发送`() = runBlocking {
        val printer = FakePrinter()
        val conn: PrinterConnection = FakePrinterConnection(printer)
        conn.connect(device)

        // 100 行 = 64 + 36 两块
        val result = conn.printRaster(knownRaster(100), mode = 0)
        assertTrue("打印应成功: ${result.message}", result.ok)
        assertEquals(2, printer.rasterBlocks.size)
        assertEquals(64, printer.rasterBlocks[0].height)
        assertEquals(36, printer.rasterBlocks[1].height)
        // 两块数据拼接 == 原始数据
        val joined = printer.rasterBlocks[0].data + printer.rasterBlocks[1].data
        assertArrayEquals(knownRaster(100).data, joined)
    }

    // ── 故障注入：体检拦截 ────────────────────────────────────

    @Test
    fun `缺纸体检拦截`() = runBlocking {
        val printer = FakePrinter()
        printer.noPaper = true
        val conn: PrinterConnection = FakePrinterConnection(printer)
        conn.connect(device)

        assertEquals("机器缺纸，请检查纸张装配", conn.preflightCheck())
    }

    @Test
    fun `开盖优先于缺纸提示`() = runBlocking {
        val printer = FakePrinter()
        printer.coverOpen = true
        printer.noPaper = true  // 开盖时纸传感器看不到纸，缺纸位会同时置起
        val conn: PrinterConnection = FakePrinterConnection(printer)
        conn.connect(device)

        assertEquals("机器未合盖，请检查机器", conn.preflightCheck())
    }

    // ── 故障注入：打印中主动上报 ──────────────────────────────

    @Test
    fun `打印中开盖回故障帧`() = runBlocking {
        val printer = FakePrinter()
        val conn: PrinterConnection = FakePrinterConnection(printer)
        conn.connect(device)

        // 第 1 块光栅后注入开盖（模拟打印中途掀盖，确定性注入不靠协程竞态）
        printer.autoFaultAfterRasterBlock = 1
        printer.autoFaultCode = FaultCode.COVER_OPEN

        val result = conn.printRaster(knownRaster(100), mode = 0)
        assertFalse("打印应失败", result.ok)
        assertEquals("开盖", result.message)
        // 打印机侧确实发了 FF 02 故障帧
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0x02),
            printer.acksSent[0]
        )
    }

    @Test
    fun `打印中缺纸回故障帧`() = runBlocking {
        val printer = FakePrinter()
        val conn: PrinterConnection = FakePrinterConnection(printer)
        conn.connect(device)

        printer.autoFaultAfterRasterBlock = 1
        printer.autoFaultCode = FaultCode.NO_PAPER

        val result = conn.printRaster(knownRaster(10), mode = 0)
        assertFalse("打印应失败", result.ok)
        assertEquals("缺纸", result.message)
    }

    // ── 查询无响应（SPP 单向通道路径）─────────────────────────

    @Test
    fun `查询无响应时体检放行打印正常`() = runBlocking {
        val printer = FakePrinter()
        printer.respondToQueries = false  // SPP 单向通道：能打印、查询无响应
        val conn: PrinterConnection = FakePrinterConnection(printer, queryTimeoutMs = 100)  // 缩短查询超时
        conn.connect(device)

        // 型号查不到（显示 "?" 属预期，连接层字段留空）
        assertEquals("", conn.deviceModel)
        assertNull(conn.queryStatus())

        // 体检放行（查不到状态宁可让打印试一次，失败由 ACK 阶段故障帧兜住）
        assertNull(conn.preflightCheck())

        // 打印仍然成功（SPP 实测：查询无响应但打印正常出纸）
        val result = conn.printRaster(knownRaster(5), mode = 0)
        assertTrue("打印应成功: ${result.message}", result.ok)
    }

    // ── ACK 超时 ───────────────────────────────────────────────

    @Test
    fun `打印后无ACK走超时失败`() = runBlocking {
        val printer = FakePrinter()
        printer.ackPrint = false
        val conn: PrinterConnection = FakePrinterConnection(printer)
        conn.connect(device)

        // 缩短 ACK 超时：测试不真等 8 秒
        PrintJobRunner.ackTimeoutMs = { 500L }

        val result = conn.printRaster(knownRaster(1), mode = 0)
        assertFalse("打印应失败", result.ok)
        assertEquals("等待打印完成超时", result.message)
    }

    // ── 连接态守卫 ─────────────────────────────────────────────

    @Test
    fun `未连接直接打印被拒`() = runBlocking {
        val conn: PrinterConnection = FakePrinterConnection()
        val result = conn.printRaster(knownRaster(1), mode = 0)
        assertFalse(result.ok)
        assertEquals("打印机未连接", result.message)
    }

    @Test
    fun `打印中重入被拒`() = runBlocking {
        val printer = FakePrinter()
        val conn: PrinterConnection = FakePrinterConnection(printer)
        conn.connect(device)

        // 大光栅（200 行 ≈ 4 块，光栅阶段 > 600ms）挂后台，主线程稍后重入
        val job = launch(Dispatchers.Default) {
            conn.printRaster(knownRaster(200), mode = 0)
        }
        delay(200)  // 等第一个任务进入打印流程（busy 置位）
        val result = conn.printRaster(knownRaster(1), mode = 0)
        assertEquals("上一个打印任务还没结束", result.message)
        job.join()
    }

    // ── AUTO 探测链路（PrinterHolder.connect 真代码）───────────
    // 2026-08-13 反转：SPP 优先（快 + 墨色深 + 查询实测可响应）→ BLE 兜底

    @Test
    fun `AUTO探测SPP通道直接采用`() = runBlocking {
        val bleFake = FakePrinterConnection()
        val sppFake = FakePrinterConnection()
        PrinterHolder.injectForTest(bleConn = bleFake, sppConn = sppFake)
        Settings.connectionMode = ConnectionMode.AUTO

        val phases = mutableListOf<String>()
        val ok = PrinterHolder.connect(device) { text, _ -> phases.add(text) }

        assertTrue("AUTO 连接应成功", ok)
        assertSame(sppFake, PrinterHolder.active)  // SPP 优先 → 采用 SPP
        assertTrue(phases.contains("正在连接（经典蓝牙 SPP）…"))
        assertTrue(phases.contains("连接成功（经典蓝牙）"))
        assertTrue(sppFake.connected)
        assertFalse(bleFake.connected)  // 没走到 BLE
    }

    @Test
    fun `AUTO探测SPP失败回退BLE`() = runBlocking {
        val sppFake = FakePrinterConnection(connectFails = true)  // 模拟纯 BLE 版无 SPP 服务
        val bleFake = FakePrinterConnection()
        PrinterHolder.injectForTest(bleConn = bleFake, sppConn = sppFake)
        Settings.connectionMode = ConnectionMode.AUTO

        val phases = mutableListOf<String>()
        val ok = PrinterHolder.connect(device) { text, _ -> phases.add(text) }

        assertTrue("AUTO 连接应成功（回退 BLE）", ok)
        assertSame(bleFake, PrinterHolder.active)  // SPP 连接失败 → 回退 BLE
        assertTrue(phases.contains("SPP 连接失败，改用 BLE 透传…"))
        assertTrue(phases.contains("连接成功（BLE 透传）"))
        assertFalse(sppFake.connected)  // SPP 已断开
        assertTrue(bleFake.connected)
    }

    @Test
    fun `切通道先断开另一通道`() = runBlocking {
        val bleFake = FakePrinterConnection()
        val sppFake = FakePrinterConnection()
        PrinterHolder.injectForTest(bleConn = bleFake, sppConn = sppFake)

        // SPP 模式连上 → active = sppFake
        Settings.connectionMode = ConnectionMode.SPP
        assertTrue(PrinterHolder.connect(device))
        assertSame(sppFake, PrinterHolder.active)
        assertTrue(sppFake.connected)

        // 切 BLE 模式重连：打印机会先断开 SPP 再走 BLE（单连接约束）
        Settings.connectionMode = ConnectionMode.BLE
        assertTrue(PrinterHolder.connect(device))
        assertSame(bleFake, PrinterHolder.active)
        assertTrue(bleFake.connected)
        assertFalse("切通道前应已断开 SPP", sppFake.connected)
    }
}
