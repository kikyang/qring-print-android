package com.qring.print

/**
 * 虚拟打印机协议引擎（2026-08-13 加）—— 纯字节流状态机，无 Android 依赖。
 *
 * 在 JVM 里完整仿真 X1 的协议行为：
 * - 应答查询：10 FF 40（状态 1 字节）、10 FF 50 F1（电量 2 字节）、
 *   10 FF 70 / 20 F0 / 20 F1 / 20 F2 / 30 10~12（ASCII 字符串）
 * - 解析打印流程：ENABLE/浓度/WAKEUP/ESC@/走纸/GS v 0 光栅（任意分包边界）/STOP
 * - 打印结束回 ACK 0xAA；打印中有故障时回 FF xx 主动上报帧
 * - 故障注入：缺纸/开盖/过热/低电量，直接改属性即可
 *
 * 与真机实测行为的对应关系（供故障注入参考）：
 * - [respondToQueries]=false → SPP 单向通道（能打印、查询无响应）
 * - [ackPrint]=false → 打印后无 ACK（客户端超时失败）
 * - 光栅 widthBytes ≠ 48 → 数据照收但本次打印无 ACK（客户端超时失败）
 *
 * 每个 FakePrinter 实例代表一台"物理打印机"，可被多个连接复用。
 */
class FakePrinter {

    // ── 注入的物理状态（测试直接改）──────────────────────────

    /** 缺纸（状态位 0x04 / 故障帧 FF 01） */
    @Volatile var noPaper = false

    /** 开盖（状态位 0x02 / 故障帧 FF 02；开盖时纸传感器看不到纸，缺纸位也会置起） */
    @Volatile var coverOpen = false

    /** 过热（状态位 0x10 / 故障帧 FF 03） */
    @Volatile var overheat = false

    /** 低电量（状态位 0x08 / 故障帧 FF 04） */
    @Volatile var lowBattery = false

    /** 电量百分比（10 FF 50 F1 应答第 2 字节） */
    @Volatile var battery = 80

    // ── 行为开关 ──────────────────────────────────────────────

    /** false = 所有查询不应答（模拟 SPP 单向通道：能打印、查询无响应） */
    @Volatile var respondToQueries = true

    /** false = 打印结束不回 ACK（客户端 ACK 超时路径） */
    @Volatile var ackPrint = true

    /** false = 打印中故障不回 FF xx 主动上报帧（客户端靠体检拦截） */
    @Volatile var reportFaults = true

    // ── 确定性故障注入（测试用，不用协程竞态）────────────────

    /** 收到第 N 块光栅（1 起）后自动按 [autoFaultCode] 注入故障（0 = 不注入） */
    @Volatile var autoFaultAfterRasterBlock = 0

    /** autoFaultAfterRasterBlock 触发时注入的故障类型 */
    @Volatile var autoFaultCode = FaultCode.COVER_OPEN

    // ── 设备信息（10 FF 70 / 20 F0~F2 / 30 10~12 应答）────────

    var model = "Qring-X1"
    var firmware = "V1.05"
    var serialNumber = "X1SN000001"
    var btName = "Qring-X1"
    var btVersion = "BT5.1"
    var btMac = "AA:BB:CC:DD:EE:FF"

    // ── 观测（测试断言用）─────────────────────────────────────

    /** 收到的完整命令（含参数，不含光栅数据与 WAKEUP 零字节），顺序即发送时序 */
    val receivedCommands = mutableListOf<ByteArray>()

    /** 解析出的光栅块（GS v 0 头 + 数据完整拼接） */
    val rasterBlocks = mutableListOf<FakeRasterBlock>()

    /** 光栅校验错误文案（widthBytes ≠ 48、height = 0 等） */
    val rasterErrors = mutableListOf<String>()

    /** 未知命令/字节计数（容错观测：仿真器收到无法解析的输入） */
    var unknownCount = 0
        private set

    /** 发出的 ACK / 故障帧历史（观测用） */
    val acksSent = mutableListOf<ByteArray>()

    /** 收到的字节总数（含光栅数据） */
    var totalBytes = 0L
        private set

    /** 当前是否打印中（ENABLE 置起、STOP 清除，状态字节的 0x01 位来源） */
    @Volatile var printing = false
        private set

    // ── 内部状态机 ────────────────────────────────────────────

    private enum class State {
        IDLE,          // 空闲：找命令头
        Q_DLE,         // 收 10，等 FF
        Q_OPCODE,      // 收 10 FF，等 opcode
        Q_ARG1,        // 等 1 个参数字节
        Q_ARG2,        // 等 2 个参数字节
        ESC_CMD,       // 收 1B，等命令字节
        ESC_N,         // ESC J，等走纸点行数
        GS_CMD,        // 收 1D，等命令字节
        GS_V,          // 收 1D 76，等 0x30
        GSV_HDR,       // 等 5 字节光栅头
        RASTER_DATA,   // 收光栅数据
    }

