package com.qring.print

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

/**
 * 设计系统 —— Material 3 规范（2026-08-11 按 material-3 skill 重构）。
 *
 * M3 核心应用：
 * - **Tonal surface 代替阴影**：卡片用 surface-container-low 色（不用 elevation 阴影）
 * - **圆角刻度**：卡片 medium(12dp)、按钮/分段 full(胶囊)、输入框 small(8dp)
 * - **8dp 间距体系**：页面 16dp、卡片内 16dp、元素 gap 8dp
 * - **色板 token**：primary/on-primary/primary-container/surface/outline 等 M3 角色
 * - **深色模式**：跟随系统（MainActivity.onResume 检测 uiMode 设置 [isDark]），双色板
 * 主色：学习绿 seed #2E7D32（用户 2026-08-11 选定）。纯代码实现，API 兼容。
 */
object Design {

    /** 深色模式开关：MainActivity.onResume 按系统 uiMode 设置 */
    @Volatile
    var isDark: Boolean = false

    // ── M3 Color Scheme ──
    // Light: seed 学习绿 #2E7D32 | Dark: M3 dark palette
    val PRIMARY: Int get() = if (isDark) 0xFF8BD98F.toInt() else 0xFF2E7D32.toInt()
    val ON_PRIMARY: Int get() = if (isDark) 0xFF00390A.toInt() else 0xFFFFFFFF.toInt()
    val PRIMARY_DEEP: Int get() = if (isDark) 0xFF9EE7A2.toInt() else 0xFF1B5E20.toInt()
    val PRIMARY_CONTAINER: Int get() = if (isDark) 0xFF1D5E24.toInt() else 0xFFC8E6C9.toInt()
    val ON_PRIMARY_CONTAINER: Int get() = if (isDark) 0xFFB7F0B9.toInt() else 0xFF002106.toInt()
    val SECONDARY_CONTAINER: Int get() = if (isDark) 0xFF35412F.toInt() else 0xFFDCE8D8.toInt()
    val ON_SECONDARY_CONTAINER: Int get() = if (isDark) 0xFFDCE8D8.toInt() else 0xFF20241E.toInt()
    val SURFACE: Int get() = if (isDark) 0xFF12140F.toInt() else 0xFFF7FBF4.toInt()
    val SURFACE_CONTAINER_LOW: Int get() = if (isDark) 0xFF1A1D17.toInt() else 0xFFF1F5EE.toInt()
    val SURFACE_CONTAINER: Int get() = if (isDark) 0xFF1E211B.toInt() else 0xFFE9EEE7.toInt()
    val ON_SURFACE: Int get() = if (isDark) 0xFFE2E4DD.toInt() else 0xFF191C20.toInt()
    val ON_SURFACE_VARIANT: Int get() = if (isDark) 0xFFC3C9BF.toInt() else 0xFF44474E.toInt()
    val OUTLINE: Int get() = if (isDark) 0xFF8D9389.toInt() else 0xFF737A71.toInt()
    val OUTLINE_VARIANT: Int get() = if (isDark) 0xFF42483F.toInt() else 0xFFC3CAC1.toInt()
    val ERROR: Int get() = if (isDark) 0xFFFFB4AB.toInt() else 0xFFBA1A1A.toInt()
    val OK: Int get() = if (isDark) 0xFF8BD98F.toInt() else 0xFF2E7D32.toInt()

    // ── 兼容别名（旧调用点）──
    val BG: Int get() = SURFACE
    val CARD: Int get() = SURFACE_CONTAINER_LOW
    val TEXT: Int get() = ON_SURFACE
    val TEXT_SUB: Int get() = ON_SURFACE_VARIANT
    val DIVIDER: Int get() = OUTLINE_VARIANT
    val PRIMARY_LIGHT: Int get() = PRIMARY_CONTAINER
    const val RADIUS_SM = 12f   // 小圆角（宫格/设备项等卡片类用 medium 12dp）

    // ── M3 Shape 刻度 ──
    private const val SHAPE_SMALL = 8f     // 输入框
    private const val SHAPE_MEDIUM = 12f   // 卡片
    private const val SHAPE_LARGE = 28f    // 对话框/大容器
    private const val SHAPE_FULL = 999f    // 按钮/胶囊

