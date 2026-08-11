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

    // ── 微信小程序风 Color Scheme ──
    // Light: 微信灰底白卡 + 微信绿 | Dark: 微信深色风
    val PRIMARY: Int get() = 0xFF07C160.toInt()          // 微信绿（深浅通用）
    val ON_PRIMARY: Int get() = 0xFFFFFFFF.toInt()
    val PRIMARY_DEEP: Int get() = 0xFF06AD56.toInt()     // 按下态
    val PRIMARY_CONTAINER: Int get() = if (isDark) 0xFF1F3D2C.toInt() else 0xFFE8F8EE.toInt()  // 浅绿选中底
    val ON_PRIMARY_CONTAINER: Int get() = if (isDark) 0xFF8BE8B4.toInt() else 0xFF07C160.toInt()
    val SECONDARY_CONTAINER: Int get() = if (isDark) 0xFF262626.toInt() else 0xFFF2F3F5.toInt() // 浅灰底
    val ON_SECONDARY_CONTAINER: Int get() = if (isDark) 0xFFE5E5E5.toInt() else 0xFF191919.toInt()
    val SURFACE: Int get() = if (isDark) 0xFF111111.toInt() else 0xFFF7F7F7.toInt()             // 页面灰底
    val SURFACE_CONTAINER_LOW: Int get() = if (isDark) 0xFF1E1E1E.toInt() else 0xFFFFFFFF.toInt() // 白卡
    val SURFACE_CONTAINER: Int get() = if (isDark) 0xFF2A2A2A.toInt() else 0xFFF2F3F5.toInt()   // 分段/输入底
    val ON_SURFACE: Int get() = if (isDark) 0xFFE5E5E5.toInt() else 0xFF191919.toInt()          // 主文字
    val ON_SURFACE_VARIANT: Int get() = if (isDark) 0xFF9A9A9A.toInt() else 0xFF888888.toInt()  // 次文字
    val OUTLINE: Int get() = if (isDark) 0xFF3A3A3A.toInt() else 0xFFDADADA.toInt()             // 描边
    val OUTLINE_VARIANT: Int get() = if (isDark) 0xFF2A2A2A.toInt() else 0xFFEBEDF0.toInt()     // 分隔线
    val ERROR: Int get() = 0xFFFA5151.toInt()
    val OK: Int get() = 0xFF07C160.toInt()

    // ── 兼容别名（旧调用点）──
    val BG: Int get() = SURFACE
    val CARD: Int get() = SURFACE_CONTAINER_LOW
    val TEXT: Int get() = ON_SURFACE
    val TEXT_SUB: Int get() = ON_SURFACE_VARIANT
    val DIVIDER: Int get() = OUTLINE_VARIANT
    val PRIMARY_LIGHT: Int get() = PRIMARY_CONTAINER
    const val RADIUS_SM = 8f   // 微信小圆角（卡片/按钮/宫格）

    // ── Shape 刻度 ──
    private const val SHAPE_SMALL = 8f     // 卡片/按钮/输入框
    private const val SHAPE_MEDIUM = 12f   // 大容器
    private const val SHAPE_LARGE = 28f    // 对话框
    private const val SHAPE_FULL = 999f    // 兼容旧胶囊调用（segmentGroup 等仍可胶囊）

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
    /** 主按钮：微信绿底白字 + 8px 圆角 */
    fun primaryButton(text: String): Button = Button(Utils.appContext()).apply {
        this.text = text
        setTextColor(ON_PRIMARY)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        minHeight = dp(44)
        setPadding(dp(20), 0, dp(20), 0)
        background = pressable(
            rounded(PRIMARY, SHAPE_SMALL),
            rounded(PRIMARY_DEEP, SHAPE_SMALL),
        )
    }

    /** 次按钮：白底 + 灰描边 + 绿字 + 8px 圆角 */
    fun outlineButton(text: String): Button = Button(Utils.appContext()).apply {
        this.text = text
        setTextColor(PRIMARY)
        textSize = 13.5f
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        minHeight = dp(40)
        setPadding(dp(16), 0, dp(16), 0)
        background = pressable(
            rounded(SURFACE_CONTAINER_LOW, SHAPE_SMALL, OUTLINE, dp(1)),
            rounded(PRIMARY_CONTAINER, SHAPE_SMALL, PRIMARY, dp(1)),
        )
    }

    /** 轻按钮：浅灰底 + 深灰字 + 8px 圆角（微信小程序常用） */
    fun ghostButton(text: String): Button = Button(Utils.appContext()).apply {
        this.text = text
        setTextColor(ON_SECONDARY_CONTAINER)
        textSize = 13.5f
        isAllCaps = false
        minHeight = dp(40)
        setPadding(dp(16), 0, dp(16), 0)
        background = pressable(
            rounded(SECONDARY_CONTAINER, SHAPE_SMALL),
            rounded(if (isDark) 0xFF333333.toInt() else 0xFFE0E0E0.toInt(), SHAPE_SMALL),
        )
    }

    // ── 分段控件（微信 segmented：白底灰边 + 选中浅绿底绿字）──
    fun segmentGroup(items: List<Pair<String, Any?>>, defaultIndex: Int = 0, onChange: ((Int) -> Unit)? = null): RadioGroup =
        RadioGroup(Utils.appContext()).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
            background = rounded(SURFACE_CONTAINER_LOW, SHAPE_SMALL, OUTLINE, dp(1))
            items.forEachIndexed { i, (label, value) ->
                val rb = RadioButton(Utils.appContext()).apply {
                    text = label
                    textSize = 13f
                    gravity = Gravity.CENTER
                    isAllCaps = false
                    minHeight = dp(34)
                    // checked 态持续高亮（浅绿底绿字），未选白底灰字
                    background = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_checked), rounded(PRIMARY_CONTAINER, SHAPE_SMALL))
                        addState(intArrayOf(android.R.attr.state_pressed), rounded(PRIMARY_CONTAINER, SHAPE_SMALL))
                        addState(intArrayOf(), rounded(SURFACE_CONTAINER_LOW, SHAPE_SMALL))
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
