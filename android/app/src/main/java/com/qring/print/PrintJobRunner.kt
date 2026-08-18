package com.qring.print

import kotlinx.coroutines.delay

/**
 * 最小收发抽象 + 打印时序编排（2026-08-13 抽取）。
 *
 * 三个连接实现（BLE / SPP / Fake）的收发差异只在底层，协议时序完全一致。
 * 把「STOP复位 → ENABLE → 浓度 → WAKEUP → ESC@ → 前走纸 → 光栅分包 → 后走纸
 * → STOP → 等 ACK/故障帧」整条时序抽到这里，真机连接与虚拟打印机共享同一份代码，
 * 端到端测试测到的时序 = 真机跑的时序。实物联调只剩两个物理未知量：
 * GATT 写特征与热敏头物理行为。
 *
 * 纯 Kotlin + 协程，无 Android 依赖（JVM 单测可直接跑）。
 */

/** 收发抽象：发送字节流、消费式读响应、清空接收缓冲 */
interface PrinterIo {
    /** 发送字节（分包等由实现类负责）。返回 false 表示发送失败 */
    suspend fun write(bytes: ByteArray): Boolean

    /**
     * 等 n 字节或超时返回已有（可能为空）；读出即从缓冲移除。
     * 与 BlePrinterConnection.waitBytes 语义一致。
     */
    suspend fun readAvailable(n: Int, timeoutMs: Long): List<Int>

    /** 清空接收缓冲 */
    fun clearRx()

    /**
     * 光栅块间延迟（通道特性）。SPP 快：2026-08-13 实测块间 0ms 内容完整且更清晰
     * （开头缺色 = 打印头冷启动物理现象，与速度无关）；BLE 传输慢需保留等待。
     */
    val rasterChunkDelayMs: Long
}

object PrintJobRunner {

    // ── X1 实测定稿的打印时序参数（原 BlePrinterConnection/SppPrinterConnection 常量）──

    /** 发命令后等打印机准备响应的时间，照搬 SDK */
    const val QUERY_SETTLE_MS = 150L

    /** 查询响应等待上限 */
    const val QUERY_TIMEOUT_MS = 1_500L

    /** 打印前后走纸点行 */
    const val FEED_BEFORE = 10
    const val FEED_AFTER = 100

    /** 默认打印浓度（X1 合法范围 0~2，实测 2 显色最好） */
    const val DEFAULT_THICKNESS = 2

    /** 光栅分块行数：单次 GS v 0 超限会固字瀑布（实测 m=0 时 128 行 OK / 256 行失败）。
     *  m=2 双倍高时每行数据打两遍（物理 2 倍行），取 64 行数据（=128 物理行）保守安全 */
    const val RASTER_CHUNK_ROWS = 64

    /** ESC @ 初始化（文本/光栅打印前的解析器复位） */
    private val CMD_ESC_INIT = byteArrayOf(0x1B, 0x40)

    /**
     * 默认 ACK 超时计算（2026-08-11 借鉴 lztttt/QrintPrint-Android）：
     * 基础 8s + 每行 5ms，上限 30s——打印失败不用傻等固定 120s。
     */
    val DEFAULT_ACK_TIMEOUT_MS: (Int) -> Long = { h -> minOf(30_000L, 8_000L + h * 5L) }

    /** var 仅供测试缩短等待（ACK 超时路径测试不真等 8 秒），真机路径保持默认 */
    var ackTimeoutMs: (height: Int) -> Long = DEFAULT_ACK_TIMEOUT_MS

    // ── 查询 ──────────────────────────────────────────────────

    /**
     * 清空输入 → 发命令 → 稍等 → 读响应。这是官方 SDK 的固定套路。
     * @param timeoutMs 响应等待上限（Fake 测试可缩短，真机连接用默认）
     */
    suspend fun query(
        io: PrinterIo,
        command: ByteArray,
        nbytes: Int,
        timeoutMs: Long = QUERY_TIMEOUT_MS,
    ): List<Int> {
        io.clearRx()
        if (!io.write(command)) return emptyList()
        delay(QUERY_SETTLE_MS)
        return io.readAvailable(nbytes, timeoutMs)
    }

    // ── 打印 ──────────────────────────────────────────────────

