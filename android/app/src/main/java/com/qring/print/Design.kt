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

/** 界面主题（2026-08-18 加）：微信风 / xyprt 简洁风 / 仿喵喵机蓝白风 */
enum class UiTheme(val label: String) {
    WECHAT("微信风"),
    XYPRT("简洁风"),
    MIAOMIAO("蓝白风"),
}

/**
 * 设计系统 —— 微信小程序风格（2026-08-11 用户选定，替换原 M3 学习绿）。
 *
 * 微信风核心：
 * - **灰底白卡**：页面背景 #F7F7F7，卡片纯白 #FFFFFF 圆角 8px，卡间靠间距分层（无阴影）
 * - **微信绿主色** #07C160：按钮/选中态/强调
 * - **文字层级**：主 #191919 / 次 #888888 / 辅助 #B2B2B2
 * - **分隔线** #EBEDF0
 * - 按钮为 8px 圆角（非胶囊）；输入框浅灰底无描边
 * - 深色模式：微信深色风（#111111 底 / #1E1E1E 卡）
 * 纯代码实现，API 兼容旧调用点。
 */
object Design {

    /** 深色模式开关：MainActivity.onResume 按系统 uiMode 设置 */
    @Volatile
    var isDark: Boolean = false

    /** 界面主题（2026-08-18 加）：设置页可切换 */
    @Volatile
    var theme: UiTheme = UiTheme.WECHAT

    // ── 主题 Color Scheme（2026-08-18 加：微信风 / xyprt 简洁风 / 仿喵喵机蓝白风）──
    private data class Palette(
        val primary: Int, val primaryDeep: Int, val primaryContainer: Int,
        val onPrimaryContainer: Int, val secondaryContainer: Int, val onSecondaryContainer: Int,
        val surface: Int, val surfaceContainerLow: Int, val surfaceContainer: Int,
        val onSurface: Int, val onSurfaceVariant: Int, val outline: Int, val outlineVariant: Int,
    )