    private var state = State.IDLE
    private var opcode = 0
    private val param = IntArray(5)
    private var paramIdx = 0
    private var rasterBuf: ByteArray = ByteArray(0)
    private var rasterOffset = 0
    private var rasterMode = 0
    private var rasterWidthBytes = 0
    private var rasterHeight = 0

    /** 本次打印任务（ENABLE 后）是否收到过光栅数据 */
    private var jobHasRaster = false

    /** 本次打印任务是否出现过光栅校验错误（出错则 STOP 不应答 ACK） */
    private var jobRasterError = false

    /** 收到的原始字节流（sendCommand 调试台原样回放用，完整保留） */
    private val rawStream = java.io.ByteArrayOutputStream()

    // ── 应答构造 ──────────────────────────────────────────────

    private val ascii = fun(s: String) = s.toByteArray(Charsets.US_ASCII)

    private fun statusByte(): Int {
        var b = if (printing) ST_PRINTING else 0
        if (coverOpen) b = b or ST_COVER_OPEN
        if (noPaper) b = b or ST_NO_PAPER
        if (lowBattery) b = b or ST_LOW_BATTERY
        if (overheat) b = b or ST_OVERHEAT
        return b
    }

    /** 故障帧优先级：开盖 > 缺纸 > 过热 > 低电量（与 faultMessage 的开盖优先一致） */
    private fun faultCode(): Int? = when {
        coverOpen -> FaultCode.COVER_OPEN.code
        noPaper -> FaultCode.NO_PAPER.code
        overheat -> FaultCode.OVERHEAT.code
        lowBattery -> FaultCode.LOW_BATTERY.code
        else -> null
    }

    // ── 主入口 ────────────────────────────────────────────────

    /**
     * 喂入客户端发来的字节流（允许任意分包边界），返回本次产生的应答字节。
     */
    fun feed(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (b in bytes) {
            val ub = b.toInt() and 0xFF
            totalBytes++
            rawStream.write(ub)
            step(ub)?.let { out.write(it) }
        }
        return out.toByteArray()
    }

    /** 收到的原始字节流副本（调试台回放） */
    fun rawBytes(): ByteArray = rawStream.toByteArray()

    private fun step(b: Int): ByteArray? {
        when (state) {
            State.IDLE -> when (b) {
                0x10 -> state = State.Q_DLE
                0x1B -> state = State.ESC_CMD
                0x1D -> state = State.GS_CMD
                0x00 -> Unit // WAKEUP 零字节，忽略
                else -> { unknownCount++; state = State.IDLE }
            }

            State.Q_DLE -> if (b == 0xFF) {
                state = State.Q_OPCODE
            } else {
                unknownCount++
                state = State.IDLE
            }

            State.Q_OPCODE -> {
                opcode = b
                when (b) {
                    0x40 -> { // 状态查询：立即完成
                        recordCommand(byteArrayOf(0x10, 0xFF.toByte(), 0x40))
                        state = State.IDLE
                        return if (respondToQueries) byteArrayOf(statusByte().toByte()) else null
                    }
                    0x70 -> { // 设备信息：立即完成
                        recordCommand(byteArrayOf(0x10, 0xFF.toByte(), 0x70))
                        state = State.IDLE
                        return if (respondToQueries) ascii(deviceInfoString()) else null
                    }
                    0x50 -> { state = State.Q_ARG1; paramIdx = 0 }
                    0x20, 0x30, 0xF1 -> { state = State.Q_ARG1; paramIdx = 0 }
                    0x10, 0x12 -> { state = State.Q_ARG2; paramIdx = 0 }
                    else -> { unknownCount++; recordUnknownCommand(b); state = State.IDLE }
                }
            }

            State.Q_ARG1 -> {
                param[paramIdx++] = b
                val p = param[0]
                state = State.IDLE
                recordCommand(byteArrayOf(0x10, 0xFF.toByte(), opcode.toByte(), p.toByte()))
                when (opcode) {
                    0x50 -> return if (respondToQueries) {
                        byteArrayOf(0x00, battery.coerceIn(0, 100).toByte()) // [?, 百分比]
                    } else null
                    0x20 -> { // 20 F0 型号 / 20 F1 固件 / 20 F2 SN
                        val s = when (p) {
                            0xF0 -> model
                            0xF1 -> firmware
                            0xF2 -> serialNumber
                            else -> return null
                        }
                        return if (respondToQueries) ascii(s) else null
                    }
                    0x30 -> { // 30 10 蓝牙版本 / 30 11 BT 名称 / 30 12 MAC
                        val s = when (p) {
                            0x10 -> btVersion
                            0x11 -> btName
                            0x12 -> btMac
                            else -> return null
                        }
                        return if (respondToQueries) ascii(s) else null
                    }
                    0xF1 -> { // F1 02 ENABLE / F1 45 STOP
                        when (p) {
                            0x02 -> { // 打印使能：进入打印任务
                                jobHasRaster = false
                                jobRasterError = false
                                printing = true
                            }
                            0x45 -> { // 停止：打印完成 → ACK / 故障帧 / 不应答
                                printing = false
                                if (jobHasRaster) {
                                    jobHasRaster = false
                                    return ackOrFault()
                                }
                            }
                        }
                    }
                }
            }

            State.Q_ARG2 -> {
                param[paramIdx++] = b
                if (paramIdx < 2) return null
                val p0 = param[0]
                val p1 = param[1]
                state = State.IDLE
                recordCommand(
                    byteArrayOf(0x10, 0xFF.toByte(), opcode.toByte(), p0.toByte(), p1.toByte())
                )
                // 10 00 xx 浓度 / 12 xx xx 关机时间：无应答
            }

            State.ESC_CMD -> when (b) {
                0x40 -> { // ESC @ 初始化
                    recordCommand(byteArrayOf(0x1B, 0x40))
                    state = State.IDLE
                }
                0x4A -> { state = State.ESC_N; paramIdx = 0 } // ESC J 走纸
                else -> { unknownCount++; state = State.IDLE }
            }

            State.ESC_N -> {
                param[paramIdx++] = b
                state = State.IDLE
                recordCommand(byteArrayOf(0x1B, 0x4A, b.toByte()))
            }

            State.GS_CMD -> if (b == 0x76) {
                state = State.GS_V
            } else {
                unknownCount++
                state = State.IDLE
            }

            State.GS_V -> if (b == 0x30) {
                state = State.GSV_HDR
                paramIdx = 0
            } else {
                unknownCount++
                state = State.IDLE
            }

            State.GSV_HDR -> {
                param[paramIdx++] = b
                if (paramIdx < 5) return null
                val m = param[0] and 0x03
                val wb = param[1] or (param[2] shl 8)
                val h = param[3] or (param[4] shl 8)
                recordCommand(
                    byteArrayOf(
                        0x1D, 0x76, 0x30, m.toByte(),
                        param[1].toByte(), param[2].toByte(), param[3].toByte(), param[4].toByte(),
                    )
                )
                // 校验：宽度必须 48 字节（384 点）；高度不能为 0
                if (wb != WIDTH_BYTES) {
                    rasterErrors.add("widthBytes=$wb != $WIDTH_BYTES")
                    jobRasterError = true
                }
                if (h == 0) {
                    rasterErrors.add("height=0")
                    jobRasterError = true
                }
                rasterMode = m
                rasterWidthBytes = wb
                rasterHeight = h
                rasterBuf = ByteArray(wb * h)
                rasterOffset = 0
                jobHasRaster = true
                state = if (rasterBuf.isEmpty()) State.IDLE else State.RASTER_DATA
                // 高度 0 的块：数据空，直接完成
                if (rasterBuf.isEmpty()) finishRasterBlock()
            }

            State.RASTER_DATA -> {
                rasterBuf[rasterOffset++] = b.toByte()
                if (rasterOffset >= rasterBuf.size) {
                    state = State.IDLE
                    finishRasterBlock()
                }
            }
        }
        return null
    }