    /** 标题栏渐变（浅/深各一组绿渐变） */
    private fun headerGradient(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        if (isDark) intArrayOf(0xFF1D5E24.toInt(), 0xFF2E7D32.toInt())
        else intArrayOf(0xFF2E7D32.toInt(), 0xFF4CAF50.toInt())
    ).apply { cornerRadius = SHAPE_LARGE }

    // ── 圆角背景工具 ──
    fun rounded(color: Int, radius: Float = SHAPE_MEDIUM, strokeColor: Int? = null, strokeW: Int = 1): GradientDrawable =
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

    /** 顶部标题栏：品牌绿渐变 + 大圆角 */
    fun header(text: String): LinearLayout = LinearLayout(Utils.appContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(16))
        background = headerGradient()
        addView(TextView(Utils.appContext()).apply {
            this.text = text
            textSize = 20f
            setTextColor(if (isDark) 0xFFB7F0B9.toInt() else Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
    }

    /**
     * 卡片：M3 tonal surface 层级（surface-container-low 色 + medium 圆角），
     * **不用阴影**——M3 用色调层级表达深度。
     */
    fun card(): LinearLayout = LinearLayout(Utils.appContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(SURFACE_CONTAINER_LOW, SHAPE_MEDIUM)
    }

    fun card(container: (LinearLayout.() -> Unit)): LinearLayout = card().also { it.container() }

    // ── 文字（M3 typescale 简化）──
    /** 小节标题：primary-container 胶囊标签 */
    fun sectionTitle(text: String): TextView = TextView(Utils.appContext()).apply {
        this.text = text
        textSize = 15f
        setTextColor(ON_SURFACE)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = rounded(PRIMARY_CONTAINER, SHAPE_MEDIUM)
    }

    fun caption(text: String): TextView = TextView(Utils.appContext()).apply {
        this.text = text
        textSize = 12f
        setTextColor(ON_SURFACE_VARIANT)
    }

    /** 标签（表单字段名）：M3 label-large */
    fun label(text: String): TextView = TextView(Utils.appContext()).apply {
        this.text = text
        textSize = 13f
        setTextColor(ON_SURFACE_VARIANT)
        setPadding(0, dp(8), 0, dp(4))
    }

    // ── 按钮（M3：filled / outlined / tonal）──
    /** Filled 按钮：primary 底 + on-primary 字 + 全胶囊（M3 高强调） */
    fun primaryButton(text: String): Button = Button(Utils.appContext()).apply {
        this.text = text
        setTextColor(ON_PRIMARY)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        minHeight = dp(48)
        setPadding(dp(20), 0, dp(20), 0)
        background = pressable(
            rounded(PRIMARY, SHAPE_FULL),
            rounded(PRIMARY_DEEP, SHAPE_FULL),
        )
    }

    /** Outlined 按钮：surface 底 + outline 描边 + primary 字 + 全胶囊（M3 中强调） */
    fun outlineButton(text: String): Button = Button(Utils.appContext()).apply {
        this.text = text
        setTextColor(PRIMARY)
        textSize = 13.5f
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        minHeight = dp(40)
        setPadding(dp(16), 0, dp(16), 0)
        background = pressable(
            rounded(SURFACE, SHAPE_FULL, OUTLINE, dp(1)),
            rounded(PRIMARY_CONTAINER, SHAPE_FULL, PRIMARY, dp(1)),
        )
    }

    /** Tonal 按钮：secondary-container 底（M3 低强调） */
    fun ghostButton(text: String): Button = Button(Utils.appContext()).apply {
        this.text = text
        setTextColor(ON_SECONDARY_CONTAINER)
        textSize = 13.5f
        isAllCaps = false
        minHeight = dp(40)
        setPadding(dp(16), 0, dp(16), 0)
        background = pressable(
            rounded(SECONDARY_CONTAINER, SHAPE_FULL),
            rounded(if (isDark) 0xFF40503A.toInt() else 0xFFD3D6E0.toInt(), SHAPE_FULL),
        )
    }

    // ── 分段控件（M3 Segmented Button：容器 surface-container + 全胶囊）──
    /**
     * 分段选择器。选中态背景 = primary-container（浅绿）+ primary 文字；
     * 未选 = 容器色 + variant 文字。用 checked 状态的 StateListDrawable，
     * 选中后**持续高亮**（2026-08-11 用户反馈"选中不选中一个色"已修）。
     */
    fun segmentGroup(items: List<Pair<String, Any?>>, defaultIndex: Int = 0, onChange: ((Int) -> Unit)? = null): RadioGroup =
        RadioGroup(Utils.appContext()).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
            background = rounded(SURFACE_CONTAINER, SHAPE_FULL)
            items.forEachIndexed { i, (label, value) ->
                val rb = RadioButton(Utils.appContext()).apply {
                    text = label
                    textSize = 13f
                    gravity = Gravity.CENTER
                    isAllCaps = false
                    minHeight = dp(36)
                    // checked 态持续高亮（浅绿底），pressed 同色，未选容器色
                    background = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_checked), rounded(PRIMARY_CONTAINER, SHAPE_FULL))
                        addState(intArrayOf(android.R.attr.state_pressed), rounded(PRIMARY_CONTAINER, SHAPE_FULL))
                        addState(intArrayOf(), rounded(SURFACE_CONTAINER, SHAPE_FULL))
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

    // ── 输入框（M3 Outlined Text Field：surface 底 + outline 描边 + small 圆角）──
    fun input(hint: String, lines: Int = 1): EditText = EditText(Utils.appContext()).apply {
        this.hint = hint
        setHintTextColor(OUTLINE)
        setTextColor(ON_SURFACE)
        textSize = 14f
        minHeight = dp(48)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = rounded(SURFACE, SHAPE_SMALL, OUTLINE, dp(1))
        if (lines > 1) {
            minLines = lines
            gravity = Gravity.TOP or Gravity.START
        }
    }

    /** 复选框 */
    fun check(text: String): android.widget.CheckBox = android.widget.CheckBox(Utils.appContext()).apply {
        this.text = text
        textSize = 13f
        setTextColor(ON_SURFACE)
    }

    // ── Material Symbols 图标（官方矢量字体，assets/fonts/）──
    // 图标 codepoint（与 Material Icons 同一套）：
    // home E88A / print E8AD / image E3F4 / edit_note E757 / person E7FD / book E865
    // calendar_month EBCC / checklist E6B2 / schedule E8B5 / menu_book EA18 / school E80C
    // settings E8B8 / build E869 / check_circle E86C / list_alt E0EE
    object Icons {
        const val HOME = ""
        const val PRINT = ""
        const val IMAGE = ""
        const val EDIT_NOTE = ""
        const val PERSON = ""
        const val BOOK = ""
        const val CALENDAR = ""
        const val CHECKLIST = ""
        const val SCHEDULE = ""
        const val MENU_BOOK = ""
        const val SCHOOL = ""
        const val SETTINGS = ""
        const val BUILD = ""
        const val CHECK_CIRCLE = ""
        const val LIST_ALT = ""
        const val HISTORY = ""
        const val CLEAR = ""
        const val REFRESH = ""
        const val PREVIEW = ""
        const val BOLT = ""

        @Volatile
        private var iconTypeface: Typeface? = null

        /** 图标字体（懒加载，复用单例） */
        fun typeface(): Typeface = iconTypeface ?: runCatching {
            Typeface.createFromAsset(Utils.appContext().assets, "fonts/MaterialSymbolsOutlined.ttf")
        }.getOrNull()?.also { iconTypeface = it } ?: Typeface.DEFAULT

        @Volatile
        private var filledTypeface: Typeface? = null

        /**
         * Sharp 填充变体：Material Symbols Sharp 风格（直角利落）+ FILL=1 + wght 500，
         * 比 Outlined 更精致醒目（2026-08-11 用户选定 sharp 风格）。
         */
        fun filledTypeface(): Typeface = filledTypeface ?: runCatching {
            Typeface.Builder(Utils.appContext().assets, "fonts/MaterialSymbolsSharp.ttf")
                .setFontVariationSettings("FILL 1,GRAD 0,opsz 48,wght 500")
                .build()
        }.getOrNull()?.also { filledTypeface = it } ?: typeface()

        /** 填充图标 TextView（指定字符 + 尺寸 + 颜色） */
        fun textViewFilled(code: String, size: Int, color: Int): TextView = TextView(Utils.appContext()).apply {
            text = code
            typeface = filledTypeface()
            textSize = size.toFloat()
            setTextColor(color)
            gravity = Gravity.CENTER
        }

        /** 图标 TextView（outline 变体） */
        fun textView(code: String, size: Int, color: Int): TextView = TextView(Utils.appContext()).apply {
            text = code
            typeface = typeface()
            textSize = size.toFloat()
            setTextColor(color)
            gravity = Gravity.CENTER
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
