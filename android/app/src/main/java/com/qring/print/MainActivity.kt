package com.qring.print

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.Editable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 错题小印（Qring / BeePrt BY）58mm 热敏打印机 —— 安卓客户端。
 *
 * 结构（2026-08-11 定稿）：底部 3 Tab + 打印页内二级切换
 *   🏠 首页   —— 设备状态条 + 功能宫格
 *   🖨 打印   —— 顶部二级切换 [文字|图片|错题卡]，三功能完全独立
 *   👤 我的   —— 设备管理 + 关于（调试台/自检页藏这里）
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private val printer get() = PrinterHolder.instance

    // 首页
    private lateinit var statusText: TextView
    private lateinit var statusBadge: TextView
    private lateinit var deviceArea: LinearLayout
    /** 机器状态灯（仿 QrintPrint-Windows StatusLights 四灯 + 打印中）：灰=未知/绿=正常/红=异常 */
    private val statusDots = HashMap<String, View>()
    private val statusDotLabels = HashMap<String, TextView>()
    private var statusRefreshJob: kotlinx.coroutines.Job? = null
    // 打印页二级切换
    private lateinit var subTabText: RadioButton
    private lateinit var subTabImage: RadioButton
    private lateinit var subTabCard: RadioButton
    private lateinit var subTabBarcode: RadioButton
    // 条码区
    private lateinit var barcodeInput: EditText
    private lateinit var barcodeHint: TextView
    private lateinit var barcodePreview: ImageView
    private lateinit var barcodeStatus: TextView
    private var currentBarcodeType: BarcodeGenerator.BarcodeType = BarcodeGenerator.TYPES[0]
    private val barcodeTypeButtons = HashMap<BarcodeGenerator.BarcodeType, android.widget.Button>()
    // 文字区
    private lateinit var input: EditText
    private lateinit var fontGroup: RadioGroup
    private lateinit var alignGroup: RadioGroup
    private lateinit var boldCheck: CheckBox
    private lateinit var textPreview: ImageView
    private lateinit var textStatus: TextView
    // 图片区
    private lateinit var imagePreview: ImageView
    private lateinit var imageStatus: TextView
    private lateinit var modeGroup: RadioGroup
    private lateinit var inkGroup: RadioGroup
    private lateinit var trimCheck: CheckBox
    private lateinit var enhanceCheck: CheckBox
    private lateinit var layoutGroup: RadioGroup
    // 错题卡区
    private lateinit var reasonInput: EditText
    private lateinit var knowledgeInput: EditText
    private lateinit var cardPreview: ImageView
    private lateinit var cardStatus: TextView
    private lateinit var inkGroupCard: RadioGroup
    private lateinit var modeGroupCard: RadioGroup
    private lateinit var layoutGroupCard: RadioGroup
    private lateinit var trimCheckCard: CheckBox
    private lateinit var enhanceCheckCard: CheckBox

    // ── 页面结构 ──
    private lateinit var contentArea: LinearLayout
    private lateinit var homePage: View
    private lateinit var printPage: View
    private lateinit var minePage: View
    private lateinit var textContent: LinearLayout
    private lateinit var imageContent: LinearLayout
    private lateinit var cardContent: LinearLayout
    private lateinit var barcodeContent: LinearLayout
    private lateinit var tabHome: LinearLayout
    private lateinit var tabPrint: LinearLayout
    private lateinit var tabMine: LinearLayout

    private val selectedImages = mutableListOf<Bitmap>()
    /** 扫描发现的未配对 Qring 设备（引导配对用） */
    private val discoveredDevices = mutableSetOf<BluetoothDevice>()

    private val REQ_BT = 1001
    private val REQ_IMAGE = 1002

    companion object {
        const val PAGE_HOME = 0
        const val PAGE_PRINT = 1
        const val PAGE_MINE = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PrinterHolder.init(this)
        Utils.init(this)
        // 深色模式：跟随系统（Activity 在配置变化时自动重建，这里设置后全 UI 生效）
        Design.isDark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

        // ── 根布局：内容区 + 底部导航 ──
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Design.BG)
        }
        contentArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(contentArea, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildBottomNav())

        homePage = buildHomePage()
        printPage = buildPrintPage()
        minePage = buildMinePage()
        contentArea.addView(homePage)
        contentArea.addView(printPage)
        contentArea.addView(minePage)

        setContentView(root)
        switchPage(PAGE_HOME)
        // 自检触发移到 onResume（App 运行时 am start 走 onNewIntent，onCreate 拿不到 extra）
    }

    /**
     * 全管线自检：生成所有功能的预览 PNG 到外部存储（adb pull 检查）。
     * 覆盖：文字（三字号/加粗）、图片（三抖动/消除笔/裁边/增强）、
     * 错题卡（带图/不带图）、三个模板、自检页。
     * 此后渲染代码改动，AI 跑一次自检看图确认，不需要用户实测。
     */
    private fun runPreviewCheck() {
        scope.launch {
            try {
                val dir = getExternalFilesDir(null) ?: return@launch
                val out = java.io.File(dir, "preview_check").apply { mkdirs() }
                fun save(name: String, bmp: Bitmap) {
                    java.io.FileOutputStream(java.io.File(out, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                // 模拟照片（渐变+色块，检验预处理/抖动/消除笔）
                val testPhoto = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(testPhoto)
                canvas.drawColor(Color.WHITE)
                canvas.drawRect(60f, 40f, 740f, 560f, android.graphics.Paint().apply { color = 0xFFE8E8E8.toInt() })
                canvas.drawRect(100f, 80f, 300f, 240f, android.graphics.Paint().apply { color = 0xFF2E2E2E.toInt() })
                canvas.drawRect(120f, 120f, 260f, 220f, android.graphics.Paint().apply { color = 0xFFCC3333.toInt() }) // 红笔
                canvas.drawRect(400f, 80f, 600f, 240f, android.graphics.Paint().apply { color = 0xFF3355CC.toInt() }) // 蓝笔
                canvas.drawLine(100f, 400f, 700f, 460f, android.graphics.Paint().apply { color = 0xFF111111.toInt(); strokeWidth = 10f })
                // 大留白测试图（验证自动裁白边：四周 150px 纯白）
                val testPhotoMargin = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
                val cm = android.graphics.Canvas(testPhotoMargin)
                cm.drawColor(Color.WHITE)
                cm.drawRect(150f, 150f, 650f, 450f, android.graphics.Paint().apply { color = 0xFF2E2E2E.toInt() })

                // 文字三字号 + 加粗
                listOf(32, 48, 64).forEach { size ->
                    val raster = RasterEncoder.encodeText("错题小印测试文字$size", fontSizePx = size)
                    save("text_$size.png", RasterEncoder.rasterToPreviewBitmap(raster))
                }
                // 图片：三抖动 + 消除笔红蓝 + 裁边 + 增强
                DitherMode.entries.forEach { m ->
                    save("img_${m.name}.png", imagePreviewRaster(RasterEncoder.encode(testPhoto, m)))
                }
                save("img_ink_red.png", imagePreviewRaster(RasterEncoder.encode(ImageEnhancer.removeInk(testPhoto, InkRemoveMode.RED), DitherMode.FLOYD_STEINBERG)))
                save("img_ink_both.png", imagePreviewRaster(RasterEncoder.encode(ImageEnhancer.removeInk(testPhoto, InkRemoveMode.BOTH), DitherMode.FLOYD_STEINBERG)))
                save("img_trim.png", imagePreviewRaster(RasterEncoder.encode(ImageEnhancer.trimWhiteEdges(testPhotoMargin), DitherMode.FLOYD_STEINBERG)))
                save("img_trim_ref.png", imagePreviewRaster(RasterEncoder.encode(testPhotoMargin, DitherMode.FLOYD_STEINBERG)))
                save("img_enhance.png", imagePreviewRaster(RasterEncoder.encode(RasterEncoder.rasterToPreviewBitmap(ImageEnhancer.enhanceToRaster(testPhoto)), DitherMode.NONE)))
                // 错题卡：带图（塞入测试图）+ 不带图
                selectedImages.clear()
                selectedImages.add(testPhoto)
                save("card_with_img.png", imagePreviewRaster(cardRaster("概念不清", "一元二次方程")))
                selectedImages.clear()
                save("card_no_img.png", imagePreviewRaster(cardRaster("计算错误", "勾股定理")))
                // 模板三件套
                save("tpl_course.png", imagePreviewRaster(RasterEncoder.encode(TemplateLibrary.courseTable(), DitherMode.NONE, RasterEncoder.THRESHOLD_TEXT)))
                save("tpl_word.png", imagePreviewRaster(RasterEncoder.encode(TemplateLibrary.wordList(), DitherMode.NONE, RasterEncoder.THRESHOLD_TEXT)))
                save("tpl_plan.png", imagePreviewRaster(RasterEncoder.encode(TemplateLibrary.dailyPlan(), DitherMode.NONE, RasterEncoder.THRESHOLD_TEXT)))
                // 自检页
                save("selftest.png", imagePreviewRaster(RasterEncoder.encode(SelfTest.build(), DitherMode.FLOYD_STEINBERG)))
                // 条码：QR + Code128（校验各类型可用）
                runCatching {
                    save("barcode_qr.png", imagePreviewRaster(RasterEncoder.encode(
                        BarcodeGenerator.encodeBitmap(BarcodeGenerator.TYPES[0], "https://example.com/test"),
                        DitherMode.NONE, RasterEncoder.THRESHOLD_IMAGE)))
                }
                runCatching {
                    save("barcode_code128.png", imagePreviewRaster(RasterEncoder.encode(
                        BarcodeGenerator.encodeBitmap(BarcodeGenerator.TYPES[1], "ABC-123"),
                        DitherMode.NONE, RasterEncoder.THRESHOLD_IMAGE)))
                }
                // 文字对齐三种（验证左/中/右）
                listOf(0, 1, 2).forEach { align ->
                    save("text_align_$align.png", RasterEncoder.rasterToPreviewBitmap(
                        RasterEncoder.encodeText("对齐测试", fontSizePx = 48, align = align)))
                }

                // 页面快照（离线渲染 View 树成 PNG，验证布局/配色/图标，不受窗口遮挡影响）
                fun snapshot(view: View, name: String) {
                    try {
                        val specW = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
                        val specH = View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST)
                        view.measure(specW, specH)
                        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                        val bmp = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888)
                        view.draw(android.graphics.Canvas(bmp))
                        save(name, bmp)
                    } catch (e: Exception) {
                        android.util.Log.e("QringPrint", "页面快照失败 $name: ${e.message}")
                    }
                }
                snapshot(homePage, "page_home.png")
                switchPage(PAGE_PRINT)
                snapshot(printPage, "page_print_text.png")
                subTabImage.isChecked = true
                snapshot(printPage, "page_print_image.png")
                subTabCard.isChecked = true
                snapshot(printPage, "page_print_card.png")
                subTabBarcode.isChecked = true
                snapshot(printPage, "page_print_barcode.png")
                subTabText.isChecked = true
                switchPage(PAGE_MINE)
                snapshot(minePage, "page_mine.png")
                switchPage(PAGE_HOME)

                android.util.Log.w("QringPrint", "预览自检完成: ${out.absolutePath} (${out.listFiles()?.size ?: 0} 张)")
                finish()
            } catch (e: Exception) {
                android.util.Log.e("QringPrint", "预览自检失败: ${e.message}", e)
                finish()
            }
        }
    }

    // ── 底部导航（3 Tab，M3 Navigation Bar：图标 + 文字 + 选中高亮）──
    private fun buildBottomNav(): LinearLayout {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Design.dp(4), Design.dp(6), Design.dp(4), Design.dp(6))
            setBackgroundColor(Design.SURFACE_CONTAINER_LOW)
        }
        fun tabItem(iconCode: String, label: String): LinearLayout {
            val t = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, Design.dp(4), 0, Design.dp(4))
                isClickable = true
            }
            t.addView(Design.Icons.textViewFilled(iconCode, 26, Design.TEXT_SUB))
            t.addView(TextView(this).apply {
                text = label
                textSize = 10.5f
                gravity = Gravity.CENTER
                setTextColor(Design.TEXT_SUB)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, Design.dp(2), 0, 0)
            })
            return t
        }
        tabHome = tabItem(Design.Icons.HOME, "首页")
        tabPrint = tabItem(Design.Icons.PRINT, "打印")
        tabMine = tabItem(Design.Icons.PERSON, "我的")
        tabHome.setOnClickListener { switchPage(PAGE_HOME) }
        tabPrint.setOnClickListener { switchPage(PAGE_PRINT) }
        tabMine.setOnClickListener { switchPage(PAGE_MINE) }
        listOf(tabHome, tabPrint, tabMine).forEach { t ->
            nav.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        return nav
    }

    /** 底部 Tab 选中着色：图标 + 文字同步变色 */
    private fun colorTab(tab: View, active: Boolean) {
        val c = if (active) Design.PRIMARY else Design.TEXT_SUB
        if (tab is LinearLayout) {
            for (i in 0 until tab.childCount) {
                (tab.getChildAt(i) as? TextView)?.setTextColor(c)
            }
        }
    }

    private fun switchPage(index: Int) {
        homePage.visibility = if (index == PAGE_HOME) View.VISIBLE else View.GONE
        printPage.visibility = if (index == PAGE_PRINT) View.VISIBLE else View.GONE
        minePage.visibility = if (index == PAGE_MINE) View.VISIBLE else View.GONE
        listOf(tabHome, tabPrint, tabMine).forEachIndexed { i, t ->
            colorTab(t, i == index)
        }
    }

    // ═══════════════════════ 首页 ═══════════════════════

    private fun buildHomePage(): View {
        val page = Design.page()
        val scroll = ScrollView(this).apply { setBackgroundColor(Design.BG) }
        scroll.addView(page)

        page.addView(Design.header("🖨 错题小印打印"))

        // 设备状态条（2026-08-11 增强：机器状态灯 + 电量，10s 轮询刷新）
        page.addView(Design.card {
            addView(Design.caption("设备状态"))
            addView(Design.row {
                statusBadge = TextView(this@MainActivity).apply {
                    text = "● 未连接"
                    textSize = 15f
                    setTextColor(Design.TEXT_SUB)
                    typeface = Typeface.DEFAULT_BOLD
                }
                addView(statusBadge, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                val btn = Design.outlineButton("选择设备")
                addView(btn)
                btn.setOnClickListener { switchPage(PAGE_MINE) }
            })
            // 机器状态灯：电量/缺纸/开盖/过热/打印中 五灯（圆点+标签，灰=未知 绿=正常 红=异常 蓝=打印中）
            addView(Design.row {
                for ((key, name) in listOf(
                    "battery" to "电量", "paper" to "缺纸",
                    "cover" to "开盖", "thermal" to "过热", "printing" to "打印中"
                )) {
                    val dot = View(this@MainActivity).apply {
                        background = Design.rounded(0xFFB4BACB.toInt(), Design.dp(12).toFloat())
                    }
                    val label = TextView(this@MainActivity).apply {
                        text = name
                        textSize = 12f
                        setTextColor(Design.TEXT_SUB)
                        setPadding(Design.dp(4), 0, Design.dp(10), 0)
                    }
                    statusDots[key] = dot
                    statusDotLabels[key] = label
                    addView(dot, LinearLayout.LayoutParams(Design.dp(12), Design.dp(12)))
                    addView(label)
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(6)
            })
            statusText = TextView(this@MainActivity).apply {
                text = "点「选择设备」连接打印机，或直接开始打印"
                textSize = 12f
                setTextColor(Design.TEXT_SUB)
                setPadding(0, Design.dp(4), 0, 0)
            }
            addView(statusText)
        })

        // 功能宫格：2 列 × 3 行，Material Symbols 图标 + 彩色图标底（M3 风格入口）
        page.addView(Design.sectionTitle("常用功能"))
        data class GridEntry(val icon: String, val iconColor: Int, val iconBg: Int, val label: String, val action: () -> Unit)
        // 图标底色（浅色模式浅底深字；深色模式深底浅字）
        val dark = Design.isDark
        val grid = arrayOf(
            GridEntry(Design.Icons.EDIT_NOTE, if (dark) 0xFFA5D6A7.toInt() else 0xFF1B5E20.toInt(), if (dark) 0xFF1D3A20.toInt() else 0xFFE8F5E9.toInt(), "文字打印", { switchPage(PAGE_PRINT); subTabText.isChecked = true; input.requestFocus() }),
            GridEntry(Design.Icons.IMAGE, if (dark) 0xFF90CAF9.toInt() else 0xFF0D47A1.toInt(), if (dark) 0xFF0D2B45.toInt() else 0xFFE3F2FD.toInt(), "图片打印", { switchPage(PAGE_PRINT); subTabImage.isChecked = true }),
            GridEntry(Design.Icons.MENU_BOOK, if (dark) 0xFFFFCC80.toInt() else 0xFFE65100.toInt(), if (dark) 0xFF3D2A10.toInt() else 0xFFFFF3E0.toInt(), "错题卡", { switchPage(PAGE_PRINT); subTabCard.isChecked = true }),
            GridEntry(Design.Icons.LIST_ALT, if (dark) 0xFF90A4AE.toInt() else 0xFF37474F.toInt(), if (dark) 0xFF1E2A30.toInt() else 0xFFECEFF1.toInt(), "条码打印", { switchPage(PAGE_PRINT); subTabBarcode.isChecked = true }),
            GridEntry(Design.Icons.CALENDAR, if (dark) 0xFFCE93D8.toInt() else 0xFF6A1B9A.toInt(), if (dark) 0xFF2D1B3A.toInt() else 0xFFF3E5F5.toInt(), "课程表", { printTemplate { TemplateLibrary.courseTable() } }),
            GridEntry(Design.Icons.CHECKLIST, if (dark) 0xFF80CBC4.toInt() else 0xFF00695C.toInt(), if (dark) 0xFF0F2E2B.toInt() else 0xFFE0F7FA.toInt(), "单词表", { printTemplate { TemplateLibrary.wordList() } }),
            GridEntry(Design.Icons.SCHEDULE, if (dark) 0xFFF48FB1.toInt() else 0xFFAD1457.toInt(), if (dark) 0xFF3A1B2A.toInt() else 0xFFFCE4EC.toInt(), "每日计划", { printTemplate { TemplateLibrary.dailyPlan() } }),
            GridEntry(Design.Icons.HISTORY, if (dark) 0xFFBCAAA4.toInt() else 0xFF4E342E.toInt(), if (dark) 0xFF2B1F1C.toInt() else 0xFFEFE9E7.toInt(), "打印历史", { startActivity(Intent(this@MainActivity, HistoryActivity::class.java)) }),
        )
        for (i in grid.indices step 2) {
            val g1 = grid[i]
            val g2 = grid[i + 1]
            page.addView(Design.row {
                addView(gridItem(g1.icon, g1.iconColor, g1.iconBg, g1.label, g1.action),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(gridItem(g2.icon, g2.iconColor, g2.iconBg, g2.label, g2.action),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = Design.dp(10)
                    })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(10)
            })
        }

        page.addView(Design.card {
            addView(Design.sectionTitle("使用提示"))
            addView(Design.caption("1. 首次使用先连接打印机（我的 → 选择设备）\n2. 打印页顶部切换文字/图片/错题卡\n3. 拍试卷推荐图片页的一键增强\n4. 所有打印先预览，确认效果再打防废纸"))
        })
        return scroll
    }

    /** 宫格项：大号填充图标（彩色圆底）+ 文字居中在图标正下方（M3 入口风格） */
    private fun gridItem(icon: String, iconColor: Int, iconBg: Int, label: String, action: () -> Unit): LinearLayout {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(Design.dp(8), Design.dp(18), Design.dp(8), Design.dp(16))
            background = Design.pressable(
                Design.rounded(Design.SURFACE_CONTAINER_LOW, Design.RADIUS_SM),
                Design.rounded(Design.PRIMARY_CONTAINER, Design.RADIUS_SM),
            )
            isClickable = true
            setOnClickListener { action() }
        }
        // 彩色大圆底 + Sharp 填充图标（38sp，醒目精致）
        val iconWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(Design.dp(16), Design.dp(16), Design.dp(16), Design.dp(16))
            background = Design.rounded(iconBg, Design.dp(36).toFloat())
        }
        iconWrap.addView(Design.Icons.textViewFilled(icon, 38, iconColor))
        item.addView(iconWrap)
        // 文字居中在图标正下方
        item.addView(TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Design.TEXT)
            setPadding(0, Design.dp(10), 0, 0)
        })
        return item
    }

    // ═══════════════════════ 打印页（二级切换：文字/图片/错题卡） ═══════════════════════

    private fun buildPrintPage(): View {
        val page = Design.page()
        val scroll = ScrollView(this).apply { setBackgroundColor(Design.BG) }
        scroll.addView(page)

        // 二级功能切换（顶部胶囊）
        val subGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(Design.dp(4), 0, Design.dp(4), 0)
            background = Design.rounded(0xFFEDF0F7.toInt(), Design.RADIUS_SM)
        }
        fun subTab(text: String): RadioButton = RadioButton(this).apply {
            this.text = text
            textSize = 13.5f
            gravity = Gravity.CENTER
            isAllCaps = false
            minHeight = Design.dp(40)
            setButtonDrawable(android.R.color.transparent)
            id = View.generateViewId()
            // checked 态持续高亮（浅绿底），未选容器色——用户能明确当前所处功能页
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_checked), Design.rounded(Design.PRIMARY_CONTAINER, Design.RADIUS_SM))
                addState(intArrayOf(android.R.attr.state_pressed), Design.rounded(Design.PRIMARY_CONTAINER, Design.RADIUS_SM))
                addState(intArrayOf(), Design.rounded(Design.SURFACE_CONTAINER, Design.RADIUS_SM))
            }
        }
        subTabText = subTab("📝 文字")
        subTabImage = subTab("🖼 图片")
        subTabCard = subTab("🎴 错题卡")
        subTabBarcode = subTab("🏷 条码")
        subGroup.addView(subTabText, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
        subGroup.addView(subTabImage, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
        subGroup.addView(subTabCard, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
        subGroup.addView(subTabBarcode, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
        subGroup.setOnCheckedChangeListener { _, checkedId ->
            // 切页：只显示当前功能内容，互不掺和
            textContent.visibility = if (checkedId == subTabText.id) View.VISIBLE else View.GONE
            imageContent.visibility = if (checkedId == subTabImage.id) View.VISIBLE else View.GONE
            cardContent.visibility = if (checkedId == subTabCard.id) View.VISIBLE else View.GONE
            barcodeContent.visibility = if (checkedId == subTabBarcode.id) View.VISIBLE else View.GONE
            // 着色
            listOf(subTabText, subTabImage, subTabCard, subTabBarcode).forEach {
                it.setTextColor(if (it.id == checkedId) Design.PRIMARY else Design.TEXT_SUB)
            }
        }
        page.addView(subGroup)

        // 四个功能内容块（独立构建，visibility 切换）
        textContent = buildTextContent()
        imageContent = buildImageContent()
        cardContent = buildCardContent()
        barcodeContent = buildBarcodeContent()
        page.addView(textContent)
        page.addView(imageContent)
        page.addView(cardContent)
        page.addView(barcodeContent)
        // 默认文字页
        subTabText.isChecked = true
        return scroll
    }

    // ── 文字内容块 ──
    private fun buildTextContent(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(Design.card {
            addView(Design.sectionTitle("文字打印"))
            addView(Design.caption("输入内容，选择字号，直接打印"))
            input = Design.input("输入要打印的文字（如错题内容）", lines = 3)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(8)
            })
            addView(Design.label("字号"))
            fontGroup = Design.segmentGroup(
                listOf("小" to 32, "中" to 48, "大" to 64),
                defaultIndex = 1,
            ) { autoRefreshTextPreview() }
            addView(fontGroup)
            // 对齐（2026-08-11 加，参考 QrintPrint-Windows）
            addView(Design.label("对齐"))
            alignGroup = Design.segmentGroup(
                listOf("左对齐" to 0, "居中" to 1, "右对齐" to 2),
                defaultIndex = 0,
            ) { autoRefreshTextPreview() }
            addView(alignGroup)
            boldCheck = Design.check("加粗（小字号更清晰）")
            boldCheck.setOnCheckedChangeListener { _, _ -> autoRefreshTextPreview() }
            addView(boldCheck, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(4)
            })
        })

        col.addView(Design.card {
            addView(Design.sectionTitle("打印预览"))
            textPreview = ImageView(this@MainActivity).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            addView(textPreview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(8)
            })
            addView(Design.caption("先预览再打印，防废纸"))
            val previewBtn = Design.outlineButton("👁 预览打印效果")
            addView(previewBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(8)
            })
            previewBtn.setOnClickListener { renderTextPreview() }
        })

        val printBtn = Design.primaryButton("🖨 打印文字")
        col.addView(printBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Design.dp(4)
        })
        printBtn.setOnClickListener { doPrintText() }

        textStatus = Design.caption("")
        col.addView(textStatus)
        return col
    }

    // ── 图片内容块 ──
    private fun buildImageContent(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(Design.card {
            addView(Design.sectionTitle("图片打印"))
            addView(Design.caption("多选图可拼接省纸；消除笔去批改痕迹；增强处理拍试卷"))
            addView(Design.row {
                val pickBtn = Design.outlineButton("🖼 选择图片(可多选)")
                val clearBtn = Design.ghostButton("清除")
                addView(pickBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(clearBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = Design.dp(8)
                })
                pickBtn.setOnClickListener {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "image/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    startActivityForResult(intent, REQ_IMAGE)
                }
                clearBtn.setOnClickListener {
                    selectedImages.clear()
                    imagePreview.setImageDrawable(null)
                    imageStatus.text = "已清除图片"
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(10)
            })
            addView(Design.label("拼接布局"))
            layoutGroup = Design.segmentGroup(
                listOf("单列" to 0, "双列(省纸)" to 1),
                defaultIndex = 1,
            ) { autoRefreshImagePreview() }
            addView(layoutGroup)
            addView(Design.label("抖动模式"))
            modeGroup = Design.segmentGroup(
                listOf(
                    "无(锐利)" to DitherMode.NONE,
                    "Floyd(照片)" to DitherMode.FLOYD_STEINBERG,
                    "Atkinson(高对比)" to DitherMode.ATKINSON,
                ),
                defaultIndex = 0,
            ) { autoRefreshImagePreview() }
            addView(modeGroup)
            addView(Design.label("消除笔（去批改痕迹）"))
            inkGroup = Design.segmentGroup(
                InkRemoveMode.entries.map { it.label to it },
                defaultIndex = 0,
            ) { autoRefreshImagePreview() }
            addView(inkGroup)
            trimCheck = Design.check("✂️ 自动裁白边（去掉照片四周多余留白）")
            trimCheck.setOnCheckedChangeListener { _, _ -> autoRefreshImagePreview() }
            addView(trimCheck, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(4)
            })
            enhanceCheck = Design.check("✨ 一键增强（去背景/阴影/手写，拍试卷推荐）")
            enhanceCheck.setOnCheckedChangeListener { _, _ -> autoRefreshImagePreview() }
            addView(enhanceCheck, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(4)
            })
        })

        col.addView(Design.card {
            addView(Design.sectionTitle("打印预览"))
            imagePreview = ImageView(this@MainActivity).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            addView(imagePreview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(8)
            })
            addView(Design.caption("先预览再打印，防废纸"))
            val previewBtn = Design.outlineButton("👁 预览打印效果")
            addView(previewBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(8)
            })
            previewBtn.setOnClickListener { renderImagePreview() }
        })

        val printBtn = Design.primaryButton("🖨 打印图片")
        col.addView(printBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Design.dp(4)
        })
        printBtn.setOnClickListener { doPrintImage() }

        imageStatus = Design.caption("")
        col.addView(imageStatus)
        return col
    }

    // ── 错题卡内容块 ──
    private fun buildCardContent(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(Design.card {
            addView(Design.sectionTitle("错题卡打印"))
            addView(Design.caption("题目图（可选）+ 错因 + 知识点，自动排版"))
            reasonInput = Design.input("错因（如：概念不清）")
            addView(reasonInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(8)
            })
            knowledgeInput = Design.input("知识点（如：一元二次方程）")
            addView(knowledgeInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(8)
            })
            addView(Design.row {
                val pickBtn = Design.outlineButton("🖼 选择题目图")
                val clearBtn = Design.ghostButton("清除图片")
                addView(pickBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(clearBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = Design.dp(8)
                })
                pickBtn.setOnClickListener {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "image/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    startActivityForResult(intent, REQ_IMAGE)
                }
                clearBtn.setOnClickListener {
                    selectedImages.clear()
                    imagePreview.setImageDrawable(null)
                    cardStatus.text = "已清除题目图片"
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(10)
            })
            // 题目图处理选项（与图片页同套：抖动/消除笔/裁白边/一键增强，2026-08-11 用户要求）
            addView(Design.label("题目图排列（多图可选）"))
            layoutGroupCard = Design.segmentGroup(
                listOf("单列" to 0, "双列(省纸)" to 1),
                defaultIndex = 1,
            ) { autoRefreshCardPreview() }
            addView(layoutGroupCard)
            addView(Design.label("抖动模式"))
            modeGroupCard = Design.segmentGroup(
                listOf(
                    "无(锐利)" to DitherMode.NONE,
                    "Floyd(照片)" to DitherMode.FLOYD_STEINBERG,
                    "Atkinson(高对比)" to DitherMode.ATKINSON,
                ),
                defaultIndex = 1,   // 错题卡含照片，Floyd 默认最稳
            ) { autoRefreshCardPreview() }
            addView(modeGroupCard)
            addView(Design.label("题目图处理（拍试卷推荐）"))
            inkGroupCard = Design.segmentGroup(
                InkRemoveMode.entries.map { it.label to it },
                defaultIndex = 0,
            ) { autoRefreshCardPreview() }
            addView(inkGroupCard)
            trimCheckCard = Design.check("✂️ 自动裁白边（去掉四周留白）")
            trimCheckCard.setOnCheckedChangeListener { _, _ -> autoRefreshCardPreview() }
            addView(trimCheckCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(4)
            })
            enhanceCheckCard = Design.check("✨ 一键增强（去背景/阴影/手写）")
            enhanceCheckCard.setOnCheckedChangeListener { _, _ -> autoRefreshCardPreview() }
            addView(enhanceCheckCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(4)
            })
        })

        col.addView(Design.card {
            addView(Design.sectionTitle("打印预览"))
            cardPreview = ImageView(this@MainActivity).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            addView(cardPreview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(8)
            })
            addView(Design.caption("先预览确认版式，满意再打"))
            val previewBtn = Design.outlineButton("👁 预览错题卡")
            addView(previewBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(8)
            })
            previewBtn.setOnClickListener { renderCardPreview() }
        })

        val printBtn = Design.primaryButton("🎴 生成并打印错题卡")
        col.addView(printBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Design.dp(4)
        })
        printBtn.setOnClickListener { printTemplateCard() }

        cardStatus = Design.caption("")
        col.addView(cardStatus)
        return col
    }

    // ── 条码内容块（2026-08-11 加，参考 QrintPrint-Windows）──
    private fun buildBarcodeContent(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(Design.card {
            addView(Design.sectionTitle("条码 / 二维码打印"))
            addView(Design.caption("二维码 / 条形码，打印后手机可扫"))
            addView(Design.label("条码类型"))
            // 类型网格 2 列（自定义互斥高亮，选中=primary-container 持续高亮）
            BarcodeGenerator.TYPES.chunked(2).forEach { row ->
                addView(Design.row {
                    row.forEach { type ->
                        val btn = Design.outlineButton(type.label)
                        barcodeTypeButtons[type] = btn
                        btn.setOnClickListener { selectBarcodeType(type) }
                        addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (row.indexOf(type) > 0) marginStart = Design.dp(6)
                        })
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = Design.dp(6)
                })
            }
            barcodeInput = Design.input("输入内容（文字/链接/数字）")
            addView(barcodeInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(8)
            })
            barcodeHint = Design.caption(BarcodeGenerator.TYPES[0].hint)
            addView(barcodeHint, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(4)
            })
            // 输入变化 → 实时校验提示 + 自动刷新预览
            barcodeInput.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val err = BarcodeGenerator.validate(currentBarcodeType, barcodeInput.text.toString())
                    if (err != null) {
                        barcodeHint.text = "⚠️ $err"
                        barcodeHint.setTextColor(Design.ERROR)
                    } else {
                        barcodeHint.text = currentBarcodeType.hint
                        barcodeHint.setTextColor(Design.TEXT_SUB)
                    }
                    autoRefreshBarcodePreview()
                }
            })
            // 默认选中第一个类型（QR）并高亮 —— 必须在 barcodeInput/barcodeHint 创建之后
            // （2026-08-11 修：此前在创建前调用会触发 lateinit 未初始化崩溃）
            selectBarcodeType(BarcodeGenerator.TYPES[0])
        })

        // 预览
        col.addView(Design.card {
            addView(Design.sectionTitle("打印预览"))
            barcodePreview = ImageView(this@MainActivity).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            addView(barcodePreview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(8)
            })
            addView(Design.caption("先预览再打印，防废纸"))
            // 手动预览按钮（2026-08-11 补：与其他页一致）
            val previewBtn = Design.outlineButton("👁 预览打印效果")
            addView(previewBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(8)
            })
            previewBtn.setOnClickListener {
                val err = BarcodeGenerator.validate(currentBarcodeType, barcodeInput.text.toString())
                if (err != null) {
                    barcodeStatus.text = "内容无效：$err"
                    barcodeStatus.setTextColor(Design.ERROR)
                } else {
                    renderBarcodePreview()
                }
            }
        })

        val printBtn = Design.primaryButton("🏷 打印条码")
        col.addView(printBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Design.dp(4)
        })
        printBtn.setOnClickListener { doPrintBarcode() }

        barcodeStatus = Design.caption("")
        col.addView(barcodeStatus)
        return col
    }

    /** 选中条码类型：更新当前类型 + 按钮高亮（选中 primary-container 持续高亮） */
    private fun selectBarcodeType(type: BarcodeGenerator.BarcodeType) {
        currentBarcodeType = type
        barcodeTypeButtons.forEach { (t, b) ->
            b.background = if (t == type) {
                Design.rounded(Design.PRIMARY_CONTAINER, Design.RADIUS_SM)
            } else {
                Design.pressable(
                    Design.rounded(Design.SURFACE, Design.RADIUS_SM, Design.OUTLINE, 1),
                    Design.rounded(Design.PRIMARY_CONTAINER, Design.RADIUS_SM),
                )
            }
        }
        // 构建期可能在其他字段就绪前调用，加 isInitialized 保护
        if (::barcodeHint.isInitialized) {
            barcodeHint.text = type.hint
            barcodeHint.setTextColor(Design.TEXT_SUB)
        }
        autoRefreshBarcodePreview()
    }

    /**
     * 条码自动刷新预览：
     * - 内容有效 → 重渲
     * - 内容无效/空 → 清空预览区（2026-08-11 修：换类型后旧图残留误导，用户反馈"换了类型预览没刷新"）
     */
    private fun autoRefreshBarcodePreview() {
        if (!::barcodeInput.isInitialized) return
        val text = barcodeInput.text.toString().trim()
        if (text.isEmpty()) {
            if (::barcodePreview.isInitialized) barcodePreview.setImageDrawable(null)
            return
        }
        if (BarcodeGenerator.validate(currentBarcodeType, text) == null) {
            renderBarcodePreview()
        } else if (::barcodePreview.isInitialized && barcodePreview.drawable != null) {
            barcodePreview.setImageDrawable(null)
            barcodeStatus.text = "内容对当前条码类型无效，预览已清空"
            barcodeStatus.setTextColor(Design.ERROR)
        }
    }

    /** 条码预览：校验 → 生成 → 走图片通道渲染 */
    private fun renderBarcodePreview() {
        if (!::barcodePreview.isInitialized) return  // 构建期字段未就绪保护
        try {
            val err = BarcodeGenerator.validate(currentBarcodeType, barcodeInput.text.toString())
            if (err != null) {
                barcodeStatus.text = "内容无效：$err"
                barcodeStatus.setTextColor(Design.ERROR)
                return
            }
            val bmp = BarcodeGenerator.encodeBitmap(currentBarcodeType, barcodeInput.text.toString())
            val raster = RasterEncoder.encode(bmp, DitherMode.NONE, RasterEncoder.THRESHOLD_IMAGE)
            barcodePreview.setImageBitmap(imagePreviewRaster(raster))
            barcodeStatus.text = "条码预览已生成（${raster.widthBytes * 8}×${raster.height} 点）"
            barcodeStatus.setTextColor(Design.TEXT_SUB)
        } catch (e: Exception) {
            barcodeStatus.text = "生成失败：${e.javaClass.simpleName}"
            barcodeStatus.setTextColor(Design.ERROR)
        }
    }

    /** 条码打印：预览确认 → 图片通道（m=2 + 行合并） */
    private fun doPrintBarcode() {
        barcodeStatus.text = "正在生成预览 ..."
        scope.launch {
            try {
                val err = BarcodeGenerator.validate(currentBarcodeType, barcodeInput.text.toString())
                if (err != null) {
                    barcodeStatus.setTextColor(Design.ERROR)
                    barcodeStatus.text = "内容无效：$err"
                    return@launch
                }
                val bmp = BarcodeGenerator.encodeBitmap(currentBarcodeType, barcodeInput.text.toString())
                val raster = RasterEncoder.encode(bmp, DitherMode.NONE, RasterEncoder.THRESHOLD_IMAGE)
                val previewBmp = imagePreviewRaster(raster)
                barcodePreview.setImageBitmap(previewBmp)
                previewConfirmDialog("确认打印条码", previewBmp) {
                    doPrintConfirmed(raster, mode = 2, halveRows = true, okMessage = "条码打印完成",
                        statusView = barcodeStatus, historyType = "条码", historyTitle = barcodeInput.text.toString().take(20))
                }
            } catch (e: Exception) {
                barcodeStatus.setTextColor(Design.ERROR)
                barcodeStatus.text = "条码生成失败：${e.javaClass.simpleName} ${e.message}"
            }
        }
    }

    // ═══════════════════════ 我的页 ═══════════════════════

    private fun buildMinePage(): View {
        val page = Design.page()
        val scroll = ScrollView(this).apply { setBackgroundColor(Design.BG) }
        scroll.addView(page)

        // 打印历史入口（2026-08-11 加）
        page.addView(Design.card {
            addView(Design.sectionTitle("打印记录"))
            addView(Design.caption("最近 100 条打印，可一键重新打印"))
            val historyBtn = Design.outlineButton("🕘 打印历史")
            addView(historyBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(8)
            })
            historyBtn.setOnClickListener { startActivity(Intent(this@MainActivity, HistoryActivity::class.java)) }
        })

        page.addView(Design.card {
            addView(Design.sectionTitle("设备管理"))
            addView(Design.caption("已配对 / 扫描到的打印机，点一下连接"))
            deviceArea = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(deviceArea, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(8)
            })
            val refreshBtn = Design.outlineButton("🔄 刷新设备列表")
            addView(refreshBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(8)
            })
            refreshBtn.setOnClickListener {
                discoveredDevices.clear()
                stopBleScan()
                listDevices()
                startBleScan()
            }
        })

        // 打印设置（2026-08-11 加，参考 QrintPrint-Windows：浓度/进纸/出纸可调并持久化）
        page.addView(Design.card {
            addView(Design.sectionTitle("打印设置"))
            addView(Design.caption("加热浓度（X1 合法 0~2）· 前后走纸点数"))
            addView(Design.label("浓度（0 淡 / 1 中 / 2 浓）"))
            val thicknessGroup = Design.segmentGroup(
                listOf("0(淡)" to 0, "1(中)" to 1, "2(浓)" to 2),
                defaultIndex = Settings.thickness,
            ) { i ->
                Settings.thickness = i
            }
            addView(thicknessGroup)
            addView(Design.row {
                addView(Design.label("进纸（打印前走纸）"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Design.label("出纸（打印后走纸）"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
            addView(Design.row {
                val feedBeforeInput = Design.input("默认 10", lines = 1)
                feedBeforeInput.setText(Settings.feedBefore.toString())
                feedBeforeInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                val feedAfterInput = Design.input("默认 100", lines = 1)
                feedAfterInput.setText(Settings.feedAfter.toString())
                feedAfterInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                addView(feedBeforeInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(feedAfterInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = Design.dp(8)
                })
                // 失焦时保存（非法/空值回退默认，Settings 内部 clamp 0~255）
                feedBeforeInput.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) Settings.feedBefore = feedBeforeInput.text.toString().toIntOrNull() ?: 10
                }
                feedAfterInput.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) Settings.feedAfter = feedAfterInput.text.toString().toIntOrNull() ?: 100
                }
            })
        })

        page.addView(Design.card {
            addView(Design.row {
                addView(TextView(this@MainActivity).apply {
                    text = "ℹ️ 关于错题小印打印"
                    textSize = 14f
                    setTextColor(Design.TEXT)
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@MainActivity).apply {
                    text = "v0.2.0"
                    textSize = 12f
                    setTextColor(Design.TEXT_SUB)
                    gravity = Gravity.END
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
            addView(Design.caption("学科网错题小印 X1 热敏打印机的替代客户端\n协议逆向自开源项目 QrintPrint (MIT)"))
            // 隐藏入口：自检页 + 调试台
            val hiddenRow = Design.row()
            val selfTestLink = TextView(this@MainActivity).apply {
                text = "打印测试页"
                textSize = 11f
                setTextColor(0xFFB4BACB.toInt())
                setPadding(0, Design.dp(8), 0, 0)
                setOnClickListener { printSelfTest() }
            }
            val debugLink = TextView(this@MainActivity).apply {
                text = "调试台"
                textSize = 11f
                setTextColor(0xFFB4BACB.toInt())
                setPadding(0, Design.dp(8), 0, 0)
                setOnClickListener { startActivity(Intent(this@MainActivity, DebugActivity::class.java)) }
            }
            hiddenRow.addView(selfTestLink)
            hiddenRow.addView(debugLink, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Design.dp(16)
            })
            addView(hiddenRow)
        })

        return scroll
    }

    // ═══════════════════════ 生命周期 ═══════════════════════

    override fun onResume() {
        super.onResume()
        // 渲染管线自检（adb 触发，AI/开发者自查用，不打扰用户）：
        //   adb shell am start -n com.qring.print/.MainActivity --ez run_preview_check true
        // onResume 检查：App 运行时 am start 走 onNewIntent，onCreate 拿不到 extra
        if (intent.getBooleanExtra("run_preview_check", false)) {
            intent.removeExtra("run_preview_check")
            runPreviewCheck()
        }
        ensurePermission()
        startBleScan()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // App 前台运行时 am start 走 onNewIntent（不走 onCreate/onResume）——自检在此触发
        if (intent.getBooleanExtra("run_preview_check", false)) {
            intent.removeExtra("run_preview_check")
            runPreviewCheck()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // configChanges 声明后系统不重建 Activity，深色切换需手动刷新主题并重建 UI
        val dark = (newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (dark != Design.isDark) {
            Design.isDark = dark
            recreate()
        }
    }

    override fun onPause() {
        super.onPause()
        stopBleScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        kotlinx.coroutines.runBlocking { printer.disconnect() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMAGE && resultCode == RESULT_OK) {
            val uris = mutableListOf<Uri>()
            if (data?.clipData != null) {
                for (i in 0 until data.clipData!!.itemCount) {
                    data.clipData!!.getItemAt(i).uri?.let { uris.add(it) }
                }
            }
            data?.data?.let { uris.add(it) }
            if (uris.isEmpty()) return
            for (uri in uris) {
                loadImage(uri)?.let { selectedImages.add(it) }
            }
            updateThumbnail()
        }
    }

    private fun loadImage(uri: Uri): Bitmap? {
        return try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val ratio = bounds.outWidth / 384.0
            val sample = if (ratio > 1) {
                var s = 1
                while (s * 2 < ratio) s *= 2
                s
            } else 1
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            imageStatus.text = "读取图片失败：${e.message}"
            null
        }
    }

    /** 选图后：图片页/错题卡页共享 selectedImages，缩略图显示在图片页预览区 */
    private fun updateThumbnail() {
        if (selectedImages.isEmpty()) return
        val layout = (layoutGroup.checkedRadioButtonId.takeIf { it != -1 }
            ?.let { layoutGroup.findViewById<RadioButton>(it)?.tag as? Int }) ?: 1
        val composed = RasterEncoder.composeImages(selectedImages, layout)
        imagePreview.setImageBitmap(composed)
        imageStatus.text = "已选 ${selectedImages.size} 张图"
    }

    private fun ensurePermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            val perms = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            val granted = perms.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
            if (!granted) {
                ActivityCompat.requestPermissions(this, perms, REQ_BT)
            } else {
                listDevices()
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH), REQ_BT)
            } else {
                listDevices()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            listDevices()
        } else {
            statusText.text = "未授予蓝牙权限，无法使用"
        }
    }

    // ═══════════════════════ 设备 ═══════════════════════

    private fun listDevices() {
        deviceArea.removeAllViews()
        val devices = pairedDevices().sortedByDescending { it.name?.startsWith("Qring") == true }
        if (devices.isNotEmpty()) {
            addDeviceGroupLabel("已配对设备")
            for (dev in devices) {
                deviceArea.addView(deviceItem(dev))
            }
        }
        val nearby = discoveredDevices
            .filter { it.name?.startsWith("Qring") == true || it.name?.startsWith("QRING") == true }
            .sortedBy { it.name }
        if (nearby.isNotEmpty()) {
            addDeviceGroupLabel("扫描到的打印机（BLE 直接连接）")
            for (dev in nearby) {
                deviceArea.addView(deviceItem(dev))
            }
        }
        if (devices.isEmpty() && nearby.isEmpty()) {
            val empty = Design.caption("🔍 未发现打印机，请确认打印机已开机…")
            empty.setPadding(0, Design.dp(8), 0, Design.dp(4))
            deviceArea.addView(empty)
        }
    }

    private fun addDeviceGroupLabel(text: String) {
        val label = Design.caption(text)
        label.setPadding(0, Design.dp(6), 0, Design.dp(2))
        deviceArea.addView(label)
    }

    /** 设备列表项：名称主显示，地址小字灰 */
    private fun deviceItem(dev: BluetoothDevice): LinearLayout {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Design.dp(12), Design.dp(8), Design.dp(12), Design.dp(8))
            background = Design.pressable(
                Design.rounded(Design.CARD, Design.RADIUS_SM, Design.DIVIDER, 1),
                Design.rounded(Design.PRIMARY_LIGHT, Design.RADIUS_SM, Design.PRIMARY, 1),
            )
            isClickable = true
            setOnClickListener { connectDevice(dev) }
        }
        item.addView(TextView(this).apply {
            text = "🖨  ${dev.name ?: "未知设备"}"
            textSize = 14f
            setTextColor(Design.TEXT)
            typeface = Typeface.DEFAULT_BOLD
        })
        item.addView(TextView(this).apply {
            text = dev.address
            textSize = 10f
            setTextColor(0xFFB4BACB.toInt())
        })
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Design.dp(6)
        item.layoutParams = lp
        return item
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName
            if (name != null && (name.startsWith("Qring") || name.startsWith("QRING"))) {
                discoveredDevices.add(result.device)
                listDevices()
            }
        }
    }

    private fun startBleScan() {
        val scanner = BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner ?: return
        if (discoveredDevices.isNotEmpty()) return
        runCatching { scanner.startScan(scanCallback) }
    }

    private fun stopBleScan() {
        val scanner = BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner ?: return
        runCatching { scanner.stopScan(scanCallback) }
    }

    /**
     * 机器状态灯刷新（仿 QrintPrint-Windows StatusLights）：
     * 读轮询缓存 lastStatus / batteryPercent，五灯着色：
     * 绿=正常 红=异常 灰=未知 蓝=打印中；电量<15% 红灯。
     */
    private fun updateMachineStatus() {
        if (!printer.connected) return
        val st = printer.lastStatus
        val battery = printer.batteryPercent
        fun setLight(key: String, color: Int) {
            statusDots[key]?.background = Design.rounded(color, Design.dp(12).toFloat())
        }
        val green = 0xFF3FAE4F.toInt()
        val red = 0xFFE04A3A.toInt()
        val gray = 0xFFB4BACB.toInt()
        val blue = 0xFF2196F3.toInt()
        setLight("paper", st?.let { if (it.noPaper) red else green } ?: gray)
        setLight("cover", st?.let { if (it.coverOpen) red else green } ?: gray)
        setLight("thermal", st?.let { if (it.overheat) red else green } ?: gray)
        setLight("printing", if (st?.printing == true) blue else gray)
        if (battery != null) {
            setLight("battery", if (battery < 15) red else green)
            statusDotLabels["battery"]?.text = "电量 $battery%"
        } else {
            setLight("battery", gray)
            statusDotLabels["battery"]?.text = "电量"
        }
    }

    /** 启动状态灯轮询（10s，与 BlePrinterConnection 轮询同周期） */
    private fun startStatusRefresh() {
        statusRefreshJob?.cancel()
        statusRefreshJob = scope.launch {
            while (isActive) {
                delay(10_000)
                updateMachineStatus()
            }
        }
    }

    private fun connectDevice(dev: BluetoothDevice) {
        val name = dev.name ?: "打印机"
        statusText.text = "⏳ 正在连接 $name ..."
        statusText.setTextColor(Design.TEXT_SUB)
        statusBadge.text = "● 连接中"
        scope.launch {
            val ok = printer.connect(dev)
            if (ok) {
                statusBadge.text = "● 已连接"
                statusBadge.setTextColor(Color.WHITE)
                statusBadge.background = Design.rounded(Design.OK, Design.RADIUS_SM)
                statusText.text = buildString {
                    append("✅ 已连接 $name\n")
                    append("型号 ${printer.deviceModel.ifEmpty { "?" }} · 固件 ${printer.firmwareVersion.ifEmpty { "?" }}")
                    printer.batteryPercent?.let { append(" · 电量 $it%") }
                    if (printer.btVersion.isNotEmpty()) append("\n蓝牙版本 ${printer.btVersion}")
                }
                statusText.setTextColor(Design.TEXT)
                updateMachineStatus()
                startStatusRefresh()
            } else {
                statusBadge.text = "● 未连接"
                statusBadge.setTextColor(0xFFD8E2FF.toInt())
                statusBadge.background = Design.rounded(0x33FFFFFF, Design.RADIUS_SM)
                statusText.text = "❌ 连接失败，请确认打印机已开机并处于配对状态"
                statusText.setTextColor(Design.ERROR)
            }
            switchPage(PAGE_HOME)
        }
    }

    // ═══════════════════════ 编码 / 打印 ═══════════════════════

    private fun encodeTextWithSettings(text: String): RasterData {
        val size = (fontGroup.checkedRadioButtonId.takeIf { it != -1 }
            ?.let { fontGroup.findViewById<RadioButton>(it)?.tag as? Int }) ?: 48
        val align = (alignGroup.checkedRadioButtonId.takeIf { it != -1 }
            ?.let { alignGroup.findViewById<RadioButton>(it)?.tag as? Int }) ?: 0
        return RasterEncoder.encodeText(text, fontSizePx = size, bold = boldCheck.isChecked, align = align)
    }

    /** 图片类光栅 → 预览（行合并 + verticalScale=2 模拟 m=2，与实物一致） */
    private fun imagePreviewRaster(raster: RasterData): Bitmap {
        val half = RasterEncoder.halveRows(raster)
        return RasterEncoder.rasterToPreviewBitmap(half, verticalScale = 2)
    }

    /** 图片预处理：裁白边 + 消除笔，再拼接 + 抖动/增强 */
    private fun encodeSelectedImages(images: List<Bitmap>): RasterData {
        val layout = (layoutGroup.checkedRadioButtonId.takeIf { it != -1 }
            ?.let { layoutGroup.findViewById<RadioButton>(it)?.tag as? Int }) ?: 1
        val inkMode = (inkGroup.checkedRadioButtonId.takeIf { it != -1 }
            ?.let { inkGroup.findViewById<RadioButton>(it)?.tag as? InkRemoveMode })
            ?: InkRemoveMode.NONE
        val trimmed = if (trimCheck.isChecked) images.map { ImageEnhancer.trimWhiteEdges(it) } else images
        val cleaned = if (inkMode != InkRemoveMode.NONE) trimmed.map { ImageEnhancer.removeInk(it, inkMode) } else trimmed
        val composed = if (cleaned.size > 1) RasterEncoder.composeImages(cleaned, layout) else cleaned[0]
        return if (enhanceCheck.isChecked) {
            ImageEnhancer.enhanceToRaster(composed)
        } else {
            val mode = (modeGroup.checkedRadioButtonId.takeIf { it != -1 }
                ?.let { modeGroup.findViewById<RadioButton>(it)?.tag as? DitherMode })
                ?: DitherMode.NONE
            RasterEncoder.encode(composed, mode)
        }
    }

    // ── 预览 ──

    // ── 选项变更自动刷新预览（2026-08-11：预览后再改模式必须能刷）──

    /** 文字页：有内容即重渲（改字号/加粗时，无论是否预览过） */
    private fun autoRefreshTextPreview() {
        if (input.text.toString().isNotBlank()) {
            renderTextPreview()
        }
    }

    /** 图片页：有图即重渲（改布局/抖动/消除笔时，无论是否预览过） */
    private fun autoRefreshImagePreview() {
        if (selectedImages.isNotEmpty()) {
            renderImagePreview()
        }
    }

    /** 错题卡页：内容有效即重渲（选题目图/填文本后改布局/抖动等选项时，无论是否预览过） */
    private fun autoRefreshCardPreview() {
        if (reasonInput.text.toString().isNotBlank() ||
            knowledgeInput.text.toString().isNotBlank() ||
            selectedImages.isNotEmpty()
        ) {
            renderCardPreview()
        }
    }

    /** 文字页预览（try-catch 与其他 render 一致，2026-08-11 补） */
    private fun renderTextPreview() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) {
            textStatus.text = "请先输入文字"
            return
        }
        try {
            val raster = encodeTextWithSettings(text)
            val bmp = RasterEncoder.rasterToPreviewBitmap(raster)
            textPreview.setImageBitmap(bmp)
            textStatus.text = "预览：${raster.widthBytes * 8}×${raster.height} 点，约 ${"%.0f".format(raster.height / 8.0)}mm 高"
        } catch (e: Exception) {
            textStatus.setTextColor(Design.ERROR)
            textStatus.text = "预览生成失败：${e.javaClass.simpleName} ${e.message}"
        }
    }

    /** 图片页预览 */
    private fun renderImagePreview() {
        if (selectedImages.isEmpty()) {
            imageStatus.text = "请先选择图片"
            return
        }
        try {
            val raster = encodeSelectedImages(selectedImages)
            val bmp = imagePreviewRaster(raster)
            imagePreview.setImageBitmap(bmp)
            imageStatus.setTextColor(Design.TEXT)
            imageStatus.text = "预览：${raster.widthBytes * 8}×${raster.height} 点，约 ${"%.0f".format(raster.height / 8.0)}mm 高"
        } catch (e: Exception) {
            imageStatus.setTextColor(Design.ERROR)
            imageStatus.text = "预览生成失败：${e.javaClass.simpleName} ${e.message}"
        }
    }

    /** 错题卡预览（独立预览按钮，满意再打） */
    private fun renderCardPreview() {
        val reason = reasonInput.text.toString().trim()
        val knowledge = knowledgeInput.text.toString().trim()
        if (reason.isEmpty() && knowledge.isEmpty() && selectedImages.isEmpty()) {
            cardStatus.text = "请填写错因/知识点，或先选择题目图片"
            return
        }
        try {
            // 与打印共用 cardRaster 管线：题目图预处理+拼接 + 所选抖动模式
            val raster = cardRaster(reason, knowledge)
            val bmp = imagePreviewRaster(raster)
            cardPreview.setImageBitmap(bmp)
            cardStatus.text = "预览已生成（${raster.widthBytes * 8}×${raster.height} 点），满意点下方打印"
        } catch (e: Exception) {
            cardStatus.setTextColor(Design.ERROR)
            cardStatus.text = "预览生成失败：${e.javaClass.simpleName} ${e.message}"
        }
    }

    // ── 打印确认 ──

    /** 打印前确认对话框：显示实际打印效果，确认才打印，取消零耗纸 */
    private fun previewConfirmDialog(title: String, previewBmp: Bitmap, onConfirm: () -> Unit) {
        val img = ImageView(this).apply {
            setImageBitmap(previewBmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            maxHeight = Design.dp(440)
            setPadding(Design.dp(12), Design.dp(8), Design.dp(12), Design.dp(8))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("这是实际打印效果，满意再打")
            .setView(img)
            .setPositiveButton("🖨 确认打印", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
    }

    /**
     * 确认后执行打印（preflight 现查 + printRaster + 状态显示 + 历史记录）。
     * @param historyType 历史类型（文字/图片/错题卡/模板/条码/自检页）
     * @param historyTitle 历史标题（重打列表显示）
     */
    private fun doPrintConfirmed(
        raster: RasterData,
        mode: Int,
        halveRows: Boolean,
        okMessage: String,
        statusView: TextView,
        historyType: String,
        historyTitle: String,
    ) {
        scope.launch {
            try {
                val fault = printer.preflightCheck()
                if (fault != null) {
                    statusView.text = "打印被拦截：$fault"
                    statusView.setTextColor(Design.ERROR)
                    return@launch
                }
                val r = printer.printRaster(
                    raster,
                    thickness = Settings.thickness,
                    mode = mode,
                    halveRows = halveRows,
                    feedBefore = Settings.feedBefore,
                    feedAfter = Settings.feedAfter,
                )
                if (r.ok) {
                    statusView.setTextColor(Design.OK)
                    statusView.text = "✅ $okMessage"
                    // 打印成功 → 记历史（无损光栅 + 缩略图）
                    runCatching {
                        val preview = RasterEncoder.rasterToPreviewBitmap(
                            if (halveRows) RasterEncoder.halveRows(raster) else raster,
                            verticalScale = if (halveRows) 2 else 1,
                        )
                        HistoryStore.add(historyType, historyTitle, raster, preview)
                    }
                } else {
                    statusView.setTextColor(Design.ERROR)
                    statusView.text = "❌ ${okMessage}失败：${r.message}"
                }
            } catch (e: Exception) {
                PrintLog.event("打印异常: ${e.javaClass.simpleName}: ${e.message}")
                statusView.setTextColor(Design.ERROR)
                statusView.text = "打印异常：${e.javaClass.simpleName} ${e.message}"
            }
        }
    }

    // ── 各功能打印入口 ──

    /** 文字打印：自动预览 → 确认 → 打印 */
    private fun doPrintText() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) {
            textStatus.text = "请先输入文字"
            return
        }
        textStatus.text = "正在生成预览 ..."
        scope.launch {
            try {
                val raster = encodeTextWithSettings(text)
                val bmp = RasterEncoder.rasterToPreviewBitmap(raster)
                textPreview.setImageBitmap(bmp)
                val desc = "${raster.widthBytes * 8}×${raster.height} 点，约 ${"%.0f".format(raster.height / 8.0)}mm 高"
                previewConfirmDialog("确认打印文字（$desc）", bmp) {
                    doPrintConfirmed(raster, mode = 0, halveRows = false, okMessage = "文字打印完成",
                        statusView = textStatus, historyType = "文字", historyTitle = text.take(20))
                }
            } catch (e: Exception) {
                PrintLog.event("文字打印异常: ${e.javaClass.simpleName}: ${e.message}")
                textStatus.setTextColor(Design.ERROR)
                textStatus.text = "预览生成失败：${e.javaClass.simpleName} ${e.message}"
            }
        }
    }

    /** 图片打印：自动预览 → 确认 → 打印 */
    private fun doPrintImage() {
        if (selectedImages.isEmpty()) {
            imageStatus.text = "请先选择图片"
            return
        }
        imageStatus.text = "正在生成预览 ..."
        scope.launch {
            try {
                val raster = encodeSelectedImages(selectedImages)
                val bmp = imagePreviewRaster(raster)
                imagePreview.setImageBitmap(bmp)
                val desc = "${raster.widthBytes * 8}×${raster.height} 点，约 ${"%.0f".format(raster.height / 8.0)}mm 高"
                previewConfirmDialog("确认打印图片（$desc）", bmp) {
                    doPrintConfirmed(raster, mode = 2, halveRows = true, okMessage = "图片打印完成",
                        statusView = imageStatus, historyType = "图片", historyTitle = "图片 ${selectedImages.size} 张")
                }
            } catch (e: Exception) {
                PrintLog.event("图片打印异常: ${e.javaClass.simpleName}: ${e.message}")
                imageStatus.setTextColor(Design.ERROR)
                imageStatus.text = "预览生成失败：${e.javaClass.simpleName} ${e.message}"
            }
        }
    }

    /**
     * 错题卡题目图预处理（与图片页同套选项）：
     * 每张裁白边 → 消除笔 → 按排列布局拼接（多图）→ 可选一键增强
     * （增强输出二值光栅，转回白底黑字 Bitmap 嵌入卡片）。
     */
    private fun preprocessProblemImages(images: List<Bitmap>): Bitmap? {
        if (images.isEmpty()) return null
        val ink = (inkGroupCard.checkedRadioButtonId.takeIf { it != -1 }
            ?.let { inkGroupCard.findViewById<RadioButton>(it)?.tag as? InkRemoveMode })
            ?: InkRemoveMode.NONE
        val trimmed = if (trimCheckCard.isChecked) images.map { ImageEnhancer.trimWhiteEdges(it) } else images
        val cleaned = if (ink != InkRemoveMode.NONE) trimmed.map { ImageEnhancer.removeInk(it, ink) } else trimmed
        val layout = (layoutGroupCard.checkedRadioButtonId.takeIf { it != -1 }
            ?.let { layoutGroupCard.findViewById<RadioButton>(it)?.tag as? Int }) ?: 1
        val composed = if (cleaned.size > 1) RasterEncoder.composeImages(cleaned, layout) else cleaned[0]
        return if (enhanceCheckCard.isChecked) {
            RasterEncoder.rasterToPreviewBitmap(ImageEnhancer.enhanceToRaster(composed))
        } else {
            composed
        }
    }

    /**
     * 错题卡光栅（预览/打印共用同一管线）：
     * 题目图预处理+拼接 → 模板合成 → 按所选抖动模式编码（NONE 用图片阈值 128）。
     */
    private fun cardRaster(reason: String, knowledge: String): RasterData {
        val problem = preprocessProblemImages(selectedImages)
        val card = TemplateBuilder.build(reason, knowledge, problem)
        val dither = (modeGroupCard.checkedRadioButtonId.takeIf { it != -1 }
            ?.let { modeGroupCard.findViewById<RadioButton>(it)?.tag as? DitherMode })
            ?: DitherMode.FLOYD_STEINBERG
        return if (dither == DitherMode.NONE) {
            RasterEncoder.encode(card, DitherMode.NONE, RasterEncoder.THRESHOLD_IMAGE)
        } else {
            RasterEncoder.encode(card, dither)
        }
    }

    /** 错题卡：生成 → 预览 → 确认 → 打印 */
    private fun printTemplateCard() {
        val reason = reasonInput.text.toString().trim()
        val knowledge = knowledgeInput.text.toString().trim()
        if (reason.isEmpty() && knowledge.isEmpty() && selectedImages.isEmpty()) {
            cardStatus.text = "请填写错因/知识点，或先选择题目图片"
            return
        }
        cardStatus.text = "正在生成错题卡预览 ..."
        scope.launch {
            try {
                // 与预览共用 cardRaster 管线（题目图预处理+拼接 + 抖动模式）
                val raster = cardRaster(reason, knowledge)
                val bmp = imagePreviewRaster(raster)
                cardPreview.setImageBitmap(bmp)
                previewConfirmDialog("确认打印错题卡", bmp) {
                    doPrintConfirmed(raster, mode = 2, halveRows = true, okMessage = "错题卡打印完成",
                        statusView = cardStatus, historyType = "错题卡", historyTitle = reason.ifEmpty { knowledge })
                }
            } catch (e: Exception) {
                PrintLog.event("错题卡异常: ${e.javaClass.simpleName}: ${e.message}")
                cardStatus.setTextColor(Design.ERROR)
                cardStatus.text = "错题卡异常：${e.javaClass.simpleName} ${e.message}"
            }
        }
    }

    /** 模板打印：生成 → 预览确认 → 打印（首页宫格入口） */
    private fun printTemplate(gen: () -> Bitmap) {
        imageStatus.text = "正在生成模板预览 ..."
        scope.launch {
            try {
                val page = gen()
                val raster = RasterEncoder.encode(page, DitherMode.NONE, RasterEncoder.THRESHOLD_TEXT)
                val bmp = imagePreviewRaster(raster)
                previewConfirmDialog("确认打印模板", bmp) {
                    doPrintConfirmed(raster, mode = 2, halveRows = true, okMessage = "模板打印完成",
                        statusView = imageStatus, historyType = "模板", historyTitle = "模板")
                }
            } catch (e: Exception) {
                PrintLog.event("模板异常: ${e.javaClass.simpleName}: ${e.message}")
                imageStatus.setTextColor(Design.ERROR)
                imageStatus.text = "模板异常：${e.javaClass.simpleName} ${e.message}"
            }
        }
    }

    /** 自检页（藏在我的页）：Floyd 抖动渲染灰阶渐变 */
    private fun printSelfTest() {
        if (selectedImages.isNotEmpty()) {
            cardStatus.text = "请先清除题目图片再打自检页"
            return
        }
        cardStatus.text = "正在生成自检页预览 ..."
        scope.launch {
            try {
                val page = SelfTest.build()
                val raster = RasterEncoder.encode(page, DitherMode.FLOYD_STEINBERG)
                val bmp = imagePreviewRaster(raster)
                previewConfirmDialog("确认打印自检页", bmp) {
                    doPrintConfirmed(raster, mode = 2, halveRows = true, okMessage = "自检页打印完成",
                        statusView = cardStatus, historyType = "自检页", historyTitle = "打印测试页")
                }
            } catch (e: Exception) {
                PrintLog.event("自检页异常: ${e.javaClass.simpleName}: ${e.message}")
                cardStatus.setTextColor(Design.ERROR)
                cardStatus.text = "自检页异常：${e.javaClass.simpleName} ${e.message}"
            }
        }
    }
}
