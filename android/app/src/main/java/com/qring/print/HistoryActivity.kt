package com.qring.print

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 打印历史（2026-08-11 加，参考 QrintPrint-Windows）：
 * 最近 100 条打印记录（缩略图 + 类型 + 时间），一键无损重打。
 */
class HistoryActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private val printer get() = PrinterHolder.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PrinterHolder.init(this)
        Utils.init(this)

        val root = ScrollView(this).apply { setBackgroundColor(Design.BG) }
        val col = Design.page()
        root.addView(col)

        col.addView(Design.header("🕘 打印历史"))

        val jobs = HistoryStore.list()
        if (jobs.isEmpty()) {
            col.addView(Design.card {
                addView(TextView(this@HistoryActivity).apply {
                    text = "暂无打印记录\n打印完成后自动保存，可一键重新打印"
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(Design.TEXT_SUB)
                    setPadding(0, Design.dp(24), 0, Design.dp(24))
                })
            })
        } else {
            col.addView(Design.caption("共 ${jobs.size} 条 · 点「重新打印」无损重打"))
            val clearBtn = Design.ghostButton("🗑 清空历史")
            col.addView(clearBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(8)
            })
            clearBtn.setOnClickListener {
                HistoryStore.clear()
                recreate()
            }
            for (job in jobs) {
                col.addView(historyItem(job), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = Design.dp(8)
                })
            }
        }
        setContentView(root)
    }

    /** 历史条目：缩略图 + 类型/标题/时间 + 重新打印/再编辑按钮 */
    private fun historyItem(job: HistoryStore.Job): LinearLayout {
        val item = Design.card()
        val editable = !job.paramsJson.isNullOrBlank()   // 存了状态快照才可再编辑（#5a）
        item.addView(Design.row {
            val thumb = HistoryStore.thumbBitmap(job)
            val img = ImageView(this@HistoryActivity).apply {
                if (thumb != null) setImageBitmap(thumb)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                maxHeight = Design.dp(90)
                background = Design.rounded(0xFFF0F0F0.toInt(), Design.RADIUS_SM)
            }
            addView(img, LinearLayout.LayoutParams(Design.dp(90), Design.dp(90)).apply {
                marginEnd = Design.dp(12)
            })
            val info = LinearLayout(this@HistoryActivity).apply { orientation = LinearLayout.VERTICAL }
            info.addView(TextView(this@HistoryActivity).apply {
                text = "${job.type} · ${job.title}"
                textSize = 14f
                setTextColor(Design.TEXT)
                typeface = Typeface.DEFAULT_BOLD
            })
            info.addView(TextView(this@HistoryActivity).apply {
                text = HistoryStore.formatTime(job.ts) + " · ${job.height} 行"
                textSize = 12f
                setTextColor(Design.TEXT_SUB)
                setPadding(0, Design.dp(2), 0, 0)
            })
            info.addView(TextView(this@HistoryActivity).apply {
                text = if (editable) "可重新打印，或继续编辑" else "可重新打印"
                textSize = 11f
                setTextColor(Design.PRIMARY)
                setPadding(0, Design.dp(4), 0, 0)
            })
            addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        // 操作按钮行：重新打印（全类型可用）+ 再编辑（仅存了状态快照的类型，#5a）
        item.addView(Design.row {
            val reprintBtn = Design.ghostButton("🖨 重新打印")
            addView(reprintBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            reprintBtn.setOnClickListener { reprint(job) }
            if (editable) {
                val editBtn = Design.ghostButton("✏️ 再编辑")
                addView(editBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = Design.dp(8)
                })
                editBtn.setOnClickListener { editJob(job) }
            }
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(8)
            }
        })
        item.isClickable = true
        item.setOnClickListener { reprint(job) }
        return item
    }

    /** 再编辑：带 jobId 启动 MainActivity（CLEAR_TOP 复用栈内实例），恢复编辑页（#5a） */
    private fun editJob(job: HistoryStore.Job) {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_EDIT_JOB, job.id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
    }

    /** 无损重打：载入光栅 → m=2 双打（与当时打印一致） */
    private fun reprint(job: HistoryStore.Job) {
        val raster = HistoryStore.loadRaster(job)
        if (raster == null) {
            Toast.makeText(this, "光栅数据丢失，无法重打", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在重新打印…", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val fault = printer.preflightCheck()
                if (fault != null) {
                    Toast.makeText(this@HistoryActivity, "打印被拦截：$fault", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val r = printer.printRaster(
                    raster,
                    thickness = Settings.thickness,
                    mode = job.mode,
                    halveRows = job.halve,
                    feedBefore = Settings.feedBefore,
                    feedAfter = Settings.feedAfter,
                )
                Toast.makeText(
                    this@HistoryActivity,
                    if (r.ok) "✅ 重新打印完成" else "❌ 重打失败：${r.message}",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(this@HistoryActivity, "重打异常：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
