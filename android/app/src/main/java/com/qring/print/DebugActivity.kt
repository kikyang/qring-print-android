package com.qring.print

import android.app.Activity
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
}
