package com.qring.print

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
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
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var subTabOther: RadioButton
    private lateinit var subTabBarcode: RadioButton
    private lateinit var subTabDoc: RadioButton
    // 画布区（自定义元素排版 2026-08-12，合成自 bzhou830/snowboys/lztttt；
    // 2026-08-12 晚并入图片页：Dialog 入口，结果加入图片通道，不再占顶部 Tab）
    private lateinit var canvasLayout: CanvasLayout
    private lateinit var canvasEditorCard: LinearLayout
    private lateinit var canvasEditorTitle: TextView
    private lateinit var canvasStatus: TextView
    /** 元素排版 Dialog（加入图片后关闭） */
    private var layoutDialog: Dialog? = null
    private lateinit var canvasEditText: EditText
    private lateinit var canvasFontBar: SeekBar
    private lateinit var canvasBoldCheck: CheckBox
    private lateinit var canvasBarcodeTypeGroup: RadioGroup
    private lateinit var canvasBarcodeInput: EditText
    private lateinit var canvasBarcodeHint: TextView
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
    // 文档区（PDF / Word / Excel，2026-08-11 加）
    private lateinit var docPreview: ImageView
    private lateinit var docStatus: TextView
    private var currentDocUri: Uri? = null
    private var currentDocTitle: String = ""
    private var currentDocRaster: RasterData? = null
    private var currentDocMode = 0
    /** 文档解析协程引用：新解析先取消旧的，防止旧协程结果覆盖新文件（2026-08-11 大文件竞态修复） */
    private var docParseJob: kotlinx.coroutines.Job? = null
    private lateinit var modeGroup: RadioGroup
    private lateinit var inkGroup: RadioGroup
    private lateinit var trimCheck: CheckBox
    private lateinit var enhanceCheck: CheckBox
    private lateinit var layoutGroup: RadioGroup
    // 阈值滑块（黑白化阶段，独立于打印浓度）
    private lateinit var thresholdBar: SeekBar
    private lateinit var thresholdValue: TextView
    // 描边（xyprt 移植 2026-08-11）
    private lateinit var outlineCheck: CheckBox
    private lateinit var outlineOptions: LinearLayout
    private lateinit var outlineMethodGroup: RadioGroup
    private lateinit var outlineThicknessGroup: RadioGroup
    private lateinit var outlineSmoothCheck: CheckBox
    private lateinit var outlineInvertCheck: CheckBox
    private lateinit var outlineSensitivityBar: SeekBar
    private lateinit var outlineSensitivityValue: TextView
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
    /** 其它页（模板 + 错题卡，2026-08-12 合并） */
    private lateinit var otherContent: LinearLayout
    private lateinit var barcodeContent: LinearLayout
    private lateinit var docContent: LinearLayout
    private lateinit var tabHome: LinearLayout
    private lateinit var tabPrint: LinearLayout
    private lateinit var tabMine: LinearLayout

    private val selectedImages = mutableListOf<Bitmap>()
    /** 扫描发现的未配对 Qring 设备（引导配对用） */
    private val discoveredDevices = mutableSetOf<BluetoothDevice>()

    private val REQ_BT = 1001
    private val REQ_IMAGE = 1002
    private val REQ_DOC = 1003
    private val REQ_CANVAS_IMAGE = 1004

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
                // 描边（xyprt 移植 2026-08-11）：Canny/Lines + 灵敏度/线宽/反白/平滑对照
                save("outline_canny.png", imagePreviewRaster(RasterEncoder.encodeOutline(testPhoto, OutlineMethod.CANNY, 88, 1)))
                save("outline_canny_s30.png", imagePreviewRaster(RasterEncoder.encodeOutline(testPhoto, OutlineMethod.CANNY, 30, 1)))
                save("outline_lines.png", imagePreviewRaster(RasterEncoder.encodeOutline(testPhoto, OutlineMethod.LINES, 88, 1)))
                save("outline_thick3.png", imagePreviewRaster(RasterEncoder.encodeOutline(testPhoto, OutlineMethod.CANNY, 88, 3)))
                save("outline_invert.png", imagePreviewRaster(RasterEncoder.encodeOutline(testPhoto, OutlineMethod.CANNY, 88, 1, invert = true)))
                save("outline_smooth.png", imagePreviewRaster(RasterEncoder.encodeOutline(testPhoto, OutlineMethod.CANNY, 88, 1, smooth = true)))
                // PDF 参数对照（阈值 190 + 对比度 10，xyprt PDF 默认）
                save("contrast_pdf.png", imagePreviewRaster(RasterEncoder.encode(testPhoto, DitherMode.NONE, 190, contrast = 10)))
                // 口算题（2026-08-11 加）：混合类型 12 题渲染验证
                save("math_mix.png", imagePreviewRaster(RasterEncoder.encode(MathWorksheet.build(MathWorksheet.Op.MIX, 12), DitherMode.NONE, RasterEncoder.THRESHOLD_TEXT)))
                // 文档（2026-08-11 加）：合成 PDF/docx/xlsx 走完整提取/渲染链路
                runCatching {
                    val pdfFile = java.io.File(filesDir, "test_selftest.pdf")
                    val doc = android.graphics.pdf.PdfDocument()
                    repeat(2) { p ->
                        val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, p).create())
                        val c = page.canvas
                        c.drawColor(Color.WHITE)
                        c.drawText("错题小印 PDF 自检 第${p + 1}页", 72f, 100f, android.graphics.Paint().apply { textSize = 24f })
                        c.drawRect(72f, 160f, 400f, 320f, android.graphics.Paint().apply { color = 0xFF2E2E2E.toInt() })
                        doc.finishPage(page)
                    }
                    // PdfDocument 是内存模型，必须 writeTo 才落盘
                    java.io.FileOutputStream(pdfFile).use { doc.writeTo(it) }
                    doc.close()
                    // contentResolver 不吃 file://，自检走 ParcelFileDescriptor 直开
                    val pdfBmp = PdfPrintRenderer.renderFromFile(pdfFile)
                    save("pdf_rendered.png", pdfBmp)
                    save("pdf_printed.png", imagePreviewRaster(RasterEncoder.encode(pdfBmp, DitherMode.NONE, 190, contrast = 10)))
                }.onFailure { PrintLog.event("PDF 自检失败: ${it.javaClass.simpleName}: ${it.message}") }
                runCatching {
                    val docxFile = java.io.File(filesDir, "test_selftest.docx")
                    java.util.zip.ZipOutputStream(docxFile.outputStream()).use { z ->
                        fun entry(name: String, content: String) {
                            z.putNextEntry(java.util.zip.ZipEntry(name))
                            z.write(content.toByteArray(Charsets.UTF_8))
                            z.closeEntry()
                        }
                        entry("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>")
                        entry("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>")
                        entry("word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>Word 自检：第一段</w:t></w:r></w:p><w:p><w:r><w:t xml:space=\"preserve\">第二段 带空格</w:t></w:r></w:p></w:body></w:document>")
                    }
                    val paras = DocxTextExtractor.extract(this@MainActivity, Uri.fromFile(docxFile))
                    save("docx_parsed.png", RasterEncoder.rasterToPreviewBitmap(RasterEncoder.encodeText(paras.joinToString("\n"))))
                }.onFailure { PrintLog.event("docx 自检失败: ${it.javaClass.simpleName}: ${it.message}") }
                runCatching {
                    val xlsxFile = java.io.File(filesDir, "test_selftest.xlsx")
                    java.util.zip.ZipOutputStream(xlsxFile.outputStream()).use { z ->
                        fun entry(name: String, content: String) {
                            z.putNextEntry(java.util.zip.ZipEntry(name))
                            z.write(content.toByteArray(Charsets.UTF_8))
                            z.closeEntry()
                        }
                        entry("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/></Types>")
                        entry("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
                        entry("xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
                        entry("xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/></Relationships>")
                        entry("xl/sharedStrings.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"3\" uniqueCount=\"3\"><si><t>科目</t></si><si><t>成绩</t></si><si><t>数学</t></si></sst>")
                        entry("xl/worksheets/sheet1.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row><row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"B2\"><v>95</v></c></row></sheetData></worksheet>")
                    }
                    val lines = XlsxTextExtractor.extract(this@MainActivity, Uri.fromFile(xlsxFile))
                    save("xlsx_parsed.png", RasterEncoder.rasterToPreviewBitmap(RasterEncoder.encodeText(lines.joinToString("\n"))))
                }.onFailure { PrintLog.event("xlsx 自检失败: ${it.javaClass.simpleName}: ${it.message}") }

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
                // 描边 UI 快照（勾选态）
                outlineCheck.isChecked = true
                snapshot(printPage, "page_print_image_outline.png")
                outlineCheck.isChecked = false
                subTabOther.isChecked = true
                snapshot(printPage, "page_print_card.png")
                subTabBarcode.isChecked = true
                snapshot(printPage, "page_print_barcode.png")
                subTabText.isChecked = true
                switchPage(PAGE_MINE)
                // 快照前清空设备列表：截图用于开源仓库 README，不能含真实设备名/MAC（个人信息）
                deviceArea.removeAllViews()
                val placeholder = Design.caption("🔒 设备列表已隐藏（截图用）")
                placeholder.setPadding(0, Design.dp(8), 0, Design.dp(4))
                deviceArea.addView(placeholder)
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
        fun tabItem(iconName: String, label: String): LinearLayout {
            val t = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, Design.dp(4), 0, Design.dp(4))
                isClickable = true
            }
            // AI 生成统一风格位图图标（24dp）
            t.addView(Design.Icons.imageView(iconName, 24))
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
        tabHome = tabItem("home", "首页")
        tabPrint = tabItem("print", "打印")
        tabMine = tabItem("person", "我的")
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
                        background = Design.rounded(Design.TEXT_SUB, Design.dp(12).toFloat())
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

        // 功能宫格：2 列 × 4 行，AI 生成统一风格位图图标（2026-08-11 用户要求）
        page.addView(Design.sectionTitle("常用功能"))
        data class GridEntry(val icon: String, val label: String, val action: () -> Unit)
        val grid = arrayOf(
            GridEntry("text", "文字打印", { switchPage(PAGE_PRINT); subTabText.isChecked = true; input.requestFocus() }),
            GridEntry("image", "图片打印", { switchPage(PAGE_PRINT); subTabImage.isChecked = true }),
            GridEntry("barcode", "条码打印", { switchPage(PAGE_PRINT); subTabBarcode.isChecked = true }),
            GridEntry("doc", "文档打印", { switchPage(PAGE_PRINT); subTabDoc.isChecked = true }),
            GridEntry("template", "其它打印", { switchPage(PAGE_PRINT); subTabOther.isChecked = true }),
            GridEntry("history", "打印历史", { startActivity(Intent(this@MainActivity, HistoryActivity::class.java)) }),
        )
        for (i in grid.indices step 2) {
            val g1 = grid[i]
            val g2 = grid.getOrNull(i + 1) // 奇数个时最后一行只有一格，右侧占位保持左对齐
            page.addView(Design.row {
                addView(gridItem(g1.icon, g1.label, g1.action),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (g2 != null) {
                    addView(gridItem(g2.icon, g2.label, g2.action),
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginStart = Design.dp(10)
                        })
                } else {
                    addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(10)
            })
        }

        page.addView(Design.card {
            addView(Design.sectionTitle("使用提示"))
            addView(Design.caption("1. 首次使用先连接打印机（我的 → 选择设备）\n2. 打印页顶部切换 文字/图片/错题卡/条码/文档\n3. 拍试卷推荐图片页的一键增强；文档支持 PDF/Word/Excel\n4. 所有打印先预览，确认效果再打防废纸"))
        })
        return scroll
    }

    /** 宫格项：AI 生成统一风格图标（白底彩图）+ 文字居中在图标正下方 */
    private fun gridItem(iconName: String, label: String, action: () -> Unit): LinearLayout {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(Design.dp(8), Design.dp(16), Design.dp(8), Design.dp(14))
            background = Design.pressable(
                Design.rounded(Design.SURFACE_CONTAINER_LOW, Design.RADIUS_SM),
                Design.rounded(Design.PRIMARY_CONTAINER, Design.RADIUS_SM),
            )
            isClickable = true
            setOnClickListener { action() }
        }
        // 白底圆角容器 + 位图图标（60dp，统一风格）
        val iconWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(Design.dp(10), Design.dp(10), Design.dp(10), Design.dp(10))
            background = Design.rounded(0xFFFFFFFF.toInt(), Design.dp(18).toFloat())
        }
        iconWrap.addView(Design.Icons.imageView(iconName, 64))
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
            background = Design.rounded(Design.SURFACE_CONTAINER, Design.RADIUS_SM)
        }
        fun subTab(text: String, iconName: String): RadioButton = RadioButton(this).apply {
            this.text = text
            textSize = 13.5f
            gravity = Gravity.CENTER
            isAllCaps = false
            minHeight = Design.dp(40)
            setButtonDrawable(android.R.color.transparent)
            id = View.generateViewId()
            // 左图标（AI 统一风格位图，缩放到 20dp 显示——原图 160px 直接塞会撑高按钮导致图标高低不齐）
            Design.Icons.bitmap(iconName)?.let { bmp ->
                val sizePx = Design.dp(20)
                val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, sizePx, sizePx, true)
                setCompoundDrawablesWithIntrinsicBounds(
                    android.graphics.drawable.BitmapDrawable(resources, scaled), null, null, null)
                compoundDrawablePadding = Design.dp(4)
            }
            // checked 态持续高亮（浅绿底），未选容器色——用户能明确当前所处功能页
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_checked), Design.rounded(Design.PRIMARY_CONTAINER, Design.RADIUS_SM))
                addState(intArrayOf(android.R.attr.state_pressed), Design.rounded(Design.PRIMARY_CONTAINER, Design.RADIUS_SM))
                addState(intArrayOf(), Design.rounded(Design.SURFACE_CONTAINER, Design.RADIUS_SM))
            }
        }
        subTabText = subTab("文字", "text")
        subTabImage = subTab("图片", "image")
        subTabBarcode = subTab("条码", "barcode")
        subTabDoc = subTab("文档", "doc")
        subTabOther = subTab("其它", "template")
        subGroup.addView(subTabText, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
        subGroup.addView(subTabImage, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
        subGroup.addView(subTabBarcode, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
        subGroup.addView(subTabDoc, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
        subGroup.addView(subTabOther, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
        subGroup.setOnCheckedChangeListener { _, checkedId ->
            // 切页：只显示当前功能内容，互不掺和
            textContent.visibility = if (checkedId == subTabText.id) View.VISIBLE else View.GONE
            imageContent.visibility = if (checkedId == subTabImage.id) View.VISIBLE else View.GONE
            barcodeContent.visibility = if (checkedId == subTabBarcode.id) View.VISIBLE else View.GONE
            docContent.visibility = if (checkedId == subTabDoc.id) View.VISIBLE else View.GONE
            otherContent.visibility = if (checkedId == subTabOther.id) View.VISIBLE else View.GONE
            // 着色
            listOf(subTabText, subTabImage, subTabBarcode, subTabDoc, subTabOther).forEach {
                it.setTextColor(if (it.id == checkedId) Design.PRIMARY else Design.TEXT_SUB)
            }
        }
        page.addView(subGroup)

        // 五个功能内容块（独立构建，visibility 切换；其它 = 模板 + 错题卡）
        textContent = buildTextContent()
        imageContent = buildImageContent()
        barcodeContent = buildBarcodeContent()
        docContent = buildDocContent()
        otherContent = buildOtherContent()
        page.addView(textContent)
        page.addView(imageContent)
        page.addView(barcodeContent)
        page.addView(docContent)
        page.addView(otherContent)
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
                val pickBtn = Design.outlineButton("🖼 选图")
                val canvasBtn = Design.outlineButton("🖌 涂鸦")
                val layoutBtn = Design.outlineButton("📐 排版")
                val clearBtn = Design.ghostButton("清除")
                addView(pickBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(canvasBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = Design.dp(6)
                })
                addView(layoutBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = Design.dp(6)
                })
                addView(clearBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = Design.dp(6)
                })
                pickBtn.setOnClickListener {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "image/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    startActivityForResult(intent, REQ_IMAGE)
                }
                // 画布涂鸦（2026-08-11 借鉴 lztttt，并入图片通道）
                canvasBtn.setOnClickListener { showCanvasDialog() }
                // 元素排版（2026-08-12 合成，Dialog 并入图片通道）
                layoutBtn.setOnClickListener { showLayoutDialog() }
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
            // 二值化阈值（2026-08-11 加，xyprt 借鉴）：黑白化阶段调"哪些像素算黑"，
            // 与打印浓度（"黑得多黑"）独立叠加调节。仅"无(锐利)"模式生效。
            addView(Design.row {
                addView(Design.label("二值化阈值"))
                thresholdValue = Design.label("${Settings.threshold}")
                addView(thresholdValue, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    gravity = Gravity.END
                })
            })
            thresholdBar = SeekBar(this@MainActivity).apply {
                max = 255
                progress = Settings.threshold
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        thresholdValue.text = "$progress"
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {
                        Settings.threshold = sb.progress
                        autoRefreshImagePreview()
                    }
                })
            }
            addView(thresholdBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(2)
            })
            // 描边模式（xyprt 移植 2026-08-11）：独立管线，不经过灰度/对比度；
            // 勾选时置灰冲突选项（抖动/消除笔/裁边/增强/阈值）
            outlineCheck = Design.check("✏️ 描边模式（Canny 线稿 / Lines 描边）")
            outlineCheck.setOnCheckedChangeListener { _, checked ->
                val disabled = listOf(modeGroup, inkGroup, trimCheck, enhanceCheck, thresholdBar)
                disabled.forEach { it.isEnabled = !checked }
                outlineOptions.visibility = if (checked) View.VISIBLE else View.GONE
                autoRefreshImagePreview()
            }
            addView(outlineCheck, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(4)
            })
            outlineOptions = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            outlineOptions.addView(Design.label("描边算法"))
            outlineMethodGroup = Design.segmentGroup(
                OutlineMethod.entries.map { it.label to it },
                defaultIndex = OutlineMethod.entries.indexOf(Settings.outlineMethod),
            ) { autoRefreshImagePreview() }
            outlineOptions.addView(outlineMethodGroup)
            outlineOptions.addView(Design.row {
                addView(Design.label("灵敏度（越大边缘越多）"))
                outlineSensitivityValue = Design.label("${Settings.outlineSensitivity}")
                addView(outlineSensitivityValue, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    gravity = Gravity.END
                })
            })
            outlineSensitivityBar = SeekBar(this@MainActivity).apply {
                max = 100
                progress = Settings.outlineSensitivity
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        outlineSensitivityValue.text = "$progress"
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {
                        Settings.outlineSensitivity = sb.progress
                        autoRefreshImagePreview()
                    }
                })
            }
            outlineOptions.addView(outlineSensitivityBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(2)
            })
            outlineOptions.addView(Design.label("线宽"))
            outlineThicknessGroup = Design.segmentGroup(
                listOf("细" to 1, "中" to 2, "粗" to 3),
                defaultIndex = (Settings.outlineThickness - 1).coerceIn(0, 2),
            ) { autoRefreshImagePreview() }
            outlineOptions.addView(outlineThicknessGroup)
            outlineSmoothCheck = Design.check("平滑（去毛刺/小噪点）")
            outlineSmoothCheck.isChecked = Settings.outlineSmooth
            outlineSmoothCheck.setOnCheckedChangeListener { _, checked ->
                Settings.outlineSmooth = checked
                autoRefreshImagePreview()
            }
            outlineOptions.addView(outlineSmoothCheck, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(4)
            })
            outlineInvertCheck = Design.check("反白（黑底白线）")
            outlineInvertCheck.isChecked = Settings.outlineInvert
            outlineInvertCheck.setOnCheckedChangeListener { _, checked ->
                Settings.outlineInvert = checked
                autoRefreshImagePreview()
            }
            outlineOptions.addView(outlineInvertCheck, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(4)
            })
            addView(outlineOptions)
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
    // ── 其它内容块（常用模板 + 错题卡，2026-08-12 合并，替换原 错题卡/课程表/单词表/每日计划/口算题 分散入口）──
    private fun buildOtherContent(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(Design.card {
            addView(Design.sectionTitle("常用模板"))
            addView(Design.caption("一键生成 · 课程表 / 单词表 / 每日计划 / 口算题"))
            // 2×2 图标宫格（与首页「常用功能」同款视觉，2026-08-12 改：文字按钮行太生硬）
            data class Tpl(val icon: String, val label: String, val action: () -> Unit)
            val tpls = arrayOf(
                Tpl("course", "课程表", { printTemplate { TemplateLibrary.courseTable() } }),
                Tpl("word", "单词表", { printTemplate { TemplateLibrary.wordList() } }),
                Tpl("plan", "每日计划", { printTemplate { TemplateLibrary.dailyPlan() } }),
                Tpl("math", "口算题", { showMathDialog() }),
            )
            for (i in tpls.indices step 2) {
                val t1 = tpls[i]
                val t2 = tpls.getOrNull(i + 1)
                addView(Design.row {
                    addView(gridItem(t1.icon, t1.label, t1.action),
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    if (t2 != null) {
                        addView(gridItem(t2.icon, t2.label, t2.action),
                            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                                marginStart = Design.dp(10)
                            })
                    }
                }.also {
                    it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = Design.dp(4)
                    }
                })
            }
        })

        // 错题卡（原独立 Tab，2026-08-12 并入其它页）
        cardContent = buildCardContent()
        col.addView(cardContent)
        return col
    }

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
    // ── 元素排版编辑器（2026-08-12，合成自 bzhou830/snowboys/lztttt；
    //    2026-08-12 晚并入图片页：Dialog 打开，结果加入图片通道打印）──
    private fun buildLayoutEditor(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(Design.card {
            addView(Design.sectionTitle("元素排版"))
            addView(Design.caption("文字 / 图片 / 条码自由排版，拖拽摆放 · 完成后加入图片通道打印"))
            val toolRow = Design.row()
            val addTextBtn = Design.ghostButton("＋ 文字")
            val addImageBtn = Design.ghostButton("＋ 图片")
            val addBarcodeBtn = Design.ghostButton("＋ 条码")
            toolRow.addView(addTextBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            toolRow.addView(addImageBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Design.dp(8)
            })
            toolRow.addView(addBarcodeBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Design.dp(8)
            })
            val clearBtn = Design.ghostButton("清空")
            toolRow.addView(clearBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Design.dp(8)
            })
            addView(toolRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(8)
            })

            addTextBtn.setOnClickListener {
                canvasLayout.addElement(CanvasElement(CanvasElement.KIND_TEXT, 0f, 0f, 280f, 48f).apply {
                    text = "双击编辑文字"
                    fontSize = 24f
                })
                refreshCanvasEditor()
            }
            addImageBtn.setOnClickListener {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                }
                startActivityForResult(intent, REQ_CANVAS_IMAGE)
            }
            addBarcodeBtn.setOnClickListener {
                canvasLayout.addElement(CanvasElement(CanvasElement.KIND_BARCODE, 0f, 0f, 200f, 120f).apply {
                    barcodeContent = "https://github.com/kikyang/qring-print-android"
                })
                refreshCanvasEditor()
            }
            clearBtn.setOnClickListener {
                if (canvasLayout.elements.isEmpty()) return@setOnClickListener
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("清空画布")
                    .setMessage("将删除所有元素，确定？")
                    .setPositiveButton("清空") { _, _ -> canvasLayout.clear(); refreshCanvasEditor() }
                    .setNegativeButton("取消", null)
                    .show()
            }
        })

        col.addView(Design.card {
            canvasLayout = CanvasLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    Design.dp(240)
                )
                onSelect = { refreshCanvasEditor() }
            }
            addView(canvasLayout)
            canvasStatus = TextView(this@MainActivity).apply {
                text = "点按选中元素，拖动调整位置"
                textSize = 12f
                setTextColor(Design.TEXT_SUB)
                setPadding(0, Design.dp(6), 0, 0)
            }
            addView(canvasStatus)
        })

        // 选中元素编辑面板（未选中时隐藏）
        canvasEditorCard = Design.card().apply {
            addView(Design.sectionTitle("编辑元素").also { title ->
                canvasEditorTitle = title
            })

            // 文字：内容 / 字号 / 加粗
            canvasEditText = EditText(this@MainActivity).apply {
                hint = "文字内容"
                textSize = 14f
                setTextColor(Design.ON_SURFACE)
                setHintTextColor(Design.TEXT_SUB)
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) syncCanvasTextElement()
                }
            }
            addView(canvasEditText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            val fontRow = Design.row()
            fontRow.addView(TextView(this@MainActivity).apply {
                text = "字号"
                textSize = 13f
                setTextColor(Design.ON_SURFACE)
            })
            canvasFontBar = SeekBar(this@MainActivity).apply {
                max = 48 - 12
                progress = 24 - 12
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                        val el = canvasLayout.selected
                        if (el != null && el.kind == CanvasElement.KIND_TEXT) {
                            el.fontSize = (progress + 12).toFloat()
                            canvasLayout.invalidate()
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }
            fontRow.addView(canvasFontBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Design.dp(8)
            })
            addView(fontRow)
            canvasBoldCheck = Design.check("加粗").apply {
                setOnCheckedChangeListener { _, checked ->
                    val el = canvasLayout.selected
                    if (el != null && el.kind == CanvasElement.KIND_TEXT) {
                        el.bold = checked
                        canvasLayout.invalidate()
                    }
                }
            }
            addView(canvasBoldCheck)

            // 条码：类型 / 内容
            canvasBarcodeTypeGroup = RadioGroup(this@MainActivity).apply {
                orientation = RadioGroup.HORIZONTAL
            }
            for (type in BarcodeGenerator.TYPES) {
                val rb = RadioButton(this@MainActivity).apply {
                    text = type.label
                    textSize = 11f
                    isAllCaps = false
                    setButtonDrawable(android.R.color.transparent)
                    minHeight = Design.dp(30)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) {
                            canvasLayout.selected?.barcodeType = type
                            canvasLayout.invalidate()
                        }
                    }
                }
                canvasBarcodeTypeGroup.addView(rb)
            }
            addView(canvasBarcodeTypeGroup)
            canvasBarcodeInput = EditText(this@MainActivity).apply {
                hint = "条码内容"
                textSize = 14f
                setTextColor(Design.ON_SURFACE)
                setHintTextColor(Design.TEXT_SUB)
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) syncCanvasBarcodeElement()
                }
            }
            addView(canvasBarcodeInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(4)
            })
            canvasBarcodeHint = TextView(this@MainActivity).apply {
                textSize = 11f
                setTextColor(Design.TEXT_SUB)
            }
            addView(canvasBarcodeHint)

            // 通用操作：放大 / 缩小 / 置顶 / 删除
            val opRow = Design.row()
            fun opBtn(label: String, action: () -> Unit): Button =
                Design.ghostButton(label).also { it.setOnClickListener { action() } }
            opRow.addView(opBtn("放大", { canvasLayout.scaleSelected(1.1f) }),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            opRow.addView(opBtn("缩小", { canvasLayout.scaleSelected(0.9f) }),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Design.dp(6) })
            opRow.addView(opBtn("置顶", { canvasLayout.toFront() }),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Design.dp(6) })
            opRow.addView(opBtn("删除", {
                canvasLayout.removeSelected()
                refreshCanvasEditor()
            }),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Design.dp(6) })
            addView(opRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(8)
            })
        }
        canvasEditorCard.visibility = View.GONE
        col.addView(canvasEditorCard)

        col.addView(Design.card {
            val saveBtn = Design.outlineButton("💾 存为模板")
            val loadBtn = Design.outlineButton("📂 加载模板")
            val addBtn = Design.primaryButton("✅ 加入图片（预览确认）")
            val row = Design.row()
            row.addView(saveBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(loadBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Design.dp(8)
            })
            addView(row)
            addView(addBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(8)
            })
            saveBtn.setOnClickListener { saveCanvasTemplate() }
            loadBtn.setOnClickListener { loadCanvasTemplate() }
            addBtn.setOnClickListener { doAddCanvasToImage() }
        })

        return col
    }

    /** 元素排版 Dialog（与涂鸦一致：图片页入口，全屏编辑） */
    private fun showLayoutDialog() {
        val content = buildLayoutEditor()
        val scroll = ScrollView(this).apply { addView(content) }
        layoutDialog = Dialog(this).apply {
            setTitle("📐 元素排版")
            setContentView(scroll)
            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        layoutDialog?.show()
    }

    /** 排版结果加入图片通道（预览确认 → selectedImages → 图片页统一打印） */
    private fun doAddCanvasToImage() {
        if (canvasLayout.elements.isEmpty()) {
            Toast.makeText(this, "画布是空的，先添加元素", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            try {
                val bmp = CanvasEditor.render(canvasLayout.elements)
                val raster = RasterEncoder.encode(bmp, DitherMode.NONE, RasterEncoder.THRESHOLD_IMAGE)
                val previewBmp = imagePreviewRaster(raster)
                val desc = "${raster.widthBytes * 8}×${raster.height} 点，约 ${"%.0f".format(raster.height / 8.0)}mm 高"
                previewConfirmDialog("确认加入排版画布（$desc）", previewBmp) {
                    selectedImages.add(bmp)
                    updateThumbnail()
                    layoutDialog?.dismiss()
                    imageStatus.text = "已加入排版画布（共 ${selectedImages.size} 张图）"
                }
            } catch (e: Exception) {
                PrintLog.event("排版画布异常: ${e.javaClass.simpleName}: ${e.message}")
                Toast.makeText(this@MainActivity, "排版生成失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 选中元素变化 → 刷新编辑面板（文字/条码控件与元素双向同步） */
    private fun refreshCanvasEditor() {
        val el = canvasLayout.selected
        if (el == null) {
            canvasEditorCard.visibility = View.GONE
            return
        }
        canvasEditorCard.visibility = View.VISIBLE
        canvasEditorTitle.text = when (el.kind) {
            CanvasElement.KIND_TEXT -> "编辑文字元素"
            CanvasElement.KIND_IMAGE -> "编辑图片元素"
            else -> "编辑条码元素"
        }
        canvasEditText.visibility = if (el.kind == CanvasElement.KIND_TEXT) View.VISIBLE else View.GONE
        canvasFontBar.visibility = if (el.kind == CanvasElement.KIND_TEXT) View.VISIBLE else View.GONE
        canvasBoldCheck.visibility = if (el.kind == CanvasElement.KIND_TEXT) View.VISIBLE else View.GONE
        canvasBarcodeTypeGroup.visibility = if (el.kind == CanvasElement.KIND_BARCODE) View.VISIBLE else View.GONE
        canvasBarcodeInput.visibility = if (el.kind == CanvasElement.KIND_BARCODE) View.VISIBLE else View.GONE
        canvasBarcodeHint.visibility = if (el.kind == CanvasElement.KIND_BARCODE) View.VISIBLE else View.GONE
        if (el.kind == CanvasElement.KIND_TEXT) {
            canvasEditText.setText(el.text)
            canvasFontBar.progress = (el.fontSize.toInt() - 12).coerceIn(0, 36)
            canvasBoldCheck.isChecked = el.bold
        } else if (el.kind == CanvasElement.KIND_BARCODE) {
            val idx = BarcodeGenerator.TYPES.indexOf(el.barcodeType).coerceAtLeast(0)
            val rb = canvasBarcodeTypeGroup.getChildAt(idx)
            if (rb is RadioButton && !rb.isChecked) rb.isChecked = true
            canvasBarcodeInput.setText(el.barcodeContent)
            updateCanvasBarcodeHint(el)
        }
    }

    private fun syncCanvasTextElement() {
        val el = canvasLayout.selected ?: return
        if (el.kind != CanvasElement.KIND_TEXT) return
        el.text = canvasEditText.text.toString()
        canvasLayout.invalidate()
    }

    private fun syncCanvasBarcodeElement() {
        val el = canvasLayout.selected ?: return
        if (el.kind != CanvasElement.KIND_BARCODE) return
        el.barcodeContent = canvasBarcodeInput.text.toString().trim()
        updateCanvasBarcodeHint(el)
        canvasLayout.invalidate()
    }

    private fun updateCanvasBarcodeHint(el: CanvasElement) {
        if (el.barcodeContent.isEmpty()) {
            canvasBarcodeHint.text = "请输入条码内容"
            canvasBarcodeHint.setTextColor(Design.TEXT_SUB)
            return
        }
        val err = BarcodeGenerator.validate(el.barcodeType, el.barcodeContent)
        canvasBarcodeHint.text = err ?: "✓ 内容有效"
        canvasBarcodeHint.setTextColor(if (err == null) Design.PRIMARY else Design.ERROR)
    }

    /** 存模板：输入名称（有同名弹覆盖确认） */
    private fun saveCanvasTemplate() {
        if (canvasLayout.elements.isEmpty()) {
            Toast.makeText(this, "画布是空的", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            hint = "模板名称（如：单词卡）"
            textSize = 14f
        }
        AlertDialog.Builder(this)
            .setTitle("存为模板")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (CanvasEditor.templateNames(this).contains(name)) {
                    AlertDialog.Builder(this)
                        .setTitle("模板已存在")
                        .setMessage("「$name」已存在，覆盖？")
                        .setPositiveButton("覆盖") { _, _ ->
                            if (CanvasEditor.saveTemplate(this, name, canvasLayout.elements)) {
                                Toast.makeText(this, "已保存「$name」", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "保存失败（图片元素不会保存）", Toast.LENGTH_LONG).show()
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    CanvasEditor.saveTemplate(this, name, canvasLayout.elements)
                    Toast.makeText(this, "已保存「$name」", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 加载模板：列表选择（可长按删除） */
    private fun loadCanvasTemplate() {
        val names = CanvasEditor.templateNames(this)
        if (names.isEmpty()) {
            Toast.makeText(this, "还没有模板，先画一个存起来吧", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("加载模板")
            .setItems(names.toTypedArray()) { _, which ->
                val name = names[which]
                val els = CanvasEditor.loadTemplate(this, name)
                canvasLayout.clear()
                canvasLayout.elements.addAll(els)
                canvasLayout.invalidate()
                refreshCanvasEditor()
                Toast.makeText(this, "已加载「$name」（图片元素需重新添加）", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("管理模板", { _, _ ->
                if (names.isEmpty()) return@setNeutralButton
                AlertDialog.Builder(this)
                    .setTitle("管理模板")
                    .setItems(names.toTypedArray()) { _, which ->
                        val name = names[which]
                        AlertDialog.Builder(this)
                            .setMessage("删除模板「$name」？")
                            .setPositiveButton("删除") { _, _ ->
                                CanvasEditor.deleteTemplate(this, name)
                                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    .show()
            })
            .setNegativeButton("取消", null)
            .show()
    }

    /** 画布打印：合成 → 阈值编码（条码页同管线）→ 预览确认 → 打印 */
    // ── 文档内容块（PDF / Word / Excel，2026-08-11 加）──
    private fun buildDocContent(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(Design.card {
            addView(Design.sectionTitle("文档打印"))
            addView(Design.caption("PDF 逐页图片打印 · Word/Excel/TXT 文本打印（支持老格式 doc/xls）"))
            val pickBtn = Design.outlineButton("📄 选择文档（PDF / docx / xlsx / doc / xls / txt）")
            addView(pickBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(8)
            })
            pickBtn.setOnClickListener {
                // 文件筛选（2026-08-11 修：*/* 会把图片等全显示出来）
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                        "application/pdf",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/msword",                       // .doc 老格式（解析会提示不支持）
                        "application/vnd.ms-excel",                  // .xls 老格式
                        "text/plain",                                // .txt
                    ))
                }
                startActivityForResult(intent, REQ_DOC)
            }
        })

        col.addView(Design.card {
            addView(Design.sectionTitle("打印预览"))
            docPreview = ImageView(this@MainActivity).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            addView(docPreview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(8)
            })
            addView(Design.caption("先预览再打印，防废纸"))
            val previewBtn = Design.outlineButton("👁 预览打印效果")
            addView(previewBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Design.dp(8)
            })
            previewBtn.setOnClickListener { renderDocPreview() }
        })

        val printBtn = Design.primaryButton("🖨 打印文档")
        col.addView(printBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Design.dp(4)
        })
        printBtn.setOnClickListener { doPrintDoc() }

        docStatus = Design.caption("")
        col.addView(docStatus)
        return col
    }

    /**
     * 按扩展名分派解析 → 光栅。PDF 走图片通道（m=2），Word/Excel 走文字通道（m=0）。
     * @param onProgress 解析进度回调（阶段文案，大文档防"像没反应"）
     */
    private suspend fun renderDocRaster(uri: Uri, name: String, onProgress: (String) -> Unit = {}): Pair<RasterData, Int> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val lower = name.lowercase()
            when {
                lower.endsWith(".pdf") -> {
                    val bmp = PdfPrintRenderer.renderToBitmap(this@MainActivity, uri)
                    RasterEncoder.encode(bmp, DitherMode.NONE, 190, contrast = 10) to 2
                }
                lower.endsWith(".docx") -> {
                    val paras = DocxTextExtractor.extract(this@MainActivity, uri) { n ->
                        onProgress("已提取 $n 段…")
                    }
                    RasterEncoder.encodeText(paras.joinToString("\n")) to 0
                }
                lower.endsWith(".xlsx") -> {
                    val lines = XlsxTextExtractor.extract(this@MainActivity, uri) { n ->
                        onProgress("已读取 $n 行…")
                    }
                    RasterEncoder.encodeText(lines.joinToString("\n")) to 0
                }
                lower.endsWith(".txt") || lower.endsWith(".text") -> {
                    // 流式读 + 大小上限（2026-08-11：readBytes 全量读入大文件会 OOM 闪退）
                    val text = readTextLimited(this@MainActivity, uri)
                    RasterEncoder.encodeText(text) to 0
                }
                lower.endsWith(".doc") -> {
                    // 老格式 OLE2：FIB 文本流（UTF-16LE），简单文档可靠
                    val paras = LegacyDocExtractor.extractDoc(this@MainActivity, uri)
                    RasterEncoder.encodeText(paras.joinToString("\n")) to 0
                }
                lower.endsWith(".xls") -> {
                    // 老格式 OLE2：BIFF8 SST 共享字符串表
                    val strs = LegacyDocExtractor.extractXls(this@MainActivity, uri)
                    RasterEncoder.encodeText(strs.joinToString("\n")) to 0
                }
                else -> throw IllegalStateException("不支持的文档类型，请选择 PDF / docx / xlsx / doc / xls / txt")
            }
        }

    /**
     * txt 流式读取（2026-08-11 加）：上限 5MB 防 OOM（readBytes 全量读入大文件会崩）。
     * 编码：BOM 检测 → UTF-8 严格尝试 → GBK 兜底（中文 txt 常见 GBK）。
     */
    private fun readTextLimited(context: Context, uri: Uri): String {
        val maxBytes = 5 * 1024 * 1024
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = java.io.ByteArrayOutputStream()
            val tmp = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(tmp)
                if (n < 0) break
                total += n
                if (total > maxBytes) {
                    // 截断到上限（防止一次分配超限数组）
                    buf.write(tmp, 0, n - (total - maxBytes))
                    break
                }
                buf.write(tmp, 0, n)
            }
            buf.toByteArray()
        } ?: throw IllegalStateException("无法读取文件")
        return decodeText(bytes)
    }

    /**
     * txt 解码：BOM 检测 → UTF-8 严格尝试 → GBK 兜底
     * （中文 txt 常见 GBK 编码，直接按 UTF-8 读会乱码）。
     */
    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)          // UTF-8 BOM
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)       // UTF-16 LE BOM
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        // 含替换符 U+FFFD = 非合法 UTF-8 → 按 GBK 解码（Android 一直可用）
        return if (utf8.contains('�')) {
            runCatching { String(bytes, java.nio.charset.Charset.forName("GBK")) }.getOrDefault(utf8)
        } else {
            utf8
        }
    }

    private fun renderDocPreview() {
        val raster = currentDocRaster ?: run {
            docStatus.text = "请先选择文档"
            return
        }
        docPreview.setImageBitmap(imagePreviewRaster(raster))
    }

    private fun doPrintDoc() {
        val raster = currentDocRaster ?: run {
            docStatus.text = "请先选择文档"
            return
        }
        val title = currentDocTitle
        previewConfirmDialog(title, imagePreviewRaster(raster)) {
            doPrintConfirmed(
                raster, mode = currentDocMode, halveRows = currentDocMode == 2,
                okMessage = "文档打印完成", statusView = docStatus,
                historyType = "文档", historyTitle = title,
            )
        }
    }

    /** 查询文件显示名（系统文件选择器返回的 uri 只有 id） */
    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

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
            // 连接方式（2026-08-11 加：X1 存在多版本，透传版走 BLE、经典版走 SPP）
            addView(Design.label("连接方式"))
            val modeGroup = Design.segmentGroup(
                ConnectionMode.entries.map { it.label to it },
                defaultIndex = ConnectionMode.entries.indexOf(Settings.connectionMode),
            ) { i ->
                Settings.connectionMode = ConnectionMode.entries[i]
            }
            addView(modeGroup)
            addView(Design.caption("自动 = 先试 BLE 透传，无响应自动改走经典蓝牙"))
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
                    // 动态读版本号（与 build.gradle.kts versionName 同步，2026-08-11 修硬编码）
                    text = "v" + runCatching {
                        packageManager.getPackageInfo(packageName, 0).versionName
                    }.getOrDefault("0.3.0")
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
                setTextColor(Design.TEXT_SUB)
                setPadding(0, Design.dp(8), 0, 0)
                setOnClickListener { printSelfTest() }
            }
            val debugLink = TextView(this@MainActivity).apply {
                text = "调试台"
                textSize = 11f
                setTextColor(Design.TEXT_SUB)
                setPadding(0, Design.dp(8), 0, 0)
                setOnClickListener { startActivity(Intent(this@MainActivity, DebugActivity::class.java)) }
            }
            val updateLink = TextView(this@MainActivity).apply {
                text = "检查更新"
                textSize = 11f
                setTextColor(Design.TEXT_SUB)
                setPadding(0, Design.dp(8), 0, 0)
                setOnClickListener { checkForUpdate() }
            }
            hiddenRow.addView(selfTestLink)
            hiddenRow.addView(debugLink, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Design.dp(16)
            })
            hiddenRow.addView(updateLink, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
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
        // 画布：选一张图作为画布元素（2026-08-12）
        if (requestCode == REQ_CANVAS_IMAGE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val bmp = loadImage(uri) ?: return
            // 等比缩到 384 宽内（画布逻辑坐标），避免原图过大拖慢渲染
            val scale = minOf(1f, CanvasEditor.WIDTH.toFloat() / bmp.width)
            val w = (bmp.width * scale).toInt().coerceAtLeast(1)
            val h = (bmp.height * scale).toInt().coerceAtLeast(1)
            val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
            canvasLayout.addElement(CanvasElement(CanvasElement.KIND_IMAGE, 0f, 0f, scaled.width.toFloat(), scaled.height.toFloat()).apply {
                image = scaled
            })
            refreshCanvasEditor()
        }
        // 文档（PDF / Word / Excel）：选中即异步解析 + 预览
        if (requestCode == REQ_DOC && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val name = queryDisplayName(uri) ?: "文档"
            currentDocUri = uri
            currentDocTitle = name
            currentDocRaster = null
            // 立即清旧预览（2026-08-11 修：切新文件时旧图挂 ImageView 上，
            // 解析期间/失败时界面看起来"没刷新"）
            docPreview.setImageDrawable(null)
            docStatus.setTextColor(Design.TEXT_SUB)
            docStatus.text = "⏳ 正在解析 $name ..."
            // 解析加载对话框（2026-08-11 加：大 Word/Excel 解析要几秒~几十秒，
            // 静态文案像没反应；进度条 + 阶段文案 + 可取消）
            val parseLabel = TextView(this).apply {
                textSize = 13f
                setTextColor(Design.TEXT)
                text = "正在解析 $name …"
                setPadding(0, Design.dp(4), 0, Design.dp(10))
            }
            val parseBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                isIndeterminate = true
            }
            val parseContent = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Design.dp(24), Design.dp(8), Design.dp(24), Design.dp(8))
                addView(parseLabel)
                addView(parseBar)
            }
            val parseDialog = AlertDialog.Builder(this)
                .setTitle("📄 正在解析文档")
                .setView(parseContent)
                .setNegativeButton("取消", null)
                .create()
            parseDialog.show()
            // 新解析先取消旧的（大文件竞态：旧协程晚完成会覆盖新文件结果）
            docParseJob?.cancel()
            docParseJob = scope.launch {
                try {
                    val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
                    val (raster, mode) = renderDocRaster(uri, name) { progress ->
                        // 解析在 IO 线程，进度回传切主线程更新 UI
                        uiHandler.post { parseLabel.text = "正在解析 $name … $progress" }
                    }
                    if (parseDialog.isShowing) parseDialog.dismiss()
                    currentDocRaster = raster
                    currentDocMode = mode
                    docStatus.setTextColor(Design.TEXT)
                    // 空/极少内容（<64 行）提示，避免"选完没反应"的困惑（2026-08-11）
                    if (raster.height < 64) {
                        docStatus.setTextColor(Design.ERROR)
                        docStatus.text = "⚠️ $name 没有可打印的内容（可能文档是空的）"
                        docPreview.setImageDrawable(null)
                        return@launch
                    }
                    docStatus.text = "✅ $name（${raster.height} 行，点击预览确认）"
                    docPreview.setImageBitmap(imagePreviewRaster(raster))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e   // 被新任务/用户取消：不吞，保持取消语义
                } catch (e: OutOfMemoryError) {
                    // OOM 是 Error 不是 Exception，必须单独抓（2026-08-11 大文件闪退根因）
                    if (parseDialog.isShowing) parseDialog.dismiss()
                    PrintLog.event("文档解析 OOM: ${e.message}")
                    docStatus.setTextColor(Design.ERROR)
                    docStatus.text = "文件过大内存不足，请用较小文档重试"
                } catch (e: Throwable) {
                    if (parseDialog.isShowing) parseDialog.dismiss()
                    PrintLog.event("文档解析失败: ${e.message}")
                    docStatus.setTextColor(Design.ERROR)
                    docStatus.text = "解析失败：${e.message}"
                }
            }
            parseDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                docParseJob?.cancel()
                parseDialog.dismiss()
                docStatus.text = "已取消解析"
                docStatus.setTextColor(Design.TEXT_SUB)
            }
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
            setTextColor(Design.TEXT_SUB)
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
        val gray = Design.TEXT_SUB
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
        // 连接进度对话框（2026-08-11 加：AUTO 模式最坏约 40 秒，进度条 + 阶段文案防"像死机"）
        val progressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
        }
        val progressLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Design.TEXT_SUB)
            text = "正在连接 $name …"
            setPadding(0, Design.dp(8), 0, 0)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Design.dp(24), Design.dp(8), Design.dp(24), Design.dp(4))
            addView(progressBar)
            addView(progressLabel)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("🖨 正在连接打印机")
            .setView(content)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        var job: kotlinx.coroutines.Job? = null
        job = scope.launch {
            // 双通道分派（AUTO/BLE/SPP），阶段文案 + 进度实时驱动状态栏和对话框
            val ok = PrinterHolder.connect(dev) { phase, progress ->
                statusText.text = "⏳ $phase"
                progressLabel.text = phase
                if (progress != null) progressBar.progress = progress
            }
            if (dialog.isShowing) dialog.dismiss()
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
                statusBadge.setTextColor(Design.TEXT_SUB)
                statusBadge.background = Design.rounded(Design.SECONDARY_CONTAINER, Design.RADIUS_SM)
                statusText.text = "❌ 连接失败，请确认打印机已开机并处于配对状态"
                statusText.setTextColor(Design.ERROR)
            }
            switchPage(PAGE_HOME)
        }
        // 取消：中断连接协程（底层阻塞操作无法立即中断，但 UI 立即恢复可操作）
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            job?.cancel()
            dialog.dismiss()
            statusBadge.text = "● 未连接"
            statusBadge.setTextColor(Design.TEXT_SUB)
            statusBadge.background = Design.rounded(Design.SECONDARY_CONTAINER, Design.RADIUS_SM)
            statusText.text = "已取消连接"
            statusText.setTextColor(Design.TEXT_SUB)
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

    /** 图片预处理：裁白边 + 消除笔，再拼接 + 抖动/增强/描边 */
    private fun encodeSelectedImages(images: List<Bitmap>): RasterData {
        // 描边独立管线（最优先，2026-08-11 加）：不经过裁边/消除笔/灰度，
        // 直接在拼接图上做边缘检测（xyprt toMono OUTLINE 分支语义）
        if (outlineCheck.isChecked) {
            val layout = (layoutGroup.checkedRadioButtonId.takeIf { it != -1 }
                ?.let { layoutGroup.findViewById<RadioButton>(it)?.tag as? Int }) ?: 1
            val composed = if (images.size > 1) RasterEncoder.composeImages(images, layout) else images[0]
            val method = (outlineMethodGroup.checkedRadioButtonId.takeIf { it != -1 }
                ?.let { outlineMethodGroup.findViewById<RadioButton>(it)?.tag as? OutlineMethod })
                ?: Settings.outlineMethod
            val thickness = (outlineThicknessGroup.checkedRadioButtonId.takeIf { it != -1 }
                ?.let { outlineThicknessGroup.findViewById<RadioButton>(it)?.tag as? Int })
                ?: Settings.outlineThickness
            return RasterEncoder.encodeOutline(
                composed, method,
                sensitivity = outlineSensitivityBar.progress,
                thickness = thickness,
                smooth = outlineSmoothCheck.isChecked,
                invert = outlineInvertCheck.isChecked,
            )
        }
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
            RasterEncoder.encode(composed, mode, thresholdBar.progress)
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

    /** 画布涂鸦（2026-08-11 借鉴 lztttt，并入图片通道：完成即加入 selectedImages） */
    private fun showCanvasDialog() {
        val canvasView = DrawCanvasView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Design.dp(380))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Design.dp(8), Design.dp(4), Design.dp(8), Design.dp(4))
            addView(canvasView)
            addView(Design.row {
                val clearBtn = Design.ghostButton("清空")
                val undoBtn = Design.ghostButton("撤销")
                addView(clearBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(undoBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = Design.dp(8)
                })
                clearBtn.setOnClickListener { canvasView.clear() }
                undoBtn.setOnClickListener { canvasView.undo() }
            }.also {
                it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = Design.dp(8)
                }
            })
        }
        AlertDialog.Builder(this)
            .setTitle("🖌 画布涂鸦")
            .setView(content)
            .setPositiveButton("✅ 加入图片", null)
            .setNegativeButton("取消", null)
            .create().apply {
                show()
                getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val bmp = canvasView.toBitmap()
                    selectedImages.add(bmp)
                    dismiss()
                    updateThumbnail()
                    imageStatus.text = "已加入画布涂鸦（共 ${selectedImages.size} 张图）"
                }
            }
    }

    /** 口算题：类型 + 题数 → 生成 → 预览 → 打印（2026-08-11 加，与课程表等模板同组） */
    private fun showMathDialog() {
        val types = MathWorksheet.Op.entries
        val typeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        types.forEachIndexed { i, op ->
            val rb = RadioButton(this).apply {
                text = op.label
                textSize = 14f
                id = View.generateViewId()
                isChecked = i == 0
                setPadding(Design.dp(8), Design.dp(4), 0, Design.dp(4))
            }
            typeGroup.addView(rb)
        }
        val countGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf(6, 12, 18).forEachIndexed { i, n ->
            val rb = RadioButton(this).apply {
                text = "$n 题"
                textSize = 14f
                id = View.generateViewId()
                tag = n
                isChecked = i == 1
                setPadding(Design.dp(8), Design.dp(4), Design.dp(8), Design.dp(4))
            }
            countGroup.addView(rb)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Design.dp(8), Design.dp(4), Design.dp(8), Design.dp(4))
            addView(TextView(this@MainActivity).apply {
                text = "类型"; textSize = 13f; setTextColor(Design.TEXT_SUB)
            })
            addView(typeGroup)
            addView(TextView(this@MainActivity).apply {
                text = "题数"; textSize = 13f; setTextColor(Design.TEXT_SUB)
                setPadding(0, Design.dp(8), 0, 0)
            })
            addView(countGroup)
        }
        AlertDialog.Builder(this)
            .setTitle("🖊 口算题")
            .setView(content)
            .setPositiveButton("生成", null)
            .setNegativeButton("取消", null)
            .create().apply {
                show()
                getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val op = types[typeGroup.checkedRadioButtonId.takeIf { it != -1 }?.let {
                        typeGroup.indexOfChild(typeGroup.findViewById(it))
                    } ?: 0]
                    val count = (countGroup.checkedRadioButtonId.takeIf { it != -1 }
                        ?.let { countGroup.findViewById<RadioButton>(it)?.tag as? Int }) ?: 12
                    dismiss()
                    printTemplate { MathWorksheet.build(op, count) }
                }
            }
    }

    /** OTA 检查更新（藏在我的页 → 关于）：GitHub Releases 版本比对 + 下载安装 */
    private fun checkForUpdate() {
        val current = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrDefault("?")
        val checking = android.app.ProgressDialog(this).apply {
            setMessage("正在检查更新（v$current）…")
            setCancelable(false)
        }
        checking.show()
        UpdateManager.check(scope, this, object : UpdateManager.Listener {
            override fun onUpdateAvailable(version: String, notes: String, url: String) {
                checking.dismiss()
                if (url.isEmpty()) {
                    Toast.makeText(this@MainActivity, "发现新版本 v$version，但下载地址为空", Toast.LENGTH_LONG).show()
                    return
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("发现新版本 v$version")
                    .setMessage(notes.ifEmpty { "点击更新下载安装" })
                    .setPositiveButton("立即更新") { _, _ -> downloadUpdate(version, url) }
                    .setNegativeButton("稍后", null)
                    .show()
            }

            override fun onResult(message: String?) {
                checking.dismiss()
                Toast.makeText(
                    this@MainActivity,
                    message ?: "已是最新版本（v$current）",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
    }

    /** 下载 APK（进度条）→ FileProvider 安装 */
    private fun downloadUpdate(version: String, url: String) {
        val progress = android.app.ProgressDialog(this).apply {
            setTitle("正在下载 v$version")
            setMessage("0%")
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            max = 100
            setButton(android.app.ProgressDialog.BUTTON_NEGATIVE, "取消") { _, _ -> }
        }
        progress.show()
        UpdateManager.downloadAndInstall(
            scope, this, version, url,
            onProgress = { p ->
                progress.progress = p
                progress.setMessage("$p%")
            },
            onDone = { success ->
                runCatching { if (progress.isShowing) progress.dismiss() }
                PrintLog.event("OTA 下载${if (success) "成功并触发安装" else "失败"} v$version")
            }
        )
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