    private fun finishRasterBlock() {
        rasterBlocks.add(
            FakeRasterBlock(rasterMode, rasterWidthBytes, rasterHeight, rasterBuf.copyOf())
        )
        // 确定性故障注入：第 N 块光栅后置起故障位（模拟打印中途开盖/缺纸/过热）
        if (autoFaultAfterRasterBlock > 0 && rasterBlocks.size >= autoFaultAfterRasterBlock) {
            when (autoFaultCode) {
                FaultCode.NO_PAPER -> noPaper = true
                FaultCode.COVER_OPEN -> coverOpen = true
                FaultCode.OVERHEAT -> overheat = true
                FaultCode.LOW_BATTERY -> lowBattery = true
            }
            autoFaultAfterRasterBlock = 0  // 一次性
        }
    }

    /** 打印任务结束时的应答：ACK / 故障帧 / 不应答（取决于注入与校验结果） */
    private fun ackOrFault(): ByteArray? {
        // 光栅校验出过错 → 打印机对坏数据不回应答（客户端走超时失败路径）
        if (jobRasterError) {
            jobRasterError = false
            return null
        }
        val fault = if (reportFaults) faultCode() else null
        val resp = when {
            !ackPrint -> null
            fault != null -> byteArrayOf(FAULT_FRAME_HEAD.toByte(), fault.toByte())
            else -> byteArrayOf(ACK_PRINT_DONE.toByte())
        }
        if (resp != null) acksSent.add(resp)
        return resp
    }

    private fun recordCommand(cmd: ByteArray) {
        receivedCommands.add(cmd)
    }

    private fun recordUnknownCommand(opcode: Int) {
        receivedCommands.add(byteArrayOf(0x10, 0xFF.toByte(), opcode.toByte()))
    }

    /** 10 FF 70 应答格式：名称|MAC|MAC|固件版本|SN|电量 */
    private fun deviceInfoString(): String =
        "$model|$btMac|$btMac|$firmware|$serialNumber|$battery"
}

/** 解析出的一个光栅块（GS v 0 头 + 完整数据） */
data class FakeRasterBlock(
    val mode: Int,
    val widthBytes: Int,
    val height: Int,
    val data: ByteArray,
)
