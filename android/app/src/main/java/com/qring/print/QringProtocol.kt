package com.qring.print

/**
 * 小印 (Qring / BeePrt BY) 热敏打印机私有协议。
 *
 * 移植自开源项目 Thisko/QrintPrint (MIT)：
 * https://github.com/Thisko/QrintPrint —— 通过对官方 App com.zxxk.xiaoyin.App 逆向整理，
 * 作者已在 HarmonyOS 真机验证。本文件为纯协议层：只拼字节、解析字节，不碰 socket。
 *
 * 注意：这不是标准 ESC/POS 状态协议。标准 ESC/POS 用 DLE EOT (10 04 n) 查状态、
 * 且没有电量指令；Qring 用自己的 10 FF 系列命令，一个状态字节里同时带
 * 打印中/开盖/缺纸/低电压/过热五个位，并有独立电量查询。
 * 只有走纸 (ESC J) 和光栅位图 (GS v 0) 两条沿用了 ESC/POS。
 */

/** 58mm 热敏头点数 */
const val WIDTH_DOTS = 384
/** 每行字节数 384/8 = 48，无补位 */
const val WIDTH_BYTES = 48
/** 单次 write 上限，超过要分包 */
const val CHUNK_SIZE = 1024
/** 分包间隔，照搬官方 SDK 行为 */
const val CHUNK_DELAY_MS = 1L

// ── 打印控制 ────────────────────────────────────────────────
val CMD_ENABLE = byteArrayOf(0x10, 0xFF.toByte(), 0xF1.toByte(), 0x02)
// 注意：不要用 ENABLE2（1F B2 10）——X1 固件不识别，会被文本引擎渲染成「固」字乱码
val CMD_STOP = byteArrayOf(0x10, 0xFF.toByte(), 0xF1.toByte(), 0x45)
/** 唤醒：12 个 0x00 */
val CMD_WAKEUP = ByteArray(12)

// ── 查询 ────────────────────────────────────────────────────
val CMD_STATUS = byteArrayOf(0x10, 0xFF.toByte(), 0x40)
val CMD_BATTERY = byteArrayOf(0x10, 0xFF.toByte(), 0x50, 0xF1.toByte())
val CMD_MODEL = byteArrayOf(0x10, 0xFF.toByte(), 0x20, 0xF0.toByte())
val CMD_FW_VERSION = byteArrayOf(0x10, 0xFF.toByte(), 0x20, 0xF1.toByte())
val CMD_SN = byteArrayOf(0x10, 0xFF.toByte(), 0x20, 0xF2.toByte())
val CMD_BT_NAME = byteArrayOf(0x10, 0xFF.toByte(), 0x30, 0x11)

/** 打印完成 ACK */
const val ACK_PRINT_DONE = 0xAA
/** 主动上报帧头 */
const val FAULT_FRAME_HEAD = 0xFF

// ── 状态字节位 ──────────────────────────────────────────────
const val ST_PRINTING = 0x01
const val ST_COVER_OPEN = 0x02
const val ST_NO_PAPER = 0x04
const val ST_LOW_BATTERY = 0x08
const val ST_OVERHEAT = 0x10

/** FF xx 主动上报的故障码 */
enum class FaultCode(val code: Int, val label: String) {
    NO_PAPER(0x01, "缺纸"),
    COVER_OPEN(0x02, "开盖"),
    OVERHEAT(0x03, "过热"),
    LOW_BATTERY(0x04, "低电量");

    companion object {
        fun from(code: Int): FaultCode? = entries.firstOrNull { it.code == code }
    }
}

/** 状态字节解析结果 */
data class QringStatus(
    val raw: Int,
    val printing: Boolean,
    val coverOpen: Boolean,
    val noPaper: Boolean,
    val lowBattery: Boolean,
    val overheat: Boolean,
) {
    /** 状态字节为 0 表示一切正常 */
    val isHealthy: Boolean get() = raw == 0
}

fun parseStatus(raw: Int): QringStatus = QringStatus(
    raw = raw,
    printing = raw and ST_PRINTING != 0,
    coverOpen = raw and ST_COVER_OPEN != 0,
    noPaper = raw and ST_NO_PAPER != 0,
    lowBattery = raw and ST_LOW_BATTERY != 0,
    overheat = raw and ST_OVERHEAT != 0,
)

/**
 * 打印前体检文案。返回 null 表示可以打印。
 * 判断顺序有讲究：开盖必须排在缺纸前面——上盖打开时纸传感器看不到纸，
 * 会同时把缺纸位也置起来，这时提示「缺纸」是误导。
 */
fun faultMessage(status: QringStatus): String? = when {
    status.coverOpen -> "机器未合盖，请检查机器"
    status.noPaper -> "机器缺纸，请检查纸张装配"
    status.overheat -> "机器过热，请稍候再尝试打印"
    else -> null
}

// ── 指令构造 ────────────────────────────────────────────────

/** 打印浓度 / 加热强度。官方 App 打文字用 1 */
fun cmdThickness(level: Int): ByteArray = byteArrayOf(0x10, 0xFF.toByte(), 0x10, 0x00, level.toByte())

/** 自动关机时间，大端 16 位，单位秒 */
fun cmdShutdownTime(seconds: Int): ByteArray =
    byteArrayOf(0x10, 0xFF.toByte(), 0x12, (seconds / 256).toByte(), (seconds % 256).toByte())

/**
 * ESC J n —— 走纸 n 点行。n 是单字节，超过 255 要拆成多条。
 */
fun cmdFeed(dots: Int): List<ByteArray> {
    val commands = mutableListOf<ByteArray>()
    var remaining = dots
    while (remaining > 0) {
        val n = minOf(remaining, 255)
        commands.add(byteArrayOf(0x1B, 0x4A, n.toByte()))
        remaining -= n
    }
    return commands
}

/** GS v 0 —— 光栅位图头。data 紧跟其后单独发送 */
fun cmdRasterHeader(widthBytes: Int, height: Int, mode: Int): ByteArray = byteArrayOf(
    0x1D, 0x76, 0x30, (mode and 0x03).toByte(),
    (widthBytes % 256).toByte(), ((widthBytes / 256) and 0xFF).toByte(),
    (height % 256).toByte(), ((height / 256) and 0xFF).toByte(),
)
