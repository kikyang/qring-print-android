package com.qring.print

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * 错题小印 X1 蓝牙连接管理 —— **BLE 透传通道**（写 FF02 / 通知 FF01）。
 *
 * 2026-08-10 实物联调结论（X1 实测）：
 * - 控制通道是 BLE 透传（ISSC 芯片，FF00 服务）；
 *   SPP 非空壳（2026-08-11 修正：能打印、查询无响应，见 SppPrinterConnection）
 * - 打印流程：STOP复位 → ENABLE → 浓度 → WAKEUP → ESC@ → 前走纸 → 光栅 → 后走纸 → STOP → 等 ACK
 *   **不要 ENABLE2（1F B2 10）**：X1 固件不识别，会被文本引擎渲染成「固」字乱码
 * - 光栅用 GS v 0 **m=0**（m=1 对含 0x00 的数据有 bug）
 * - 浓度合法范围 0~2（3/4 报 ER），默认 2
 * - 发送：无确认写（WRITE_TYPE_NO_RESPONSE）+ 包间 80ms + 分包默认 32B。
 *
 *   2026-08-13 电脑端直连实测（Windows bleak，MTU 协商同为 136）：**打印机
 *   物理上完全能吃满 MTU 满包**——133B/包连 2ms 间隔、407 行大图都稳定；
 *   「ISSC 芯片缓冲 32B」旧结论作废（160B 失败只是 Windows 本地 MTU 限制）。
 *   但**手机端** 大包会卡死——2026-08-13 手机扫档实测：128B 卡死（动一下即停）、
 *   **96B/40ms 稳定**，故默认分包 = 96B/40ms（比旧基线 32B/80ms 快 6 倍）。
 *   电脑端与手机端差异是 Android 蓝牙栈对 WRITE_TYPE_NO_RESPONSE 大包的行为差异。
 *   [overrideChunk]/[overrideDelayMs] 调试注入仍在（调试台扫档备用）。
 */
