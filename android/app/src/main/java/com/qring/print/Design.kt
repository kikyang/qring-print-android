package com.qring.print

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

/** 界面主题（v0.7.6 起）：8 套差异化风格（旧微信风/简洁风/蓝白风已移除） */
enum class UiTheme(val label: String) {
    QRING("紫罗兰"),
    PAPER("墨绿纸感"),
    LINEARDARK("暗色效能"),
    NOTEBOOK("手账纸感"),
    THERMAL("热敏黑白"),
    CLAY("奶油多彩"),
    APPLE("Apple Bento"),
    GLASS("玻璃拟态"),
}

/**
 * 设计系统 —— v0.7.6 共 8 套差异化主题。
 *
 * 代码骨架保留早期「微信小程序风格」的纯代码实现方式：
 * 灰底白卡、圆角分级、按钮/分段控件、线性图标；每套主题通过
 * Palette + RADIUS_SM + buttonRadius + sectionTitle/header 差异化。
 * 旧微信风 / xyprt 简洁风 / 喵喵机蓝白风 已从 UiTheme 移除。
 */
object Design {

    /** 深色模式开关：MainActivity.onResume 按系统 uiMode 设置 */
    @Volatile
    var isDark: Boolean = false

    /** 界面主题（2026-08-18 加）：设置页可切换 */
    @Volatile
    var theme: UiTheme = UiTheme.PAPER

    // ── 主题 Color Scheme（v0.7.6：8 套）──
    private data class Palette(
        val primary: Int, val primaryDeep: Int, val primaryContainer: Int,
        val onPrimaryContainer: Int, val secondaryContainer: Int, val onSecondaryContainer: Int,
        val surface: Int, val surfaceContainerLow: Int, val surfaceContainer: Int,
        val onSurface: Int, val onSurfaceVariant: Int, val outline: Int, val outlineVariant: Int,
    )