    /**
     * 打印一张已经转好的光栅位图。
     * X1 实测定稿时序（2026-08-10 起逐步验证）：
     *   STOP复位 → ENABLE → 浓度 → WAKEUP → ESC@ → 前走纸 → 光栅 → 后走纸 → STOP → 等 ACK
     *   无 ENABLE2（1F B2 10）：X1 固件不识别，会渲染成「固」字乱码
     *
     * @param mode 光栅模式：文字走 m=0；图片走 m=2 + halveRows=true（行合并减半后双打）
     * @param halveRows 行合并减半（仅图片通道，配合 m=2 使用）
     */
    suspend fun printRaster(
        io: PrinterIo,
        raster: RasterData,
        thickness: Int?,
        mode: Int,
        halveRows: Boolean,
        feedBefore: Int?,
        feedAfter: Int?,
    ): PrintResult {
        if (!io.write(CMD_STOP)) return PrintResult(false, "发送失败，连接可能已断开")
        delay(100)
        if (!io.write(CMD_ENABLE)) return PrintResult(false, "发送失败，连接可能已断开")
        val t = thickness ?: DEFAULT_THICKNESS
        io.write(cmdThickness(t))
        io.write(CMD_WAKEUP)
        io.write(CMD_ESC_INIT)

        // 进纸/出纸可调（Settings 持久化，参考 QrintPrint-Windows）
        val fb = (feedBefore ?: FEED_BEFORE).coerceIn(0, 255)
        val fa = (feedAfter ?: FEED_AFTER).coerceIn(0, 255)
        for (cmd in cmdFeed(fb)) {
            if (!io.write(cmd)) return PrintResult(false, "发送失败，连接可能已断开")
        }

        // 图片通道：先行合并减半（2 行 OR 1 行），再用 m=2 双打
        val data = if (halveRows) RasterEncoder.halveRows(raster) else raster
        val h = data.height

        // 光栅分块发送：单次 GS v 0 数据量超限会固字瀑布（实测 128 行 OK / 256 行失败）。
        // 每块独立 GS v 0 头 + 块间短延迟，打印连续不中断。
        val w = data.widthBytes
        var rowOffset = 0
        while (rowOffset < h) {
            val rows = minOf(RASTER_CHUNK_ROWS, h - rowOffset)
            if (!io.write(cmdRasterHeader(w, rows, mode))) {
                return PrintResult(false, "发送失败，连接可能已断开")
            }
            val chunk = data.data.copyOfRange(
                rowOffset * w, (rowOffset + rows) * w
            )
            if (!io.write(chunk)) return PrintResult(false, "位图发送中断")
            rowOffset += rows
            delay(io.rasterChunkDelayMs)
        }

        for (cmd in cmdFeed(fa)) {
            if (!io.write(cmd)) return PrintResult(false, "发送失败，连接可能已断开")
        }
        io.write(CMD_STOP)

        // ACK 超时动态计算（2026-08-11 借鉴 lztttt/QrintPrint-Android）：
        // 基础 8s + 每行 5ms，上限 30s——打印失败不用傻等固定 120s
        return waitAck(io, ackTimeoutMs(h))
    }

    /**
     * 多份打印（2026-08-18 加）：逐份调用 [printRaster]，每份都等 ACK 完成后再开下一份。
     * 返回第一份失败时的错误；成功份数通过 message 说明。
     */
    suspend fun printRasterCopies(
        io: PrinterIo,
        raster: RasterData,
        thickness: Int?,
        mode: Int,
        halveRows: Boolean,
        feedBefore: Int?,
        feedAfter: Int?,
        copies: Int,
    ): PrintResult {
        val n = copies.coerceAtLeast(1)
        for (i in 1..n) {
            val r = printRaster(io, raster, thickness, mode, halveRows, feedBefore, feedAfter)
            if (!r.ok) {
                return if (n > 1) PrintResult(false, "第 $i/$n 份失败：${r.message}") else r
            }
        }
        return PrintResult(true, if (n > 1) "打印完成（$n 份）" else "打印完成")
    }

    /**
     * 等打印完成 ACK (0xAA)，同时盯 FF xx 故障帧。
     * prev 跨 readAvailable 调用保留（原实现每次查缓冲会漏掉跨包边界的 FF xx 帧）。
     */
    suspend fun waitAck(io: PrinterIo, timeoutMs: Long): PrintResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        var prev = -1
        while (System.currentTimeMillis() < deadline) {
            val chunk = io.readAvailable(RX_SWEEP, ACK_POLL_MS)
            for (b in chunk) {
                if (b == ACK_PRINT_DONE) return PrintResult(true, "打印完成")
                if (prev == FAULT_FRAME_HEAD) {
                    FaultCode.from(b)?.let { fc -> return PrintResult(false, fc.label) }
                }
                prev = b
            }
            // readAvailable 自身超时即是一次 100ms 轮询等待，空 chunk 无需额外 delay
        }
        return PrintResult(false, "等待打印完成超时")
    }

    /** waitAck 每次扫的字节数上限（接收缓冲兜底 4096，扫全部） */
    private const val RX_SWEEP = 4096

    /** waitAck 轮询间隔 */
    private const val ACK_POLL_MS = 100L
}