@SuppressLint("MissingPermission")
class BlePrinterConnection(
    private val appContext: Context,
    private val scope: CoroutineScope,
) : PrinterConnection {
    companion object {
        private val SERVICE_UUID: UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
        private val WRITE_UUID: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        private val NOTIFY_UUID: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** 状态轮询间隔 */
        private const val POLL_INTERVAL_MS = 10_000L
        /** 接收缓冲兜底上限 */
        private const val RX_BUFFER_MAX = 4096
        /** BLE 透传单包默认大小。2026-08-13 手机扫档实测定稿：**96B 稳定、128B 卡死**
         *  （打印机动一下即停，Android 栈对大包行为与电脑端不同），取 96B 为 Android 端
         *  稳定最大值；比旧基线 32B 快 3 倍。调试台扫档可覆盖（[overrideChunk]）。 */
        private const val BLE_CHUNK_DEFAULT = 96
        /** 请求协商的 MTU（Android ATT 单包上限 517；仅调试扫档时参考，不用于默认分包） */
        private const val REQUEST_MTU = 517
        /** 分包间隔（2026-08-13 手机扫档定稿）：**96B/40ms 实测稳定**，比旧基线 80ms 快
         *  一倍。80ms 是旧防丢包保守值，40ms 已实测连续打印不卡死。调试台扫档可覆盖。 */
        private const val BLE_CHUNK_DELAY_MS = 40L

        /** 调试注入：分包大小覆盖（DebugActivity 扫档用；null=用默认 96B）。见 send() */
        @Volatile
        var overrideChunk: Int? = null
        /** 调试注入：分包间隔覆盖（毫秒；null=用默认 40ms）。见 send() */
        @Volatile
        var overrideDelayMs: Int? = null

        /** 默认打印浓度（X1 合法范围 0~2，实测 2 显色最好）。
         *  2026-08-13 起实际常量在 PrintJobRunner（BLE/SPP/Fake 三通道共享打印时序） */
        const val DEFAULT_THICKNESS = PrintJobRunner.DEFAULT_THICKNESS
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

    /** 蓝牙版本（10 FF 30 10，X1 可能不支持，空串则隐藏） */
    @Volatile override var btVersion: String = ""
        private set

    /** 蓝牙 MAC（10 FF 30 12，可能不支持） */
    @Volatile override var btMac: String = ""
        private set

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    /** 协商成功的 MTU（onMtuChanged 记录；0=未协商/失败 → 发送回退 32B 小包）。
     *  2026-08-13：requestMtu 一直有调，但此前没利用协商结果，固定 32B/包浪费了大 MTU */
    @Volatile private var negotiatedMtu = 0

    /** 打印任务进行中 —— 期间暂停状态轮询 */
    @Volatile private var busy = false

    /** 滚动接收缓冲。响应长度不定，还会随时插入 FF xx 主动上报帧 */
    private val rxBuffer = ArrayDeque<Int>()

    private val mutex = Mutex()
    private var pollJob: kotlinx.coroutines.Job? = null

    /** 适配 [PrinterIo]：发送/接收都走 GATT 通道（打印时序在 PrintJobRunner，与 SPP/Fake 共享） */
    private val io = object : PrinterIo {
        /** BLE 传输慢（96B/40ms），光栅块间保留 150ms 等传输（不可减） */
        override val rasterChunkDelayMs: Long = 150L
        override suspend fun write(bytes: ByteArray): Boolean = send(bytes)

        override suspend fun readAvailable(n: Int, timeoutMs: Long): List<Int> =
            waitBytes(n, timeoutMs)

        override fun clearRx() {
            synchronized(rxBuffer) { rxBuffer.clear() }
        }
    }

    /** 当前等待的连接/服务发现（回调驱动） */
    private var connectPending: CompletableDeferred<Boolean>? = null

    // ── 连接 / 断开 ───────────────────────────────────────────

    /**
     * 连接设备（阻塞调用方协程直到连接成功/失败）。
     * 打印机要求 LE 加密连接：首次连接若失败且设备未配对，自动发起配对并重连。
     */
    override suspend fun connect(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        disconnect()
        if (!tryConnect(device)) {
            // 首次连接失败：打印机要求 LE 配对（ISSC 透传），配对后重连
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                if (awaitBond(device)) {
                    if (!tryConnect(device)) return@withContext false
                } else {
                    return@withContext false
                }
            } else {
                return@withContext false
            }
        }
        // 连接成功：等通知订阅（CCCD）生效 + 打印机就绪，再查设备信息
        delay(600)
        queryDeviceInfo()
        startPolling()
        PrintLog.event("连接成功 ${device.name} ${device.address}")
        true
    }

    /** 发起 LE 配对并等待完成（BOND_BONDED）；用户取消返回 false */
    @SuppressLint("MissingPermission")
    private suspend fun awaitBond(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<Boolean>()
        val appContext = appContext
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                val dev = intent?.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    ?: return
                if (dev.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                    BluetoothDevice.BOND_BONDED -> deferred.complete(true)
                    BluetoothDevice.BOND_NONE -> deferred.complete(false)
                }
            }
        }
        appContext.registerReceiver(
            receiver,
            android.content.IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        try {
            val started = runCatching { device.createBond() }.getOrDefault(false)
            if (!started) {
                appContext.unregisterReceiver(receiver)
                return@withContext false
            }
            val ok = withTimeoutOrNull(20_000L) { deferred.await() } ?: false
            appContext.unregisterReceiver(receiver)
            ok
        } catch (e: Exception) {
            runCatching { appContext.unregisterReceiver(receiver) }
            false
        }
    }

    /** 单次 GATT 连接尝试 */
    private suspend fun tryConnect(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val deferred = CompletableDeferred<Boolean>()
            connectPending = deferred
            val cb = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        g.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        PrintLog.event("GATT 断开 status=$status")
                        connectPending?.complete(false)
                        connectPending = null
                        connected = false
                        connectedDevice = null
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        connectPending?.complete(false)
                        connectPending = null
                        return
                    }
                    val svc = g.getService(SERVICE_UUID)
                    if (svc == null) {
                        connectPending?.complete(false)
                        connectPending = null
                        return
                    }
                    writeChar = svc.getCharacteristic(WRITE_UUID)
                    notifyChar = svc.getCharacteristic(NOTIFY_UUID)
                    if (writeChar == null || notifyChar == null) {
                        connectPending?.complete(false)
                        connectPending = null
                        return
                    }
                    // 订阅通知（CCCD 写 enable）
                    subscribeNotify(g, notifyChar!!)
                    // 尽力提升 MTU（提升失败也能用 20B 包）
                    runCatching { g.requestMtu(517) }
                    connectPending?.complete(true)
                    connectPending = null
                }

                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    onRx(value)
                }

                override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                    // 只记录协商结果供调试参考。2026-08-13 实测：打印机物理能吃满
                    // MTU 满包（电脑端 133B 稳定），但 Android 栈大包会卡死，
                    // 故协商值不用于默认分包——分包见 send()（默认固定 32B）。
                    if (status == BluetoothGatt.GATT_SUCCESS && mtu >= 64) {
                        negotiatedMtu = mtu
                        PrintLog.event("BLE MTU 协商成功: $mtu（分包走默认/调试覆盖）")
                    } else {
                        negotiatedMtu = 0
                        PrintLog.event("BLE MTU 协商失败 status=$status")
                    }
                }

            }
            // 必须指定 TRANSPORT_LE：DUAL 设备默认走 BR/EDR，但打印机 GATT 服务只在 BLE 上
            gatt = device.connectGatt(appContext, false, cb, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                connectPending = null
                return@withContext false
            }
            val ok = deferred.await()
            if (!ok) {
                gatt?.close(); gatt = null
                connectedDevice = null; connected = false
                return@withContext false
            }
            connectedDevice = device
            connected = true
        } catch (e: Exception) {
            gatt?.close(); gatt = null
            connectedDevice = null; connected = false
            return@withContext false
        }
        true
    }

    private fun subscribeNotify(g: BluetoothGatt, char: BluetoothGattCharacteristic) {
        runCatching {
            g.setCharacteristicNotification(char, true)
            val cccd = char.getDescriptor(CCCD_UUID) ?: return
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }
    }

    override suspend fun disconnect() {
        mutex.withLock {
            stopPolling()
            rxBuffer.clear()
            busy = false
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
            gatt = null
            writeChar = null
            notifyChar = null
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

    /**
     * 发送数据（无确认写 + 默认 32B/包 + 80ms 包间）。
     * 2026-08-13 电脑端实测打印机物理能吃满 MTU 满包，但 Android 栈对大包
     * 行为不同（133B 实测卡死），故默认保持 32B 防丢包基线；调试台扫档时
     * 用 [overrideChunk]/[overrideDelayMs] 覆盖测 Android 端稳定分包值。
     */
    private suspend fun send(data: ByteArray): Boolean {
        val g = gatt ?: return false
        val char = writeChar ?: return false
        val chunkSize = overrideChunk ?: BLE_CHUNK_DEFAULT
        val chunkDelay: Long = overrideDelayMs?.toLong() ?: BLE_CHUNK_DELAY_MS
        val total = data.size
        var offset = 0
        while (offset < total) {
            val end = minOf(offset + chunkSize, total)
            val packet = data.copyOfRange(offset, end)
            // 无确认写（onCharacteristicWrite 回调在 SDK 34 不可 override），
            // 靠小包 + 固定间隔让透传芯片消化，电脑端已实测稳定。
            // SDK 33+ 的 writeCharacteristic 返回 int 状态码（0=成功）
            // 2026-08-11: writeCharacteristic 可能抛异常（未连接/权限/栈异常），
            // 吞掉转失败返回——查询/打印路径都依赖它，不能让它崩协程
            val status = try {
                g.writeCharacteristic(
                    char, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                )
            } catch (e: Exception) {
                PrintLog.event("写特征异常: ${e.javaClass.simpleName}: ${e.message}")
                return false
            }
            if (status != BluetoothGatt.GATT_SUCCESS) return false
            delay(chunkDelay)
            offset = end
        }
        PrintLog.log('T', data)
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
    private suspend fun query(command: ByteArray, nbytes: Int): List<Int> =
        PrintJobRunner.query(io, command, nbytes)

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
        // X1：设备信息走 10 FF 70（名称|MAC|MAC|固件版本|SN|电量），实测 67 字节
        // 2026-08-11 实测：10 FF 31 全变体无响应（X1 固件不支持），勿用；
        // 固件版本 = 10 FF 70 第 4 段 = 10 FF 20 F1 返回的 V1.05，二者一致
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
        // 蓝牙版本/MAC（QrintPrint-Windows 的命令，X1 可能不支持——无响应则留空隐藏）
        btVersion = queryString(CMD_BT_VERSION)
        btMac = queryString(CMD_BT_MAC)
    }

    /**
     * 打印前体检。返回故障文案，null 表示可以打印。
     * 这里现查一次而不是读轮询缓存 —— 轮询间隔 10s，
     * 用户可能刚掀开上盖或刚用完纸就点了打印，缓存值是过期的。
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
     * 打印一张已经转好的光栅位图。
     * X1 实测定稿时序（2026-08-10 起逐步验证）：
     *   STOP复位 → ENABLE → 浓度 → WAKEUP → ESC@ → 前走纸 → 光栅 → 后走纸 → STOP → 等 ACK
     *   无 ENABLE2（X1 固件不识别，会渲染成「固」字乱码）
     *
     * @param mode 光栅模式：文字走 m=0（用户实测文字本来就黑，不需要加深）；
     *   图片走 m=2 + halveRows=true（2026-08-11 打黑定稿）：
     *   - m=2/3 比 m=0 黑（每行加热两遍），00 字节安全（m=1 有 00 bug）
     *   - 但 m=2 是标准"双倍高"（垂直复制），直接打图片会纵向拉长 2 倍（用户实测确认）
     *   - 解法：halveRows=true 先把数据行 OR 合并减半，m=2 双打还原高度 → 黑度↑ 比例不变
     * @param halveRows 行合并减半（仅图片通道，配合 m=2 使用）
     */
    // 默认值在接口 PrinterConnection 声明（override 不能重复声明默认值），
    // 经 PrinterHolder.instance（接口类型）调用时生效
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
        PrintLog.event("打印开始 mode=$mode halve=$halveRows 行=${raster.height}")

        try {
            // 打印时序在 PrintJobRunner（与 SPP/Fake 同一份代码，2026-08-13 抽取）。
            // 无预热条：2026-08-11 用户实测排除——文字不打预热本来就黑；
            // 全黑块打不打预热都不黑（固件电流限制，strobe 固定短）。
            return PrintJobRunner.printRaster(
                io, raster, thickness, mode, halveRows, feedBefore, feedAfter
            )
        } catch (e: Exception) {
            // 2026-08-11 自检页"一点就断连"根因：协程无 try-catch，BLE 异常直接崩 App。
            // 任何底层异常转为打印失败结果，绝不崩协程。
            PrintLog.event("打印异常: ${e.javaClass.simpleName}: ${e.message}")
            return PrintResult(false, "打印中断（${e.javaClass.simpleName}），请重新连接后重试")
        } finally {
            busy = false
            // 打完刷新一次状态，纸张/电量会有变化（查询失败不抛——否则会掩盖上面 return 的结果）
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

    /**
     * 发送任意原始命令并等待响应（最多 64 字节，超时 1500ms）。
     * 联调排查用：hex 形如 "10 FF 40"、"1B 4A 32"。
     * @return 响应字节；超时返回已收到的内容（可能为空）
     */
    override suspend fun sendCommand(hex: String, expectBytes: Int): List<Int> {
        val clean = hex.replace(" ", "").replace(",", "")
        require(clean.isNotEmpty() && clean.length % 2 == 0) { "hex 格式错误，应为偶数位十六进制" }
        val bytes = ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        synchronized(rxBuffer) { rxBuffer.clear() }
        if (!send(bytes)) return emptyList()
        delay(PrintJobRunner.QUERY_SETTLE_MS)
        return waitBytes(expectBytes, PrintJobRunner.QUERY_TIMEOUT_MS)
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

/** 打印结果 */
data class PrintResult(val ok: Boolean, val message: String)

/** 光栅数据 */
data class RasterData(val widthBytes: Int, val height: Int, val data: ByteArray)

/** 获取已配对设备列表（原 SppPrinterConnection 中的函数，SPP 空壳已删，此工具保留） */
fun pairedDevices(): List<BluetoothDevice> {
    val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
    return runCatching { adapter.bondedDevices?.toList() ?: emptyList() }.getOrDefault(emptyList())
}

/** X1 设备信息查询：设备名|MAC|MAC|固件版本|SN|电量 */
val CMD_DEVICE_INFO = byteArrayOf(0x10, 0xFF.toByte(), 0x70)

/** 蓝牙版本（QrintPrint-Windows 命令，X1 待验证） */
val CMD_BT_VERSION = byteArrayOf(0x10, 0xFF.toByte(), 0x30, 0x10)

/** 蓝牙 MAC（QrintPrint-Windows 命令，X1 待验证） */
val CMD_BT_MAC = byteArrayOf(0x10, 0xFF.toByte(), 0x30, 0x12)
