package com.qring.print

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 调试台：实物联调排查用。
 * - 蓝牙收发日志（Hex，环形缓冲实时滚动）
 * - 原始命令发送（任意 hex，看响应）
 * - 快捷命令（状态/电量/型号/固件/走纸/唤醒/停止）
 * - 日志复制 / 分享（可发给 AI 分析）
 */
class DebugActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var lastLogCount = -1

    private lateinit var logView: TextView
    private lateinit var cmdInput: EditText
    private lateinit var respView: TextView
    private val printer get() = PrinterHolder.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        root.addView(col)

        // ── 日志区 ──
        logView = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.BLACK)
            gravity = Gravity.START
        }
        col.addView(logView)

        val logRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 6)
        }
        col.addView(logRow)

        val clearBtn = Button(this).apply { text = "清空日志" }
        clearBtn.setOnClickListener {
            PrintLog.clear()
            logView.text = ""
            lastLogCount = -1
        }
        logRow.addView(clearBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val copyBtn = Button(this).apply { text = "复制日志" }
        copyBtn.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("printlog", PrintLog.snapshotText()))
            respView.text = "已复制 ${PrintLog.size()} 条日志"
        }
        logRow.addView(copyBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val shareBtn = Button(this).apply { text = "分享" }
        shareBtn.setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, PrintLog.snapshotText())
            }
            startActivity(Intent.createChooser(send, "分享收发日志"))
        }
        logRow.addView(shareBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // ── 原始命令区 ──
        val cmdLabel = TextView(this).apply {
            text = "原始命令（hex，如 10 FF 40）"
            textSize = 13f
            setPadding(0, 8, 0, 2)
        }
        col.addView(cmdLabel)

        cmdInput = EditText(this).apply {
            hint = "10 FF 40"
            typeface = Typeface.MONOSPACE
        }
        col.addView(cmdInput)

        val sendBtn = Button(this).apply { text = "发送并读响应" }
        col.addView(sendBtn)

        respView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(0, 6, 0, 6)
        }
        col.addView(respView)

        // ── 快捷命令 ──
        val quickLabel = TextView(this).apply {
            text = "快捷命令"
            textSize = 13f
            setPadding(0, 8, 0, 2)
        }
        col.addView(quickLabel)

        val quickRows = listOf(
            listOf("查状态 10FF40" to "10 FF 40", "查电量 10FF50F1" to "10 FF 50 F1"),
            listOf("查型号 10FF20F0" to "10 FF 20 F0", "查固件 10FF20F1" to "10 FF 20 F1"),
            listOf("走纸 50 点" to "1B 4A 32", "唤醒(12×00)" to "00 00 00 00 00 00 00 00 00 00 00 00"),
            listOf("使能+停" to "10 FF F1 02 1F B2 10 10 FF F1 45", "浓度1" to "10 FF 10 00 01"),
        )
        for (row in quickRows) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, hex) in row) {
                val b = Button(this).apply { text = label; textSize = 11f }
                b.setOnClickListener {
                    cmdInput.setText(hex)
                    doSend(hex)
                }
                r.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            col.addView(r)
        }

        // ── BLE 连接（2026-08-13 加：BLE 藏进调试、主力 SPP。
        //    主界面已不暴露 BLE 模式，扫档前须先用这里连 BLE）──
        val bleLabel = TextView(this).apply {
            text = "BLE 连接（调试用：扫档前先连 BLE）"
            textSize = 13f
            setPadding(0, 12, 0, 2)
        }
        col.addView(bleLabel)

        val bleMacInput = EditText(this).apply {
            hint = "打印机 MAC（如 65:56:10:28:B8:EA）"
            typeface = Typeface.MONOSPACE
        }
        col.addView(bleMacInput)

        val bleConnectBtn = Button(this).apply { text = "BLE 连接 / 重连" }
        col.addView(bleConnectBtn)

        bleConnectBtn.setOnClickListener {
            val mac = bleMacInput.text.toString().trim()
            if (mac.isEmpty()) {
                respView.text = "请输入打印机 MAC（本机见主界面已配对列表）"
                return@setOnClickListener
            }
            bleConnectBtn.isEnabled = false
            respView.text = "BLE 连接 $mac ..."
            scope.launch {
                try {
                    val dev = runCatching {
                        BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(mac)
                    }.getOrNull()
                    if (dev == null) {
                        respView.text = "无效 MAC：$mac"
                        return@launch
                    }
                    val ok = PrinterHolder.connectBle(dev)
                    respView.text = if (ok) "✅ BLE 已连接（可扫档）" else "❌ BLE 连接失败"
                } catch (e: Exception) {
                    respView.text = "连接异常：${e.javaClass.simpleName}: ${e.message}"
                } finally {
                    bleConnectBtn.isEnabled = true
                }
            }
        }

        // ── BLE 分包扫档（2026-08-13：电脑端已证打印机能吃满 MTU，
        //    Android 端 96B/40ms 已定稿；扫档用于复测/新机型探边界）──
        val scanLabel = TextView(this).apply {
            text = "BLE 分包扫档（先用上方连 BLE；逐档打小图看是否卡死）"
            textSize = 12f
            setPadding(0, 12, 0, 2)
        }
        col.addView(scanLabel)

        val scanChunkInput = EditText(this).apply {
            hint = "分包大小（逗号分隔，如 32,64,96,128）"
            typeface = Typeface.MONOSPACE
        }
        col.addView(scanChunkInput)

        val scanDelayInput = EditText(this).apply {
            hint = "包间 ms（默认 80）"
            typeface = Typeface.MONOSPACE
        }
        col.addView(scanDelayInput)

        val scanRowsInput = EditText(this).apply {
            hint = "测试图行数（默认 32，省纸）"
            typeface = Typeface.MONOSPACE
        }
        col.addView(scanRowsInput)

        val scanBtn = Button(this).apply { text = "开始逐档打印测试" }
        col.addView(scanBtn)

        scanBtn.setOnClickListener {
            val chunks = scanChunkInput.text.toString()
                .split(",").mapNotNull { it.trim().toIntOrNull() }
            if (chunks.isEmpty()) {
                respView.text = "请输入分包大小（如 32,64,96,128）"
                return@setOnClickListener
            }
            val intervalMs = scanDelayInput.text.toString().trim().toIntOrNull() ?: 80
            val rows = scanRowsInput.text.toString().trim().toIntOrNull() ?: 32
            if (rows <= 0 || rows > 200) {
                respView.text = "行数需在 1~200"
                return@setOnClickListener
            }
            scanBtn.isEnabled = false
            scope.launch {
                try {
                    if (!PrinterHolder.ble.connected) {
                        respView.text = "BLE 未连接！请返回主界面用 BLE 模式连接打印机后重试"
                        return@launch
                    }
                    val sb = StringBuilder()
                    sb.append("扫档 ${chunks.joinToString(",")}B / ${intervalMs}ms / ${rows} 行\n")
                    respView.text = sb.toString()
                    for (cs in chunks) {
                        BlePrinterConnection.overrideChunk = cs
                        BlePrinterConnection.overrideDelayMs = intervalMs
                        respView.text = "打印 ${cs}B/${intervalMs}ms ..."
                        val raster = buildChunkTestRaster(rows)
                        val r = PrinterHolder.ble.printRaster(
                            raster, 2, 0, false, 10, 20
                        )
                        sb.append("${cs}B: ${r.message}\n")
                        respView.text = sb.toString()
                        delay(1000)
                    }
                } finally {
                    BlePrinterConnection.overrideChunk = null
                    BlePrinterConnection.overrideDelayMs = null
                    scanBtn.isEnabled = true
                }
            }
        }

        setContentView(root)

        sendBtn.setOnClickListener {
            val hex = cmdInput.text.toString().trim()
            if (hex.isNotEmpty()) doSend(hex)
        }

        // 日志定时刷新（每 800ms 检查，有变化才重绘）
        refreshHandler.post(object : Runnable {
            override fun run() {
                val n = PrintLog.size()
                if (n != lastLogCount) {
                    lastLogCount = n
                    logView.text = PrintLog.snapshotText()
                    if (n > 0) {
                        (root.getChildAt(0) as? LinearLayout)?.let {
                            // 滚到底部
                            root.post { root.fullScroll(ScrollView.FOCUS_DOWN) }
                        }
                    }
                }
                refreshHandler.postDelayed(this, 800)
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        refreshHandler.removeCallbacksAndMessages(null)
        // 2026-08-11 修复：从调试台返回主界面不应断连（此前 disconnect() 导致
        // 每次进出调试台连接就断，用户以为是"点打印就断开"）。连接是全局单例，
        // 只在 MainActivity.onDestroy（App 退出）时断开。
    }

    private fun doSend(hex: String) {
        respView.text = "发送中 ..."
        scope.launch {
            try {
                val resp = printer.sendCommand(hex)
                respView.text = if (resp.isEmpty()) {
                    "无响应（超时 1.5s）"
                } else {
                    "响应(${resp.size}B): " + resp.joinToString(" ") { "%02X".format(it) }
                }
            } catch (e: Exception) {
                respView.text = "命令错误：${e.message}"
            }
        }
    }

    /** 分包扫档测试图（384 宽，rows 行）：粗黑条 + 白间隙 + 细线 + 竖条纹。
     *  丢包会表现为局部发白/缺块，肉眼可辨。 */
    private fun buildChunkTestRaster(rows: Int): RasterData {
        val w = 48  // 384 点 = 48 字节
        val data = ByteArray(w * rows)
        for (y in 0 until rows) {
            val row = when {
                y < 6 -> ByteArray(w) { 0xFF.toByte() }        // 粗黑条
                y < 7 -> ByteArray(w) { 0x00 }                 // 白间隙
                y < 14 -> ByteArray(w) { 0xFF.toByte() }       // 第二段黑条
                y < 15 -> ByteArray(w) { 0x00 }                // 白间隙
                y < 22 -> ByteArray(w) { if (y % 3 == 0) 0xFF.toByte() else 0x00.toByte() }
                else -> ByteArray(w) { i ->
                    if ((y % 2 == 0 && i % 4 < 2) || (y % 2 == 1 && i % 4 >= 2)) 0xFF.toByte() else 0x00
                }                                              // 竖条纹（AA/55 交错）
            }
            System.arraycopy(row, 0, data, y * w, w)
        }
        return RasterData(w, rows, data)
    }
}
