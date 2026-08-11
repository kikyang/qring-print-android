package com.qring.print

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * 全局共享的打印机连接实例（单例）。
 * MainActivity 与 DebugActivity 共用同一连接，避免各自建 socket。
 * scope 为应用级，不随 Activity 销毁。
 *
 * BLE 通道（2026-08-10 联调后）：
 * X1 的控制通道是 BLE 透传（FF02 写 / FF01 通知），SPP 是空壳。
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

    val instance: BlePrinterConnection by lazy {
        check(::appContext.isInitialized) { "PrinterHolder.init(context) 必须先调用" }
        BlePrinterConnection(appContext, CoroutineScope(Dispatchers.Main))
    }
}