    private val pal: Palette
        get() = when (theme) {
            // 2026-09-05 v0.7.6：8 套差异化主题（旧微信风/简洁风/蓝白风已移除）
            UiTheme.QRING -> if (isDark) Palette(
                0xFF9F8CFF.toInt(), 0xFF7B5CE7.toInt(), 0xFF2F2458.toInt(), 0xFFD0C2FF.toInt(),
                0xFF33313D.toInt(), 0xFFE5E5E5.toInt(), 0xFF14121B.toInt(), 0xFF1C1A26.toInt(),
                0xFF24222E.toInt(), 0xFFEDEBF4.toInt(), 0xFFA49EB2.toInt(), 0xFF4A4657.toInt(), 0xFF353241.toInt(),
            ) else Palette(
                0xFF6C5CE7.toInt(), 0xFF5A48D9.toInt(), 0xFFEEEBFF.toInt(), 0xFF340D9B.toInt(),
                0xFFF0EEF8.toInt(), 0xFF6A677B.toInt(), 0xFFF4F4FA.toInt(), 0xFFFFFFFF.toInt(),
                0xFFECEAF7.toInt(), 0xFF17151F.toInt(), 0xFF8B8A97.toInt(), 0xFFD9D6EC.toInt(), 0xFFECEBF4.toInt(),
            )
            UiTheme.PAPER -> if (isDark) Palette(
                0xFF8BD5C2.toInt(), 0xFF5FB89F.toInt(), 0xFF0E5144.toInt(), 0xFFB2F1DF.toInt(),
                0xFF354A43.toInt(), 0xFFD2E8E0.toInt(), 0xFF111412.toInt(), 0xFF171A18.toInt(),
                0xFF1C201D.toInt(), 0xFFE6EAE7.toInt(), 0xFFBCC4BE.toInt(), 0xFF89918C.toInt(), 0xFF404742.toInt(),
            ) else Palette(
                0xFF176B5B.toInt(), 0xFF0E4F42.toInt(), 0xFFD6EFE7.toInt(), 0xFF08261F.toInt(),
                0xFFEDF2EE.toInt(), 0xFF48605A.toInt(), 0xFFF7F8F4.toInt(), 0xFFFFFFFF.toInt(),
                0xFFEDF1EC.toInt(), 0xFF1B1D1C.toInt(), 0xFF6B716E.toInt(), 0xFFDDE3DE.toInt(), 0xFFE7EDE8.toInt(),
            )
            UiTheme.LINEARDARK -> Palette(
                0xFF55B3FF.toInt(), 0xFF3D8FDE.toInt(), 0xFF1B241F.toInt(), 0xFF9AD1FF.toInt(),
                0xFF1B1C1E.toInt(), 0xFFD6D6D8.toInt(), 0xFF07080A.toInt(), 0xFF101111.toInt(),
                0xFF16181B.toInt(), 0xFFF9F9F9.toInt(), 0xFF9C9C9D.toInt(), 0xFFFFFFFF.toInt(), 0x40252529.toInt(),
            )
            UiTheme.NOTEBOOK -> if (isDark) Palette(
                0xFFE07A62.toInt(), 0xFFC7442F.toInt(), 0xFF4A2A20.toInt(), 0xFFFFD7CD.toInt(),
                0xFF3A3025.toInt(), 0xFFD9C9B0.toInt(), 0xFF2B2118.toInt(), 0xFF382D20.toInt(),
                0xFF42372A.toInt(), 0xFFF3E9DC.toInt(), 0xFFB9A98E.toInt(), 0xFF6B5A45.toInt(), 0xFF57483A.toInt(),
            ) else Palette(
                0xFFD95B43.toInt(), 0xFFC7442F.toInt(), 0xFFFBE4DE.toInt(), 0xFF7A2614.toInt(),
                0xFFE6F1FA.toInt(), 0xFF3F7EBB.toInt(), 0xFFFBF7EE.toInt(), 0xFFFFFEF9.toInt(),
                0xFFF4EBDC.toInt(), 0xFF3A3428.toInt(), 0xFF938A77.toInt(), 0xFFE5DBC7.toInt(), 0xFFE9DFCB.toInt(),
            )
            UiTheme.THERMAL -> if (isDark) Palette(
                0xFFE8E8E0.toInt(), 0xFFFFFFFF.toInt(), 0xFF2A2A2A.toInt(), 0xFFEEEEEE.toInt(),
                0xFF333333.toInt(), 0xFFE5E5E5.toInt(), 0xFF1A1A1A.toInt(), 0xFF252525.toInt(),
                0xFF2E2E2E.toInt(), 0xFFF2F2F2.toInt(), 0xFF9A9A9A.toInt(), 0xFF4A4A4A.toInt(), 0xFF333333.toInt(),
            ) else Palette(
                0xFF111111.toInt(), 0xFF000000.toInt(), 0xFFEDEDE8.toInt(), 0xFF222222.toInt(),
                0xFFF1F1EB.toInt(), 0xFF222222.toInt(), 0xFFF4F4F0.toInt(), 0xFFFFFFFF.toInt(),
                0xFFECECE6.toInt(), 0xFF111111.toInt(), 0xFF777770.toInt(), 0xFFAEAEA5.toInt(), 0xFFE3E3DA.toInt(),
            )
            UiTheme.CLAY -> if (isDark) Palette(
                0xFFB7A0FF.toInt(), 0xFF9A82F0.toInt(), 0xFF31245A.toInt(), 0xFFD7CCFF.toInt(),
                0xFF3A3025.toInt(), 0xFFD9C9B0.toInt(), 0xFF201A16.toInt(), 0xFF2B241E.toInt(),
                0xFF352E27.toInt(), 0xFFF3EDE4.toInt(), 0xFFBBAE9E.toInt(), 0xFF6B5A45.toInt(), 0xFF57483A.toInt(),
            ) else Palette(
                0xFF43089F.toInt(), 0xFF32037D.toInt(), 0xFFEEE9FC.toInt(), 0xFF32037D.toInt(),
                0xFFF3F0EA.toInt(), 0xFF6B655B.toInt(), 0xFFFAF9F7.toInt(), 0xFFFFFFFF.toInt(),
                0xFFF1EEE6.toInt(), 0xFF111111.toInt(), 0xFF9F9B93.toInt(), 0xFFDAD4C8.toInt(), 0xFFEEE9DF.toInt(),
            )
            UiTheme.APPLE -> if (isDark) Palette(
                0xFF2997FF.toInt(), 0xFF0071E3.toInt(), 0xFF1A2A3A.toInt(), 0xFFA2D5FF.toInt(),
                0xFF2A2A2D.toInt(), 0xFFE5E5E5.toInt(), 0xFF000000.toInt(), 0xFF1D1D1F.toInt(),
                0xFF272729.toInt(), 0xFFF5F5F7.toInt(), 0xFF98989D.toInt(), 0xFF3A3A3C.toInt(), 0xFF272729.toInt(),
            ) else Palette(
                0xFF0071E3.toInt(), 0xFF0066CC.toInt(), 0xFFEAF3FE.toInt(), 0xFF0A4C93.toInt(),
                0xFFEDEDF0.toInt(), 0xFF3A3A3C.toInt(), 0xFFF5F5F7.toInt(), 0xFFFFFFFF.toInt(),
                0xFFEDEDF0.toInt(), 0xFF1D1D1F.toInt(), 0xFF6E6E73.toInt(), 0xFFD2D2D7.toInt(), 0xFFE8E8ED.toInt(),
            )
            UiTheme.GLASS -> if (isDark) Palette(
                0xFF6FA8FF.toInt(), 0xFF4D8AE5.toInt(), 0xFF22334D.toInt(), 0xFFB4D4FF.toInt(),
                0xFF2A3440.toInt(), 0xFFE5E5E5.toInt(), 0xFF10151C.toInt(), 0xAA242D39.toInt(),
                0xAA1A212B.toInt(), 0xFFF1F4F9.toInt(), 0xFF9AA8BC.toInt(), 0xFF3A4655.toInt(), 0xFF2A3440.toInt(),
            ) else Palette(
                0xFF2E6FF2.toInt(), 0xFF2458CC.toInt(), 0x33FFFFFF.toInt(), 0xFF14213D.toInt(),
                0x33FFFFFF.toInt(), 0xFF3E4D69.toInt(), 0xFFE8F1FF.toInt(), 0x99FFFFFF.toInt(),
                0x66FFFFFF.toInt(), 0xFF14213D.toInt(), 0xFF61708B.toInt(), 0x6699B0D0.toInt(), 0x33FFFFFF.toInt(),
            )
        }

