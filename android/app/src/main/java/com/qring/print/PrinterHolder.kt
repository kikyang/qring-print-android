package com.qring.print

import android.bluetooth.BluetoothDevice
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

/**
 * 全局共享的打印机连接实例（单例）。
 * MainActivity 与 DebugActivity 共用同一连接，避免各自建 socket。
 * scope 为应用级，不随 Activity 销毁。
 *
 * 双通道（2026-08-11 加，实测修正）：X1 两个通道都可用——
 * - [BlePrinterConnection]：BLE 透传（FF02 写 / FF01 通知），查询/打印全功能
 * - [SppPrinterConnection]：经典蓝牙 SPP，**单向**：能打印、查询无响应
 *   （2026-08-11 用户真机验证：SPP 打印正常出纸，型号显示 "?" 属预期）
 * 连接前必须 init(context) 一次（ApplicationContext）。
 */
object PrinterHolder {
    private lateinit var appContext: Context
        private set

    fun init(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
            PrintLog.initLogFile(appContext)
            Settings.init(appContext)
            HistoryStore.init(appContext)
        }
    }

    val ble: BlePrinterConnection by lazy {
        check(::appContext.isInitialized) { "PrinterHolder.init(context) 必须先调用" }
        BlePrinterConnection(appContext, CoroutineScope(Dispatchers.Main))
    }

    val spp: SppPrinterConnection by lazy {
        check(::appContext.isInitialized) { "PrinterHolder.init(context) 必须先调用" }
        SppPrinterConnection(appContext, CoroutineScope(Dispatchers.Main))
    }

    /**
     * 当前 active 连接（AUTO 探测/手动切换后指向对应通道实现，见 connect()）。
     * 用 getter 惰性返回 ble：不能用属性初始化器（`= ble` 会在类加载 <clinit>
     * 时强制求值 ble lazy，而 init(context) 尚未调用 → 启动即崩）。
     */
    @Volatile
    private var activeImpl: PrinterConnection? = null

    var active: PrinterConnection
        get() = activeImpl ?: ble
        private set(v) { activeImpl = v }

    val instance: PrinterConnection get() = active

    /**
     * 按 Settings.connectionMode 分派连接：
     * - BLE：只走 BLE 透传
     * - SPP：只走经典蓝牙
     * - AUTO（默认）：BLE 优先，连接成功后用 queryStatus 验证通道是否"活着"——
     *   BLE 透传版查询有响应；SPP 单向版 GATT 无数据消费（查询超时返回 null），
     *   此时断开重走 SPP。SPP 能连上（单向，能打印查询无响应）则保持 SPP 连接，
     *   状态栏型号显示 "?" 属预期。
     *
     * 超时预算（全部协程内 await，UI 不卡）：BLE 阶段最坏 ≈30s（含未配对
     * 时 createBond 20s 超时+重试）；SPP 阶段 ≈17s（cancelDiscovery +
     * connect 15s + 600ms settle + 查询 1.5s）；AUTO 总最坏 ≈47s；
     * 典型成功（X1 透传版）3~8s。
     *
     * @param onPhase 阶段回调：(文案, 进度 0..100 或 null=不确定)。
     *   进度映射（AUTO）：BLE 连接 10% → BLE 查询验证 35% → 回退 SPP 55% →
     *   SPP 查询 80% → 成功 100%；手动模式：连接 10% → 成功 100%
     */
    suspend fun connect(device: BluetoothDevice, onPhase: (String, Int?) -> Unit = { _, _ -> }): Boolean {
        when (Settings.connectionMode) {
            ConnectionMode.BLE -> {
                onPhase("正在连接（BLE 透传）…", 10)
                val ok = ble.connect(device)
                if (ok) {
                    active = ble
                    onPhase("连接成功（BLE 透传）", 100)
                }
                return ok
            }
            ConnectionMode.SPP -> {
                onPhase("正在连接（经典蓝牙 SPP）…", 10)
                val ok = spp.connect(device)
                if (ok) {
                    active = spp
                    onPhase("连接成功（经典蓝牙）", 100)
                }
                return ok
            }
            ConnectionMode.AUTO -> {
                onPhase("正在连接（BLE 透传）…", 10)
                if (ble.connect(device)) {
                    // 通道验证：BLE 透传版查询有响应；SPP 单向版的 GATT 无数据消费
                    onPhase("BLE 已连上，验证通道…", 35)
                    val alive = runCatching {
                        withContextTimeoutSafe { ble.queryStatus() }
                    }.getOrDefault(null) != null
                    if (alive) {
                        active = ble
                        onPhase("连接成功（BLE 透传）", 100)
                        return true
                    }
                    onPhase("BLE 无响应，改用经典蓝牙…", 55)
                    ble.disconnect()
                    // 打日志便于排查是哪种机器
                    PrintLog.event("AUTO 回退：BLE 查询无响应，改走 SPP")
                } else {
                    onPhase("BLE 连接失败，改用经典蓝牙…", 55)
                }
                onPhase("正在连接（经典蓝牙 SPP）…", 60)
                val ok = spp.connect(device)
                if (ok) {
                    active = spp
                    onPhase("连接成功（经典蓝牙）", 100)
                }
                return ok
            }
        }
    }

    private suspend fun withContextTimeoutSafe(block: suspend () -> QringStatus?): QringStatus? {
        // BLE queryStatus 内部自带 1.5s 超时，这里不需要额外包超时；
        // runCatching 兜底任何异常（未连接等）
        return block()
    }
}
