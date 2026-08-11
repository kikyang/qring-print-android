package com.qring.print

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Qring / BeePrt 打印机 SPP 连接管理。
 *
 * 行为全部照搬开源项目 Thisko/QrintPrint 验证过的时序：
 * - 分包：每 1024 字节一包，包间 1ms
 * - 查询：清缓冲 → 发命令 → 等 150ms → 读响应（超时 1500ms）
 * - 打印期间停止状态轮询，避免查询字节混进打印数据流
 * - 滚动接收缓冲，随时可能插入 FF xx 主动上报故障帧
 */
class SppPrinterConnection(
    private val scope: CoroutineScope,
) {
    companion object {
        /** 串口服务标准 UUID，经典蓝牙 SPP 固定用这个 */
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
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
    }

    @Volatile var connectedDevice: BluetoothDevice? = null
        private set

    @Volatile var connected: Boolean = false
        private set

    /** 最近一次状态（轮询 / 查询写入） */
    @Volatile var lastStatus: QringStatus? = null
        private set

    @Volatile var batteryPercent: Int? = null
        private set

    @Volatile var deviceModel: String = ""
        private set

    @Volatile var firmwareVersion: String = ""
        private set

    private var socket: BluetoothSocketHolder? = null

    /** 打印任务进行中 —— 期间暂停状态轮询 */
    @Volatile private var busy = false

    /** 滚动接收缓冲。响应长度不定，还会随时插入 FF xx 主动上报帧 */
    private val rxBuffer = ArrayDeque<Int>()

    private val mutex = Mutex()
    private var pollJob: kotlinx.coroutines.Job? = null

    // ── 连接 / 断开 ───────────────────────────────────────────

    /** 连接设备（阻塞调用方协程直到连接成功/失败） */
    suspend fun connect(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        disconnect()
        try {
            val holder = BluetoothSocketHolder.open(device)
            socket = holder
            connectedDevice = device
            connected = true
        } catch (e: Exception) {
            socket = null
            connectedDevice = null
            connected = false
            return@withContext false
        }
        // 连接成功：查设备信息 + 启动轮询
        queryDeviceInfo()
        startPolling()
        true
    }

    suspend fun disconnect() {
        mutex.withLock {
            stopPolling()
            rxBuffer.clear()
            busy = false
            socket?.close()
            socket = null
            connected = false
            connectedDevice = null
            lastStatus = null
            batteryPercent = null
            deviceModel = ""
            firmwareVersion = ""
        }
    }

    // ── 底层收发 ──────────────────────────────────────────────

    /** 按官方 SDK 的方式分包：每 1024 字节一包，包间 1ms */
    private suspend fun send(data: ByteArray): Boolean {
        val sock = socket ?: return false
        val total = data.size
        var offset = 0
        while (offset < total) {
            val end = minOf(offset + CHUNK_SIZE, total)
            if (!sock.write(data.copyOfRange(offset, end))) return false
            delay(CHUNK_DELAY_MS)
            offset = end
        }
        PrintLog.log('T', data)
        return true
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

    suspend fun queryStatus(): QringStatus? {
        val resp = query(CMD_STATUS, 1)
        if (resp.isEmpty()) return null
        return parseStatus(resp[0]).also { lastStatus = it }
    }

    /** 电量：响应 2 字节，第 2 字节才是百分比 */
    suspend fun queryBattery(): Int? {
        val resp = query(CMD_BATTERY, 2)
        if (resp.size < 2) return null
        return resp[1].also { batteryPercent = it }
    }

    /** 字符串类查询：型号 / 固件版本实测是 ASCII */
    private suspend fun queryString(command: ByteArray): String {
        val resp = query(command, 64)
        return String(
            resp.filter { it in 0x20..0x7E }.map { it.toByte() }.toByteArray()
        ).trim()
    }

    suspend fun queryDeviceInfo() {
        deviceModel = queryString(CMD_MODEL)
        firmwareVersion = queryString(CMD_FW_VERSION)
    }

    /**
     * 打印前体检。返回故障文案，null 表示可以打印。
     * 这里现查一次而不是读轮询缓存 —— 轮询间隔 10s，
     * 用户可能刚掀开上盖或刚用完纸就点了打印，缓存值是过期的。
     * 查不到状态（打印机没回包）时返回 null 放行：宁可让打印试一次、
     * 失败时由 ACK 阶段的故障帧兜住。
     */
    suspend fun preflightCheck(): String? {
        if (!connected) return "打印机未连接"
        val status = queryStatus() ?: return null
        return faultMessage(status)
    }

    // ── 打印 ──────────────────────────────────────────────────

    /**
     * 打印一张已经转好的光栅位图。
     * 时序照搬 QringPrint 的 printRaster：
     *   enable → thickness → wakeup → feed(前) → 光栅 → feed(后) → stop → 等 ACK
     */
    suspend fun printRaster(raster: RasterData, thickness: Int? = null): PrintResult {
        if (!connected) return PrintResult(false, "打印机未连接")
        if (busy) return PrintResult(false, "上一个打印任务还没结束")

        busy = true
        stopPolling()
        synchronized(rxBuffer) { rxBuffer.clear() }

        try {
            if (!sendAll(listOf(CMD_ENABLE, CMD_ENABLE2))) {
                return PrintResult(false, "发送失败，连接可能已断开")
            }
            if (thickness != null) send(cmdThickness(thickness))
            send(CMD_WAKEUP)
            sendAll(cmdFeed(FEED_BEFORE))

            send(cmdRasterHeader(raster.widthBytes, raster.height, 0))
            if (!send(raster.data)) {
                return PrintResult(false, "位图发送中断")
            }

            sendAll(cmdFeed(FEED_AFTER))
            send(CMD_STOP)

            return waitAck(ACK_TIMEOUT_MS)
        } finally {
            busy = false
            // 打完刷新一次状态，纸张/电量会有变化
            refreshAll()
            startPolling()
        }
    }

    /** 查一轮状态 + 电量 */
    suspend fun refreshAll() {
        queryStatus()
        queryBattery()
    }

    // ── 调试：原始命令台 ──────────────────────────────────────

    /**
     * 发送任意原始命令并等待响应（最多 64 字节，超时 1500ms）。
     * 联调排查用：hex 形如 "10 FF 40"、"1B 4A 32"。
     * @return 响应字节；超时返回已收到的内容（可能为空）
     */
    suspend fun sendCommand(hex: String, expectBytes: Int = 64): List<Int> {
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
    fun close() {
        stopPolling()
        kotlinx.coroutines.runBlocking { disconnect() }
    }
}

/**
 * 注意：PrintResult / RasterData 已移至 BlePrinterConnection.kt（BLE 通道，2026-08-10 联调后主用）。
 * 本文件为旧 SPP 实现，保留仅供协议比对；pairedDevices() 仍被 MainActivity 使用。
 * 蓝牙 socket 持有者：封装 BluetoothSocket 的 open/read/write/close，
 * 所有读写都要求 BLUETOOTH_CONNECT 权限（Android 12+）。
 */
private class BluetoothSocketHolder private constructor(
    private val socket: android.bluetooth.BluetoothSocket,
    private val input: InputStream,
    private val output: OutputStream,
) {
    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

        /** 打开连接（阻塞，需在 IO 线程）。Android 12+ 需已授权 BLUETOOTH_CONNECT */
        fun open(device: BluetoothDevice): BluetoothSocketHolder {
            val sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
            sock.connect()
            return BluetoothSocketHolder(sock, sock.inputStream, sock.outputStream)
        }
    }

    fun write(data: ByteArray): Boolean = try {
        output.write(data)
        output.flush()
        true
    } catch (e: IOException) {
        false
    }

    fun close() {
        try { input.close() } catch (_: IOException) {}
        try { output.close() } catch (_: IOException) {}
        try { socket.close() } catch (_: IOException) {}
    }
}

/** 获取已配对设备列表（过滤 Qring 前缀的可选逻辑在 UI 层） */
fun pairedDevices(): List<BluetoothDevice> {
    val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
    return runCatching { adapter.bondedDevices?.toList() ?: emptyList() }.getOrDefault(emptyList())
}