    val PRIMARY: Int get() = pal.primary
    val ON_PRIMARY: Int get() = 0xFFFFFFFF.toInt()
    val PRIMARY_DEEP: Int get() = pal.primaryDeep
    val PRIMARY_CONTAINER: Int get() = pal.primaryContainer
    val ON_PRIMARY_CONTAINER: Int get() = pal.onPrimaryContainer
    val SECONDARY_CONTAINER: Int get() = pal.secondaryContainer
    val ON_SECONDARY_CONTAINER: Int get() = pal.onSecondaryContainer
    val SURFACE: Int get() = pal.surface
    val SURFACE_CONTAINER_LOW: Int get() = pal.surfaceContainerLow
    val SURFACE_CONTAINER: Int get() = pal.surfaceContainer
    val ON_SURFACE: Int get() = pal.onSurface
    val ON_SURFACE_VARIANT: Int get() = pal.onSurfaceVariant
    val OUTLINE: Int get() = pal.outline
    val OUTLINE_VARIANT: Int get() = pal.outlineVariant
    val ERROR: Int get() = 0xFFFA5151.toInt()
    val OK: Int get() = pal.primary

    // ── 兼容别名（旧调用点）──
    val BG: Int get() = SURFACE
    val CARD: Int get() = SURFACE_CONTAINER_LOW
    val TEXT: Int get() = ON_SURFACE
    val TEXT_SUB: Int get() = ON_SURFACE_VARIANT
    val DIVIDER: Int get() = OUTLINE_VARIANT
    val PRIMARY_LIGHT: Int get() = PRIMARY_CONTAINER
    /** 小圆角：随主题变化 */
    val RADIUS_SM: Float
        get() = when (theme) {
            UiTheme.QRING, UiTheme.PAPER -> 14f
            UiTheme.LINEARDARK -> 10f
            UiTheme.NOTEBOOK -> 12f
            UiTheme.THERMAL -> 4f
            UiTheme.CLAY -> 16f
            UiTheme.APPLE -> 10f
            UiTheme.GLASS -> 18f
        }