    private val pal: Palette
        get() = when (theme) {
            UiTheme.XYPRT -> if (isDark) Palette(
                0xFF7A9BFF.toInt(), 0xFF6A8BFF.toInt(), 0xFF25345C.toInt(), 0xFFAFC4FF.toInt(),
                0xFF262626.toInt(), 0xFFE5E5E5.toInt(), 0xFF111114.toInt(), 0xFF1C1C22.toInt(),
                0xFF26262E.toInt(), 0xFFE5E5E5.toInt(), 0xFF9A9A9A.toInt(), 0xFF3A3A44.toInt(), 0xFF2A2A32.toInt(),
            ) else Palette(
                0xFF3B6EF6.toInt(), 0xFF2F5FE0.toInt(), 0xFFE8EFFF.toInt(), 0xFF3B6EF6.toInt(),
                0xFFF2F3F5.toInt(), 0xFF191919.toInt(), 0xFFF5F6FA.toInt(), 0xFFFFFFFF.toInt(),
                0xFFF0F1F5.toInt(), 0xFF1A1A1A.toInt(), 0xFF888888.toInt(), 0xFFD8DCE5.toInt(), 0xFFEAECF0.toInt(),
            )
            UiTheme.MIAOMIAO -> if (isDark) Palette(
                0xFF6FA8E8.toInt(), 0xFF5F9ADF.toInt(), 0xFF233A55.toInt(), 0xFFA8CCF5.toInt(),
                0xFF262626.toInt(), 0xFFE5E5E5.toInt(), 0xFF10151C.toInt(), 0xFF1A212B.toInt(),
                0xFF242D39.toInt(), 0xFFE5E5E5.toInt(), 0xFF9A9A9A.toInt(), 0xFF3A4655.toInt(), 0xFF2A3440.toInt(),
            ) else Palette(
                0xFF4A90D9.toInt(), 0xFF3D7FC7.toInt(), 0xFFE3F0FC.toInt(), 0xFF4A90D9.toInt(),
                0xFFF2F4F7.toInt(), 0xFF191919.toInt(), 0xFFF0F4FF.toInt(), 0xFFFFFFFF.toInt(),
                0xFFE8EEF7.toInt(), 0xFF1A1A1A.toInt(), 0xFF888888.toInt(), 0xFFD5DEE9.toInt(), 0xFFE8EEF5.toInt(),
            )
            else -> if (isDark) Palette(
                0xFF07C160.toInt(), 0xFF06AD56.toInt(), 0xFF1F3D2C.toInt(), 0xFF8BE8B4.toInt(),
                0xFF262626.toInt(), 0xFFE5E5E5.toInt(), 0xFF111111.toInt(), 0xFF1E1E1E.toInt(),
                0xFF2A2A2A.toInt(), 0xFFE5E5E5.toInt(), 0xFF9A9A9A.toInt(), 0xFF3A3A3A.toInt(), 0xFF2A2A2A.toInt(),
            ) else Palette(
                0xFF07C160.toInt(), 0xFF06AD56.toInt(), 0xFFE8F8EE.toInt(), 0xFF07C160.toInt(),
                0xFFF2F3F5.toInt(), 0xFF191919.toInt(), 0xFFF7F7F7.toInt(), 0xFFFFFFFF.toInt(),
                0xFFF2F3F5.toInt(), 0xFF191919.toInt(), 0xFF888888.toInt(), 0xFFDADADA.toInt(), 0xFFEBEDF0.toInt(),
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
    /** 小圆角：随主题变化——微信 8px / xyprt 6px / 喵喵机胶囊 18px */
    val RADIUS_SM: Float
        get() = when (theme) {
            UiTheme.XYPRT -> 6f
            UiTheme.MIAOMIAO -> 18f
            else -> 8f
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

    // ── 页面容器 ──
    fun page(): LinearLayout = LinearLayout(Utils.appContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(32))
        setBackgroundColor(SURFACE)
    }

    /** 顶部标题栏：微信导航栏风（白底黑字 + 底部细分隔线） */
    fun header(text: String): LinearLayout = LinearLayout(Utils.appContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(16))
        setBackgroundColor(SURFACE_CONTAINER_LOW)
        addView(TextView(Utils.appContext()).apply {
            this.text = text
            textSize = 18f
            setTextColor(ON_SURFACE)
            typeface = Typeface.DEFAULT_BOLD
        })
    }

    /**
     * 卡片：微信风纯白卡 + 8px 圆角（深浅模式自动跟随），无阴影无描边，
     * 卡间分层靠页面灰底 + 外边距。
     */
    fun card(): LinearLayout = LinearLayout(Utils.appContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(SURFACE_CONTAINER_LOW, SHAPE_SMALL)
    }

    fun card(container: (LinearLayout.() -> Unit)): LinearLayout = card().also { it.container() }

    // ── 文字（微信层级：标题粗黑 / 正文深灰 / 辅助浅灰）──
    /** 小节标题：微信"标题"样式（纯文字粗体，无胶囊背景） */
    fun sectionTitle(text: String): TextView = TextView(Utils.appContext()).apply {
        this.text = text
        textSize = 16f
        setTextColor(ON_SURFACE)
        typeface = Typeface.DEFAULT_BOLD
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

    // ── 按钮（微信风：8px 圆角方按钮，非胶囊）──
    private fun buttonRadius(): Float = when (theme) {
        UiTheme.MIAOMIAO -> SHAPE_FULL
        UiTheme.XYPRT -> 6f
        else -> SHAPE_SMALL
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
                    gravity = Gravity.CENTER
                    isAllCaps = false
                    minHeight = dp(34)
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

    // ── 输入框（微信风：浅灰底无描边 + 8px 圆角）──
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

    // ── 微信风线性图标（assets/icons/，2026-08-11 全量重制）──
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
