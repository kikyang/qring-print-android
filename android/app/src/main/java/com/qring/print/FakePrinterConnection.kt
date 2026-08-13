package com.qring.print

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.delay

/**
 * 虚拟打印机连接（2026-08-13 加）—— 客户端侧的 [PrinterConnection] 实现，
 * 背后是一台 [FakePrinter] 协议引擎。
 *
 * 用途：把「连接 → 探测 → 唤醒 → 体检 → 光栅分包 → 状态拦截」整条链路
 * 在 JVM 端到端跑通。打印时序走 [PrintJobRunner]（与 BLE/SPP 真机连接同一份代码），
 * 收发差异只有这一层：write 直接喂给 [FakePrinter]，
 * 应答字节立即进接收缓冲，查询/打印路径与真机完全一致。
 *
 * 不调用 device 的任何方法（Robolectric 测试环境传入 shadow device 即可）。
 */
class FakePrinterConnection(
    /** 背后的虚拟打印机（可多连接共享一台，模拟单机重连） */
    val printer: FakePrinter = FakePrinter(),
    /** 连接建立延迟（模拟扫描/配对耗时，默认 0 立即连上） */
    var connectDelayMs: Long = 0,
    /** 查询响应等待上限（默认同真机；测试缩短以避免无响应场景干等 1.5s × 5） */
    var queryTimeoutMs: Long = PrintJobRunner.QUERY_TIMEOUT_MS,
    /** 连接失败开关（模拟纯 BLE 版无 SPP 服务/信道被拒，AUTO SPP 优先回退测试用） */
    var connectFails: Boolean = false,
) : PrinterConnection {

    @Volatile override var connectedDevice: BluetoothDevice? = null
        private set

    @Volatile override var connected: Boolean = false
        private set

    @Volatile override var lastStatus: QringStatus? = null
        private set

    @Volatile override var batteryPercent: Int? = null
        private set

    @Volatile override var deviceModel: String = ""
        private set

    @Volatile override var firmwareVersion: String = ""
        private set

    @Volatile override var btVersion: String = ""
        private set

    @Volatile override var btMac: String = ""
        private set

    /** 打印任务进行中 */
    @Volatile private var busy = false

    /** 接收缓冲（应答字节），与真机连接的 rxBuffer 同语义 */
    private val rxBuffer = ArrayDeque<Int>()

    /** 适配 [FakePrinter]：write 喂字节流，读的是引擎即时产生的应答 */
    private val io = object : PrinterIo {
        /** 虚拟打印机瞬时完成，块间 0ms */
        override val rasterChunkDelayMs: Long = 0
        override suspend fun write(bytes: ByteArray): Boolean {
            val resp = printer.feed(bytes)
            synchronized(rxBuffer) {
                for (b in resp) rxBuffer.addLast(b.toInt() and 0xFF)
            }
            return true
        }

        override suspend fun readAvailable(n: Int, timeoutMs: Long): List<Int> {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                synchronized(rxBuffer) {
                    if (rxBuffer.size >= n) {
                        return List(n) { rxBuffer.removeFirst() }
                    }
                }
                delay(20)
            }
            synchronized(rxBuffer) {
                val all = rxBuffer.toList()
                rxBuffer.clear()
                return all
            }
        }

        override fun clearRx() {
            synchronized(rxBuffer) { rxBuffer.clear() }
        }
    }

    // ── 连接 / 断开 ───────────────────────────────────────────

    override suspend fun connect(device: BluetoothDevice): Boolean {
        disconnect()
        if (connectFails) return false
        if (connectDelayMs > 0) delay(connectDelayMs)
        connectedDevice = device
        connected = true
        // 与真机一致：连接成功后等就绪再查设备信息
        delay(600)
        queryDeviceInfo()
        return true
    }

    override suspend fun disconnect() {
        synchronized(rxBuffer) { rxBuffer.clear() }
        busy = false
        connected = false
        connectedDevice = null
        lastStatus = null
        batteryPercent = null
        deviceModel = ""
        firmwareVersion = ""
        btVersion = ""
        btMac = ""
    }

    // ── 查询（时序与真机连接同一套 PrintJobRunner.query）──────

    override suspend fun queryStatus(): QringStatus? {
        val resp = PrintJobRunner.query(io, CMD_STATUS, 1, queryTimeoutMs)
        if (resp.isEmpty()) return null
        return parseStatus(resp[0]).also { lastStatus = it }
    }

    /** 电量：响应 2 字节，第 2 字节才是百分比 */
    override suspend fun queryBattery(): Int? {
        val resp = PrintJobRunner.query(io, CMD_BATTERY, 2, queryTimeoutMs)
        if (resp.size < 2) return null
        return resp[1].also { batteryPercent = it }
    }

    /** 字符串类查询：型号 / 固件版本 */
    private suspend fun queryString(command: ByteArray): String {
        val resp = PrintJobRunner.query(io, command, 64, queryTimeoutMs)
        return String(
            resp.filter { it in 0x20..0x7E }.map { it.toByte() }.toByteArray()
        ).trim()
    }

    override suspend fun queryDeviceInfo() {
        // 与 BLE/SPP 版一致的解析：设备信息走 10 FF 70（名称|MAC|MAC|固件版本|SN|电量）
        val info = PrintJobRunner.query(io, CMD_DEVICE_INFO, 128, queryTimeoutMs)
        val text = String(info.filter { it in 0x20..0x7E }.map { it.toByte() }.toByteArray()).trim()
        if (text.isNotEmpty()) {
            val parts = text.split("|")
            if (parts.size >= 5) {
                deviceModel = parts[0]
                firmwareVersion = parts[3]
            }
        }
        if (deviceModel.isEmpty()) deviceModel = queryString(CMD_MODEL)
        if (firmwareVersion.isEmpty()) firmwareVersion = queryString(CMD_FW_VERSION)
        // 蓝牙版本/MAC（X1 可能不支持——无响应则留空隐藏，fake 默认支持）
        btVersion = queryString(CMD_BT_VERSION)
        btMac = queryString(CMD_BT_MAC)
    }

    /**
     * 打印前体检。现查一次而不是读缓存（用户可能刚掀盖/换纸）。
     * 查不到状态时放行（SPP 单向通道路径），失败由 ACK 阶段故障帧兜住。
     */
    override suspend fun preflightCheck(): String? {
        if (!connected) return "打印机未连接"
        val status = queryStatus() ?: return null
        return faultMessage(status)
    }

    // ── 打印（时序 = PrintJobRunner，与真机同一份代码）────────

    override suspend fun printRaster(
        raster: RasterData,
        thickness: Int?,
        mode: Int,
        halveRows: Boolean,
        feedBefore: Int?,
        feedAfter: Int?,
    ): PrintResult {
        if (!connected) return PrintResult(false, "打印机未连接")
        if (busy) return PrintResult(false, "上一个打印任务还没结束")

        busy = true
        io.clearRx()
        try {
            return PrintJobRunner.printRaster(
                io, raster, thickness, mode, halveRows, feedBefore, feedAfter
            )
        } catch (e: Exception) {
            // 与真机连接一致：底层异常转失败，绝不崩协程
            return PrintResult(false, "打印中断（${e.javaClass.simpleName}），请重新连接后重试")
        } finally {
            busy = false
            runCatching { refreshAll() }
        }
    }

    /** 查一轮状态 + 电量 */
    override suspend fun refreshAll() {
        queryStatus()
        queryBattery()
    }

    // ── 调试：原始命令台 ──────────────────────────────────────

    override suspend fun sendCommand(hex: String, expectBytes: Int): List<Int> {
        val clean = hex.replace(" ", "").replace(",", "")
        require(clean.isNotEmpty() && clean.length % 2 == 0) { "hex 格式错误，应为偶数位十六进制" }
        val bytes = ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        io.clearRx()
        if (!io.write(bytes)) return emptyList()
        delay(PrintJobRunner.QUERY_SETTLE_MS)
        return io.readAvailable(expectBytes, queryTimeoutMs)
    }

    /** 释放连接（清状态即可，无底层资源） */
    override fun close() {
        kotlinx.coroutines.runBlocking { disconnect() }
    }
}
