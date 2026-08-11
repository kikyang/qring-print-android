package com.qring.print

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 错题小印 X1 蓝牙连接管理 —— **经典蓝牙 SPP 通道**（RFCOMM，标准串口 UUID）。
 *
 * 2026-08-11 加：X1 存在多个软件版本，透传版控制通道是 BLE GATT（[BlePrinterConnection]），
 * 经典版走 SPP。本类兼容 SPP 固件；在透传版 X1 上 RFCOMM 能连上但数据无人消费
 * （空壳），查询会超时——AUTO 模式由 [PrinterHolder.connect] 的 queryStatus 验证回退。
 *
 * 与 BLE 版的差异只在收发：
 * - 连接：createRfcommSocketToServiceRecord（失败换 insecure 再试），connect 前必须 cancelDiscovery
 * - 发送：RFCOMM 无 MTU 限制，1024B/块 + 1ms（QringProtocol.CHUNK_SIZE/CHUNK_DELAY_MS）
 * - 接收：daemon 读线程 InputStream → 喂入 rxBuffer（waitBytes/waitAck 状态机与 BLE 版相同）
 * 协议层（QringProtocol）与打印流程与 BLE 版完全一致，已真机联调。
 */
@SuppressLint("MissingPermission")
class SppPrinterConnection(
    private val appContext: android.content.Context,
    private val scope: CoroutineScope,
) : PrinterConnection {

    companion object {
        /** 标准蓝牙串口（SPP）UUID，QrintPrint/HarmonyOS 参考实现同值 */
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

        /** RFCOMM 连接超时（阻塞 connect 必须限时，防 ANR） */
        private const val SPP_CONNECT_TIMEOUT_MS = 15_000L
        /** 状态轮询间隔 */
        private const val POLL_INTERVAL_MS = 10_000L
        /** 查询响应等待上限 */
        private const val QUERY_TIMEOUT_MS = 1_500L
        /** 发命令后等打印机准备响应的时间，照搬 SDK */
        private const val QUERY_SETTLE_MS = 150L
        /** 等打印完成 ACK 的上限 */
        private const val ACK_TIMEOUT_MS = 120_000L
        /** 打印前后走纸点行 */
        private const val FEED_BEFORE = 10
        private const val FEED_AFTER = 100
        /** 接收缓冲兜底上限 */
        private const val RX_BUFFER_MAX = 4096
        /** 默认打印浓度（X1 合法范围 0~2，实测 2 显色最好） */
        const val DEFAULT_THICKNESS = 2
        /** 光栅分块行数（与 BLE 版一致，协议层分块） */
        private const val RASTER_CHUNK_ROWS = 64

        /** ESC @ 初始化（文本/光栅打印前的解析器复位） */
        private val CMD_ESC_INIT = byteArrayOf(0x1B, 0x40)
    }

    @Volatile override var connectedDevice: BluetoothDevice? = null
        private set

    @Volatile override var connected: Boolean = false
        private set

    /** 最近一次状态（轮询 / 查询写入） */
    @Volatile override var lastStatus: QringStatus? = null
        private set

    @Volatile override var batteryPercent: Int? = null
        private set

    @Volatile override var deviceModel: String = ""
        private set

    @Volatile override var firmwareVersion: String = ""
        private set

    /** 蓝牙版本（10 FF 30 10，可能不支持，空串则隐藏） */
    @Volatile override var btVersion: String = ""
        private set

    /** 蓝牙 MAC（10 FF 30 12，可能不支持） */
    @Volatile override var btMac: String = ""
        private set

    private var socket: BluetoothSocket? = null

    /** 读线程：生命周期绑定 socket，close 关流即退出，无协程泄漏 */
    private var readThread: Thread? = null

    /** 打印任务进行中 —— 期间暂停状态轮询 */
    @Volatile private var busy = false

    /** 滚动接收缓冲。响应长度不定，还会随时插入 FF xx 主动上报帧 */
    private val rxBuffer = ArrayDeque<Int>()

    private val mutex = Mutex()
    private var pollJob: kotlinx.coroutines.Job? = null

    // ── 连接 / 断开 ───────────────────────────────────────────

    override suspend fun connect(device: BluetoothDevice): Boolean {
        disconnect()
        // 必须：经典蓝牙发现进行中 connect RFCOMM 必失败（系统级坑）
        runCatching { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() }

        val ok = withContext(Dispatchers.IO) {
            try {
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                var ok = withTimeoutOrNull(SPP_CONNECT_TIMEOUT_MS) { socket?.connect(); true } ?: false
                if (!ok) {
                    // 防御：个别透传模块只注册 insecure 通道。失败后 socket 不可复用，必须重建
                    runCatching { socket?.close() }
                    socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                    ok = withTimeoutOrNull(SPP_CONNECT_TIMEOUT_MS) { socket?.connect(); true } ?: false
                }
                if (!ok) {
                    runCatching { socket?.close() }
                    socket = null
                    false
                } else {
                    true
                }
            } catch (e: Exception) {
                // 权限（SecurityException）/ 连接拒绝（IOException）一律转失败
                runCatching { socket?.close() }
                socket = null
                PrintLog.event("SPP 连接异常: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }
        if (!ok) return false

        val sock = socket ?: return false
        connectedDevice = device
        connected = true
        startReadLoop(sock)
        // 连接成功：等打印机就绪，再查设备信息（与 BLE 版一致）
        delay(600)
        queryDeviceInfo()
        startPolling()
        PrintLog.event("SPP 连接成功 ${device.name} ${device.address}")
        return true
    }

    override suspend fun disconnect() {
        mutex.withLock {
            stopPolling()
            synchronized(rxBuffer) { rxBuffer.clear() }
            busy = false
            // 关流使读线程 read() 抛异常自动退出
            runCatching { socket?.inputStream?.close() }
            runCatching { socket?.outputStream?.close() }
            runCatching { socket?.close() }
            socket = null
            connected = false
            connectedDevice = null
            lastStatus = null
            batteryPercent = null
            deviceModel = ""
            firmwareVersion = ""
            btVersion = ""
            btMac = ""
        }
    }

    // ── 底层收发 ──────────────────────────────────────────────

    /** 读线程：InputStream 字节喂入 rxBuffer（与 BLE 版 onRx 同一状态机） */
    private fun startReadLoop(sock: BluetoothSocket) {
        readThread?.let { runCatching { it.interrupt() } }
        readThread = Thread {
            try {
                val input = sock.inputStream
                val buf = ByteArray(1024)
                while (connected) {
                    val n = input.read(buf)
                    if (n < 0) break // EOF：对端关闭
                    onRx(buf.copyOfRange(0, n))
                    PrintLog.log('R', buf.copyOfRange(0, n))
                }
            } catch (e: Exception) {
                PrintLog.event("SPP 读线程退出: ${e.javaClass.simpleName}")
            } finally {
                // 异常/EOF 退出即连接死亡（disconnect() 主动关流也会走到这里，
                // 此时 connected 已被 disconnect 置 false，不会重复触发）
                if (connected) {
                    connected = false
                    connectedDevice = null
                }
            }
        }.apply { isDaemon = true; start() }
    }

    /**
     * 发送数据（1024B/块 + 1ms，照搬 QrintPrint-Windows/HarmonyOS 参考）。
     * RFCOMM 缓冲大、无 MTU 限制，不需要 BLE 的 32B/80ms 节奏。
     */
    private suspend fun send(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val out = socket?.outputStream
        if (out == null) {
            PrintLog.event("SPP 发送失败：socket 为空")
            return@withContext false
        }
        try {
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + CHUNK_SIZE, data.size)
                out.write(data, offset, end - offset)
                out.flush()
                offset = end
                delay(CHUNK_DELAY_MS)
            }
            PrintLog.log('T', data)
            true
        } catch (e: Exception) {
            // 写失败 = 连接死亡（对端关闭/超时）
            PrintLog.event("SPP 写异常: ${e.javaClass.simpleName}: ${e.message}")
            connected = false
            connectedDevice = null
            false
        }
    }

    private suspend fun sendAll(commands: List<ByteArray>): Boolean {
        for (cmd in commands) {
            if (!send(cmd)) return false
        }
        return true
    }

    private suspend fun waitBytes(n: Int, timeoutMs: Long): List<Int> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            synchronized(rxBuffer) {
                if (rxBuffer.size >= n) {
                    return List(n) { rxBuffer.removeFirst() }.also { logRx(it) }
                }
            }
            delay(20)
        }
        synchronized(rxBuffer) {
            val all = rxBuffer.toList()
            rxBuffer.clear()
            return all.also { logRx(it) }
        }
    }

    private fun onRx(bytes: ByteArray) {
        synchronized(rxBuffer) {
            for (b in bytes) rxBuffer.addLast(b.toInt() and 0xFF)
            while (rxBuffer.size > RX_BUFFER_MAX) rxBuffer.removeFirst()
        }
    }

    private fun logRx(bytes: List<Int>) {
        if (bytes.isNotEmpty()) {
            PrintLog.log('R', bytes.map { it.toByte() }.toByteArray())
        }
    }

    /** 清空输入 → 发命令 → 稍等 → 读响应。这是官方 SDK 的固定套路 */
    private suspend fun query(command: ByteArray, nbytes: Int): List<Int> {
        synchronized(rxBuffer) { rxBuffer.clear() }
        if (!send(command)) return emptyList()
        delay(QUERY_SETTLE_MS)
        return waitBytes(nbytes, QUERY_TIMEOUT_MS)
    }

    /** 等打印完成 ACK (0xAA)，同时盯 FF xx 故障帧 */
    private suspend fun waitAck(timeoutMs: Long): PrintResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            synchronized(rxBuffer) {
                if (rxBuffer.contains(ACK_PRINT_DONE)) {
                    rxBuffer.clear()
                    return PrintResult(true, "打印完成")
                }
                val it = rxBuffer.iterator()
                var prev: Int? = null
                while (it.hasNext()) {
                    val b = it.next()
                    if (prev == FAULT_FRAME_HEAD) {
                        FaultCode.from(b)?.let { fc ->
                            rxBuffer.clear()
                            return PrintResult(false, fc.label)
                        }
                    }
                    prev = b
                }
            }
            delay(100)
        }
        return PrintResult(false, "等待打印完成超时")
    }

    // ── 查询 ──────────────────────────────────────────────────

    override suspend fun queryStatus(): QringStatus? {
        val resp = query(CMD_STATUS, 1)
        if (resp.isEmpty()) return null
        return parseStatus(resp[0]).also { lastStatus = it }
    }

    /** 电量：响应 2 字节，第 2 字节才是百分比 */
    override suspend fun queryBattery(): Int? {
        val resp = query(CMD_BATTERY, 2)
        if (resp.size < 2) return null
        return resp[1].also { batteryPercent = it }
    }

    /** 字符串类查询：型号 / 固件版本 */
    private suspend fun queryString(command: ByteArray): String {
        val resp = query(command, 64)
        return String(
            resp.filter { it in 0x20..0x7E }.map { it.toByte() }.toByteArray()
        ).trim()
    }

    override suspend fun queryDeviceInfo() {
        // 与 BLE 版一致：设备信息走 10 FF 70（名称|MAC|MAC|固件版本|SN|电量）
        val info = query(CMD_DEVICE_INFO, 128)
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
        // 蓝牙版本/MAC（可能不支持——无响应则留空隐藏）
        btVersion = queryString(CMD_BT_VERSION)
        btMac = queryString(CMD_BT_MAC)
    }

    /**
     * 打印前体检。返回故障文案，null 表示可以打印。
     * 查不到状态（打印机没回包）时返回 null 放行：宁可让打印试一次、
     * 失败时由 ACK 阶段的故障帧兜住。
     */
    override suspend fun preflightCheck(): String? {
        if (!connected) return "打印机未连接"
        val status = queryStatus() ?: return null
        return faultMessage(status)
    }

    // ── 打印 ──────────────────────────────────────────────────

    /**
     * 打印一张已经转好的光栅位图。时序与 BLE 版完全一致（已真机联调）：
     * STOP复位 → ENABLE → 浓度 → WAKEUP → ESC@ → 前走纸 → 光栅 → 后走纸 → STOP → 等 ACK
     */
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
        stopPolling()
        synchronized(rxBuffer) { rxBuffer.clear() }
        PrintLog.event("SPP 打印开始 mode=$mode halve=$halveRows 行=${raster.height}")

        try {
            if (!send(CMD_STOP)) return PrintResult(false, "发送失败，连接可能已断开")
            delay(100)
            if (!send(CMD_ENABLE)) return PrintResult(false, "发送失败，连接可能已断开")
            val t = thickness ?: DEFAULT_THICKNESS
            send(cmdThickness(t))
            send(CMD_WAKEUP)
            send(CMD_ESC_INIT)

            val fb = (feedBefore ?: FEED_BEFORE).coerceIn(0, 255)
            val fa = (feedAfter ?: FEED_AFTER).coerceIn(0, 255)
            sendAll(cmdFeed(fb))

            // 图片通道：先行合并减半（2 行 OR 1 行），再用 m=2 双打
            val data = if (halveRows) RasterEncoder.halveRows(raster) else raster
            val h = data.height

            // 光栅分块发送：单次 GS v 0 数据量超限会固字瀑布，每块独立头 + 块间短延迟
            val w = data.widthBytes
            var rowOffset = 0
            while (rowOffset < h) {
                val rows = minOf(RASTER_CHUNK_ROWS, h - rowOffset)
                send(cmdRasterHeader(w, rows, mode))
                val chunk = data.data.copyOfRange(
                    rowOffset * w, (rowOffset + rows) * w
                )
                if (!send(chunk)) return PrintResult(false, "位图发送中断")
                rowOffset += rows
                delay(150)
            }

            sendAll(cmdFeed(fa))
            send(CMD_STOP)

            return waitAck(ACK_TIMEOUT_MS)
        } catch (e: Exception) {
            // 任何底层异常转为打印失败结果，绝不崩协程
            PrintLog.event("SPP 打印异常: ${e.javaClass.simpleName}: ${e.message}")
            return PrintResult(false, "打印中断（${e.javaClass.simpleName}），请重新连接后重试")
        } finally {
            busy = false
            // 打完刷新一次状态（查询失败不抛——否则会掩盖上面 return 的结果）
            runCatching { refreshAll() }
            startPolling()
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
        synchronized(rxBuffer) { rxBuffer.clear() }
        if (!send(bytes)) return emptyList()
        delay(QUERY_SETTLE_MS)
        return waitBytes(expectBytes, QUERY_TIMEOUT_MS)
    }

    // ── 状态轮询 ──────────────────────────────────────────────

    private fun startPolling() {
        stopPolling()
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (connected && !busy) {
                    refreshAll()
                }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /** 释放连接（单例模式下不取消全局 scope，只清连接状态） */
    override fun close() {
        stopPolling()
        kotlinx.coroutines.runBlocking { disconnect() }
    }
}