    /** 主题选择器预览色（不依赖当前主题状态） */
    fun themeAccent(t: UiTheme): Int = when (t) {
        UiTheme.QRING -> 0xFF6C5CE7.toInt()
        UiTheme.PAPER -> 0xFF176B5B.toInt()
        UiTheme.LINEARDARK -> 0xFF55B3FF.toInt()
        UiTheme.NOTEBOOK -> 0xFFD95B43.toInt()
        UiTheme.THERMAL -> 0xFF111111.toInt()
        UiTheme.CLAY -> 0xFF43089F.toInt()
        UiTheme.APPLE -> 0xFF0071E3.toInt()
        UiTheme.GLASS -> 0xFF2E6FF2.toInt()
    }

    // ── Shape 刻度（随主题）──
    private val SHAPE_SMALL: Float get() = RADIUS_SM
    private const val SHAPE_MEDIUM = 12f   // 大容器
    private const val SHAPE_LARGE = 28f    // 对话框
    private const val SHAPE_FULL = 999f    // 胶囊/兼容旧调用

    // ── 圆角背景工具 ──
    fun rounded(color: Int, radius: Float = SHAPE_SMALL, strokeColor: Int? = null, strokeW: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius
            if (strokeColor != null) setStroke(strokeW, strokeColor)
        }

