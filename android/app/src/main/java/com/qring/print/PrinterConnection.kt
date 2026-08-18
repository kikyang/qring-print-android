package com.qring.print

import android.bluetooth.BluetoothDevice

/**
 * 打印机连接通道抽象（2026-08-11 加）。
 *
 * X1 存在多个软件版本：透传版控制通道是 BLE GATT（FF00/FF02/FF01），
 * 经典蓝牙版走 SPP（RFCOMM）。两个通道协议层完全一致（QringProtocol），
 * 仅收发实现不同。UI 层一律通过 [PrinterHolder.instance] 操作当前 active 连接，
 * 不感知具体通道。
 *
 * 实现：
 * - [BlePrinterConnection] —— BLE 透传（本机 X1 实测路径）
 * - [SppPrinterConnection] —— 经典蓝牙 SPP（兼容经典版固件）
 */
interface PrinterConnection {

    // ── 只读状态（实现类 @Volatile var + private set）──

    val connectedDevice: BluetoothDevice?
    val connected: Boolean
    val lastStatus: QringStatus?
    val batteryPercent: Int?
    val deviceModel: String
    val firmwareVersion: String
    val btVersion: String
    val btMac: String

    // ── 连接 / 断开 ───────────────────────────────────────────

    /** 连接设备（阻塞调用方协程直到连接成功/失败） */
    suspend fun connect(device: BluetoothDevice): Boolean

    suspend fun disconnect()

    // ── 查询 ──────────────────────────────────────────────────

    suspend fun queryStatus(): QringStatus?

    suspend fun queryBattery(): Int?

    suspend fun queryDeviceInfo()

    /**
     * 打印前体检。返回故障文案，null 表示可以打印。
     * 查不到状态时放行（让打印试一次，失败由 ACK 阶段故障帧兜住）。
     */
    suspend fun preflightCheck(): String?

    // ── 打印 ──────────────────────────────────────────────────

    /**
     * 打印一张已经转好的光栅位图。
     * @param mode 光栅模式：文字 m=0；图片 m=2 + halveRows=true（行合并减半后双打）
     * @param halveRows 行合并减半（仅图片通道，配合 m=2 使用）
     */
    suspend fun printRaster(
        raster: RasterData,
        thickness: Int? = null,
        mode: Int = 0,
        halveRows: Boolean = false,
        feedBefore: Int? = null,
        feedAfter: Int? = null,
    ): PrintResult

    /**
     * 多份打印（2026-08-18 加）：逐份调用 [printRaster]，每份都等 ACK 完成后再开下一份。
     * 默认实现已满足 BLE/SPP/Fake 三通道；返回第一份失败时的错误。
     */
    suspend fun printRasterCopies(
        raster: RasterData,
        thickness: Int? = null,
        mode: Int = 0,
        halveRows: Boolean = false,
        feedBefore: Int? = null,
        feedAfter: Int? = null,
        copies: Int = 1,
    ): PrintResult {
        val n = copies.coerceAtLeast(1)
        for (i in 1..n) {
            val r = printRaster(raster, thickness, mode, halveRows, feedBefore, feedAfter)
            if (!r.ok) {
                return if (n > 1) PrintResult(false, "第 $i/$n 份失败：${r.message}") else r
            }
        }
        return PrintResult(true, if (n > 1) "打印完成（$n 份）" else "打印完成")
    }

    /** 查一轮状态 + 电量 */
    suspend fun refreshAll()

    // ── 调试：原始命令台 ──────────────────────────────────────

    /** 发送任意原始命令并等待响应（联调排查用） */
    suspend fun sendCommand(hex: String, expectBytes: Int = 64): List<Int>

    /** 释放连接（单例模式下不取消全局 scope，只清连接状态） */
    fun close()
}
