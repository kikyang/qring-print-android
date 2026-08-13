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
 * 双通道（2026-08-11 加，08-13 实测修正）：X1 两个通道都可用——
 * - [BlePrinterConnection]：BLE 透传（FF02 写 / FF01 通知），查询/打印全功能
 * - [SppPrinterConnection]：经典蓝牙 SPP，查询实测也可响应（10 FF 40/50/70
 *   均正常返回；早期"单向、查询无响应"结论系 BLE 占用时连接失败的假象）；
 *   SPP 传输快（1024B/1ms vs BLE 32B/80ms）→ 行间隔短 → 打印头残热积累 →
 *   同浓度下墨色更深更实（2026-08-13 用户实测"又黑又快"）。故 AUTO 默认 **SPP 优先**。
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

    // ── 测试注入（2026-08-13 加）──────────────────────────────
    // 端到端测试把 FakePrinterConnection 注入 ble/spp 槽位，
    // AUTO 探测分支（BLE 优先 → queryStatus 验证 → 回退 SPP）在 JVM 里跑真代码。

    private var testBle: PrinterConnection? = null
    private var testSpp: PrinterConnection? = null

    /** 测试注入：替换 BLE/SPP 通道（null 恢复默认真机实现）。用完务必恢复 */
    fun injectForTest(bleConn: PrinterConnection? = null, sppConn: PrinterConnection? = null) {
        testBle = bleConn
        testSpp = sppConn
        // 注入即失效当前 active（可能指向旧 fake），下次访问走 getter 默认路径
        activeImpl = null
    }

    private val bleChannel: PrinterConnection get() = testBle ?: ble
    private val sppChannel: PrinterConnection get() = testSpp ?: spp

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
     * - AUTO（默认）：**SPP 优先，BLE 兜底**（2026-08-13 实测反转）。
     *   SPP 传输快（1024B/1ms）墨色更深且查询实测可响应，故优先；
     *   SPP 连上即视为可用（能打印即成功，不强制查询验证——单向机型查询
     *   无响应也不影响打印）。SPP 连接失败（纯 BLE 版无 SPP 服务/信道被拒）
     *   回退 BLE 透传。
     *
     * 打印机同一时刻只接受一个连接（BLE 占用时 SPP 会被拒，反之亦然），
     * 故每个分支 connect 前先断开另一通道的 active 连接（[closeOther]）。
     *
     * 超时预算（全部协程内 await，UI 不卡）：SPP 阶段最坏 ≈31s（未配对
     * createBond 20s + cancelDiscovery + connect 15s + 600ms settle）；
     * 回退 BLE 再 ≈30s；AUTO 总最坏 ≈60s；典型成功（本机 X1）3~8s。
     *
     * @param onPhase 阶段回调：(文案, 进度 0..100 或 null=不确定)。
     *   进度映射（AUTO）：SPP 连接 10% → 回退 BLE 55% → BLE 连接 60% →
     *   成功 100%；手动模式：连接 10% → 成功 100%
     */
    suspend fun connect(device: BluetoothDevice, onPhase: (String, Int?) -> Unit = { _, _ -> }): Boolean {
        when (Settings.connectionMode) {
            ConnectionMode.BLE -> {
                onPhase("正在连接（BLE 透传）…", 10)
                closeOther(bleChannel)
                val ok = bleChannel.connect(device)
                if (ok) {
                    active = bleChannel
                    onPhase("连接成功（BLE 透传）", 100)
                }
                return ok
            }
            ConnectionMode.SPP -> {
                onPhase("正在连接（经典蓝牙 SPP）…", 10)
                closeOther(sppChannel)
                val ok = sppChannel.connect(device)
                if (ok) {
                    active = sppChannel
                    onPhase("连接成功（经典蓝牙）", 100)
                }
                return ok
            }
            ConnectionMode.AUTO -> {
                onPhase("正在连接（经典蓝牙 SPP）…", 10)
                closeOther(sppChannel)
                if (sppChannel.connect(device)) {
                    // SPP 连上即可用（能打印即成功；查询是否响应不影响打印）
                    active = sppChannel
                    onPhase("连接成功（经典蓝牙）", 100)
                    return true
                }
                onPhase("SPP 连接失败，改用 BLE 透传…", 55)
                closeOther(bleChannel)
                onPhase("正在连接（BLE 透传）…", 60)
                val ok = bleChannel.connect(device)
                if (ok) {
                    active = bleChannel
                    onPhase("连接成功（BLE 透传）", 100)
                }
                return ok
            }
        }
    }

    /**
     * 显式 BLE 连接（2026-08-13 加：BLE 藏进调试台，主界面主力 SPP）。
     * 调试台扫档前先连 BLE 用；绕过 Settings.connectionMode，直接连 BLE 通道。
     * 打印机单连接约束：先断开另一通道（SPP）的 active 连接。
     */
    suspend fun connectBle(device: BluetoothDevice): Boolean {
        closeOther(bleChannel)
        val ok = bleChannel.connect(device)
        if (ok) active = bleChannel
        return ok
    }

    /** 打印机单连接约束：切通道前先断开另一通道的 active 连接（2026-08-13）。
     *  目标通道自身的 connect 会断开自己旧连接，这里只需处理"另一通道"。 */
    private suspend fun closeOther(channel: PrinterConnection) {
        val cur = activeImpl
        if (cur != null && cur !== channel && cur.connected) {
            PrintLog.event("切通道：断开当前 ${cur.javaClass.simpleName} 连接")
            cur.disconnect()
        }
    }
}
