package com.qring.print

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 蓝牙收发日志（环形缓冲 + 事件落盘）。
 *
 * 实物联调排查用：记录所有发送/接收字节（Hex），带时间戳，
 * 在 DebugActivity 查看/复制/分享。缓冲上限 [MAX]，超出丢最旧。
 *
 * 2026-08-11 加事件落盘：关键事件（连接/断开/打印开始/异常）写入
 * app 内部存储 files/printlog.txt——崩溃闪退后内存缓冲丢失，
 * 落盘日志可 adb pull 复盘（这是"自检页一点就断连"的排查手段）。
 */
object PrintLog {

    private const val MAX = 600
    private val buffer = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile private var eventFile: File? = null

    /** 启动时调用一次（PrinterHolder.init），开事件日志文件 */
    fun initLogFile(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            eventFile = File(dir, "printlog.txt")
        } catch (_: Exception) {
        }
    }

    /** 关键事件：写日志文件（低频，同步写无碍）+ 内存缓冲 + Logcat */
    @Synchronized
    fun event(msg: String) {
        val line = "${fmt.format(Date())} [EVENT] $msg"
        try {
            eventFile?.let { FileWriter(it, true).use { w -> w.write(line + "\n") } }
        } catch (_: Exception) {
        }
        android.util.Log.w("QringPrint", msg)
        buffer.addLast(line)
        while (buffer.size > MAX) buffer.removeFirst()
    }

    @Synchronized
    fun log(direction: Char, bytes: ByteArray, note: String = "") {
        val hex = bytes.joinToString(" ") { "%02X".format(it) }
        // 超长数据（光栅位图）只记摘要，避免刷屏
        val text = if (bytes.size > 48) {
            hex.take(16 * 3 - 1) + " … [" + bytes.size + " B]"
        } else {
            hex
        }
        val line = "${fmt.format(Date())} $direction $text${if (note.isNotEmpty()) "  ($note)" else ""}"
        buffer.addLast(line)
        while (buffer.size > MAX) buffer.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = buffer.toList()

    @Synchronized
    fun snapshotText(): String = buffer.joinToString("\n")

    @Synchronized
    fun clear() = buffer.clear()

    @Synchronized
    fun size(): Int = buffer.size
}