    /** 按钮按下态（按住变深） */
    fun pressable(up: GradientDrawable, down: GradientDrawable): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), down)
            addState(intArrayOf(), up)
        }

    /**
     * 胶囊按钮文字垂直居中修复（2026-08-27 用户反馈：胶囊按钮内容错位）。
     * 根因：TextView 默认 includeFontPadding=true，按字体度量（升部/降部不对称）画文字，
     * 字形中心比几何中心偏下，胶囊大圆角下尤其刺眼。关掉后按字形框居中，配 CENTER 重力真正居中。
     */
    private fun android.widget.TextView.pillCentered() {
        includeFontPadding = false
        gravity = Gravity.CENTER
    }

    // ── 页面容器 ──
    fun page(): LinearLayout = LinearLayout(Utils.appContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(32))
        setBackgroundColor(SURFACE)
    }

    /** 顶部标题栏：默认中性底 + 墨黑字；玻璃拟态用渐变大圆角 */
    fun header(text: String): LinearLayout = LinearLayout(Utils.appContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(16))
        val gradHeader = theme == UiTheme.GLASS
        if (gradHeader) {
            val colors = if (isDark) intArrayOf(0xFF22334D.toInt(), 0xFF2E6FF2.toInt())
                else intArrayOf(0xFF6FA8FF.toInt(), 0xFFA9D3FF.toInt())
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                colors
            ).apply { cornerRadius = SHAPE_LARGE }
        } else {
            setBackgroundColor(SURFACE_CONTAINER_LOW)
        }
        addView(TextView(Utils.appContext()).apply {
            this.text = text
            textSize = if (gradHeader) 20f else 18f
            setTextColor(if (gradHeader && !isDark) 0xFFFFFFFF.toInt() else ON_SURFACE)
            typeface = Typeface.DEFAULT_BOLD
        })
    }

    /**
     * 卡片：浅色卡 + 随主题圆角；部分风格带柔和投影，卡间分层靠外边距。
     */
    fun card(): LinearLayout = LinearLayout(Utils.appContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(SURFACE_CONTAINER_LOW, SHAPE_SMALL)
        // 2026-09-05 v0.7.6：部分风格用柔和投影提升卡片层次
        if (theme in setOf(UiTheme.QRING, UiTheme.PAPER, UiTheme.NOTEBOOK, UiTheme.CLAY, UiTheme.APPLE, UiTheme.GLASS)) {
            elevation = dp(2).toFloat()
        }
    }

    fun card(container: (LinearLayout.() -> Unit)): LinearLayout = card().also { it.container() }

    // ── 文字（微信层级：标题粗黑 / 正文深灰 / 辅助浅灰）──
    /** 小节标题：纯文字粗体；热敏黑白加“▸”前缀并用等宽字体 */
    fun sectionTitle(text: String): TextView = TextView(Utils.appContext()).apply {
        this.text = if (theme == UiTheme.THERMAL) "▸ $text" else text
        textSize = if (theme == UiTheme.GLASS) 15f else 16f
        setTextColor(when (theme) {
            UiTheme.NOTEBOOK, UiTheme.CLAY -> PRIMARY
            else -> ON_SURFACE
        })
        typeface = if (theme == UiTheme.THERMAL) Typeface.MONOSPACE else Typeface.DEFAULT_BOLD
    }

    fun caption(text: String): TextView = TextView(Utils.appContext()).apply {
        this.text = text
        textSize = 12f
        setTextColor(ON_SURFACE_VARIANT)
    }

    /** 标签（表单字段名） */
    fun label(text: String): TextView = TextView(Utils.appContext()).apply {
        this.text = text
        textSize = 13f
        setTextColor(ON_SURFACE_VARIANT)
        setPadding(0, dp(8), 0, dp(4))
    }

    // ── 按钮（圆角随主题）──
    private fun buttonRadius(): Float = when (theme) {
        UiTheme.QRING, UiTheme.PAPER, UiTheme.NOTEBOOK, UiTheme.CLAY, UiTheme.GLASS -> SHAPE_FULL
        UiTheme.LINEARDARK, UiTheme.THERMAL, UiTheme.APPLE -> SHAPE_SMALL
    }

    /** 主按钮：主题化圆角（微信方 / xyprt 小圆角 / 喵喵机胶囊） */
    fun primaryButton(text: String): Button = Button(Utils.appContext()).apply {
        this.text = text
        setTextColor(ON_PRIMARY)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        minHeight = dp(44)
        setPadding(dp(20), 0, dp(20), 0)
        pillCentered()
        val r = buttonRadius()
        background = pressable(
            rounded(PRIMARY, r),
            rounded(PRIMARY_DEEP, r),
        )
    }

    /** 次按钮：白底 + 描边 + 主题色字 */
    fun outlineButton(text: String): Button = Button(Utils.appContext()).apply {
        this.text = text
        setTextColor(PRIMARY)
        textSize = 13.5f
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        minHeight = dp(40)
        setPadding(dp(16), 0, dp(16), 0)
        pillCentered()
        val r = buttonRadius()
        background = pressable(
            rounded(SURFACE_CONTAINER_LOW, r, OUTLINE, dp(1)),
            rounded(PRIMARY_CONTAINER, r, PRIMARY, dp(1)),
        )
    }

    /** 轻按钮：浅底 + 深字，主题化圆角 */
    fun ghostButton(text: String): Button = Button(Utils.appContext()).apply {
        this.text = text
        setTextColor(ON_SECONDARY_CONTAINER)
        textSize = 13.5f
        isAllCaps = false
        minHeight = dp(40)
        setPadding(dp(16), 0, dp(16), 0)
        pillCentered()
        val r = buttonRadius()
        background = pressable(
            rounded(SECONDARY_CONTAINER, r),
            rounded(if (isDark) 0xFF333333.toInt() else 0xFFE0E0E0.toInt(), r),
        )
    }

    // ── 分段控件（微信 segmented：白底灰边 + 选中浅绿底绿字）──
    fun segmentGroup(items: List<Pair<String, Any?>>, defaultIndex: Int = 0, onChange: ((Int) -> Unit)? = null): RadioGroup =
        RadioGroup(Utils.appContext()).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
            val r = buttonRadius()
            background = rounded(SURFACE_CONTAINER_LOW, r, OUTLINE, dp(1))
            items.forEachIndexed { i, (label, value) ->
                val rb = RadioButton(Utils.appContext()).apply {
                    text = label
                    textSize = 13f
                    isAllCaps = false
                    minHeight = dp(34)
                    pillCentered()
                    // checked 态持续高亮（浅绿底绿字），未选白底灰字
                    background = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_checked), rounded(PRIMARY_CONTAINER, r))
                        addState(intArrayOf(android.R.attr.state_pressed), rounded(PRIMARY_CONTAINER, r))
                        addState(intArrayOf(), rounded(SURFACE_CONTAINER_LOW, r))
                    }
                    setPadding(dp(12), 0, dp(12), 0)
                    setButtonDrawable(android.R.color.transparent)
                    id = android.view.View.generateViewId()
                    tag = value
                    isChecked = i == defaultIndex
                }
                addView(rb, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            setOnCheckedChangeListener { group, checkedId ->
                for (i in 0 until group.childCount) {
                    val child = group.getChildAt(i) as RadioButton
                    child.setTextColor(if (child.id == checkedId) PRIMARY else ON_SURFACE_VARIANT)
                }
                // 外部回调（预览自动刷新用；着色逻辑保持内部，不被覆盖）
                for (i in 0 until group.childCount) {
                    if (group.getChildAt(i).id == checkedId) onChange?.invoke(i)
                }
            }
            check(if (defaultIndex < items.size) getChildAt(defaultIndex).id else getChildAt(0).id)
        }

    // ── 输入框（浅灰底无描边 + 随主题圆角）──
    fun input(hint: String, lines: Int = 1): EditText = EditText(Utils.appContext()).apply {
        this.hint = hint
        setHintTextColor(OUTLINE)
        setTextColor(ON_SURFACE)
        textSize = 14f
        minHeight = dp(44)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = rounded(SURFACE_CONTAINER, SHAPE_SMALL)
        if (lines > 1) {
            minLines = lines
            gravity = Gravity.TOP or Gravity.START
        }
    }

    /** 复选框（系统默认，跟随系统色） */
    fun check(text: String): android.widget.CheckBox = android.widget.CheckBox(Utils.appContext()).apply {
        this.text = text
        textSize = 13f
        setTextColor(ON_SURFACE)
    }

    // ── 线性图标（assets/icons/，2026-08-11 全量重制）──
    object Icons {
        private val bitmapCache = HashMap<String, android.graphics.Bitmap?>()

        /** 加载位图图标（assets/icons/<name>.png，缓存） */
        fun bitmap(name: String): android.graphics.Bitmap? {
            if (bitmapCache.containsKey(name)) return bitmapCache[name]
            val bmp = runCatching {
                val `is` = Utils.appContext().assets.open("icons/$name.png")
                val b = android.graphics.BitmapFactory.decodeStream(`is`)
                `is`.close()
                b
            }.getOrNull()
            bitmapCache[name] = bmp
            return bmp
        }

        /** 位图图标 ImageView（指定尺寸 dp，居中显示） */
        fun imageView(name: String, sizeDp: Int): ImageView = ImageView(Utils.appContext()).apply {
            val bmp = bitmap(name)
            if (bmp != null) {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            layoutParams = android.view.ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
        }
    }

    // ── 工具 ──
    fun dp(v: Int): Int = (Utils.appContext().resources.displayMetrics.density * v).toInt()
    fun dp(v: Float): Int = (Utils.appContext().resources.displayMetrics.density * v).toInt()

    /** 行容器：水平排列 */
    fun row(): LinearLayout = LinearLayout(Utils.appContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun row(container: (LinearLayout.() -> Unit)): LinearLayout = row().also { it.container() }
}

/** 便捷访问 appContext */
object Utils {
    private lateinit var ctx: android.content.Context
    fun init(context: android.content.Context) { ctx = context.applicationContext }
    fun appContext(): android.content.Context = ctx
}
