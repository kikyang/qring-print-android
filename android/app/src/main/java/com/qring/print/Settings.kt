package com.qring.print

import android.content.Context
import android.content.SharedPreferences

/**
 * 打印设置持久化（2026-08-11 加，参考 QrintPrint-Windows 的可调设置）：
 * 浓度（X1 合法 0~2）、进纸点数、出纸点数。SharedPreferences 存储。
 */
object Settings {

    private const val NAME = "print_settings"
    private const val KEY_THICKNESS = "thickness"
    private const val KEY_FEED_BEFORE = "feed_before"
    private const val KEY_FEED_AFTER = "feed_after"
    private const val KEY_CONNECTION_MODE = "connection_mode"
    private const val KEY_THRESHOLD = "threshold"
    private const val KEY_OUTLINE_METHOD = "outline_method"
    private const val KEY_OUTLINE_SENSITIVITY = "outline_sensitivity"
    private const val KEY_OUTLINE_THICKNESS = "outline_thickness"
    private const val KEY_OUTLINE_SMOOTH = "outline_smooth"
    private const val KEY_OUTLINE_INVERT = "outline_invert"
    private const val KEY_BARCODE_TYPE = "barcode_type"
    private const val KEY_LAST_SEEN_VERSION = "last_seen_version"
    private const val KEY_UI_THEME = "ui_theme"

    /** X1 浓度合法范围 0~2（实测 3/4 报 ER） */
    const val THICKNESS_MIN = 0
    const val THICKNESS_MAX = 2
    /** ESC J 单字节走纸上限 */
    const val FEED_MAX = 255

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        }
    }

    private fun p(): SharedPreferences = checkNotNull(prefs) { "Settings.init(context) 必须先调用" }

    var thickness: Int
        get() = p().getInt(KEY_THICKNESS, BlePrinterConnection.DEFAULT_THICKNESS)
            .coerceIn(THICKNESS_MIN, THICKNESS_MAX)
        set(v) = p().edit().putInt(KEY_THICKNESS, v.coerceIn(THICKNESS_MIN, THICKNESS_MAX)).apply()

    var feedBefore: Int
        get() = p().getInt(KEY_FEED_BEFORE, 10).coerceIn(0, FEED_MAX)
        set(v) = p().edit().putInt(KEY_FEED_BEFORE, v.coerceIn(0, FEED_MAX)).apply()

    var feedAfter: Int
        get() = p().getInt(KEY_FEED_AFTER, 100).coerceIn(0, FEED_MAX)
        set(v) = p().edit().putInt(KEY_FEED_AFTER, v.coerceIn(0, FEED_MAX)).apply()

    /**
     * 连接通道（2026-08-11 加，X1 存在多版本：透传版走 BLE、经典版走 SPP）。
     * 2026-08-13 用户定案：**固定 SPP 直连，不用 AUTO**（AUTO 会在 SPP 失败时静默
     * 回退 BLE，用户无感知但打印慢、墨色淡）。BLE 仅调试台手动连接用。
     * 旧数据无 key / 存过 AUTO 时兜底 SPP。
     */
    var connectionMode: ConnectionMode
        get() = runCatching { ConnectionMode.valueOf(p().getString(KEY_CONNECTION_MODE, null) ?: "SPP") }
            .getOrDefault(ConnectionMode.SPP)
        set(v) = p().edit().putString(KEY_CONNECTION_MODE, v.name).apply()

    /**
     * 二值化阈值（2026-08-11 加）：黑白化阶段，独立于打印浓度（浓度管"黑得多黑"，
     * 阈值管"哪些算黑"）。仅 NONE 模式生效，抖动模式恒用中点 128。
     */
    var threshold: Int
        get() = p().getInt(KEY_THRESHOLD, RasterEncoder.THRESHOLD_IMAGE).coerceIn(0, 255)
        set(v) = p().edit().putInt(KEY_THRESHOLD, v.coerceIn(0, 255)).apply()

    // ── 描边参数（xyprt 移植 2026-08-11，默认值同 xyprt）──

    var outlineMethod: OutlineMethod
        get() = runCatching { OutlineMethod.valueOf(p().getString(KEY_OUTLINE_METHOD, null) ?: "CANNY") }
            .getOrDefault(OutlineMethod.CANNY)
        set(v) = p().edit().putString(KEY_OUTLINE_METHOD, v.name).apply()

    var outlineSensitivity: Int
        get() = p().getInt(KEY_OUTLINE_SENSITIVITY, 88).coerceIn(0, 100)
        set(v) = p().edit().putInt(KEY_OUTLINE_SENSITIVITY, v.coerceIn(0, 100)).apply()

    var outlineThickness: Int
        get() = p().getInt(KEY_OUTLINE_THICKNESS, 1).coerceIn(1, 3)
        set(v) = p().edit().putInt(KEY_OUTLINE_THICKNESS, v.coerceIn(1, 3)).apply()

    var outlineSmooth: Boolean
        get() = p().getBoolean(KEY_OUTLINE_SMOOTH, false)
        set(v) = p().edit().putBoolean(KEY_OUTLINE_SMOOTH, v).apply()

    var outlineInvert: Boolean
        get() = p().getBoolean(KEY_OUTLINE_INVERT, false)
        set(v) = p().edit().putBoolean(KEY_OUTLINE_INVERT, v).apply()

    // ── 内容页参数记忆（#5b：浓度全局；抖动/增强等按内容类型分存）──

    /** 上次使用的条码类型（format.name），下次打开条码页恢复 */
    var barcodeType: String
        get() = runCatching { p().getString(KEY_BARCODE_TYPE, null) ?: "QR_CODE" }
            .getOrDefault("QR_CODE")
        set(v) = p().edit().putString(KEY_BARCODE_TYPE, v).apply()

    /** 保存某内容类型（text/image/card）的编辑页状态快照（JSON，格式同历史 paramsJson） */
    fun saveContentPref(type: String, json: String) {
        p().edit().putString("content_pref_$type", json).apply()
    }

    /** 读取某内容类型的编辑页状态快照；无则 null（首启/旧版本） */
    fun loadContentPref(type: String): String? = p().getString("content_pref_$type", null)

    // ── UI 主题（2026-08-18 加）：微信风 / xyprt 简洁风 / 仿喵喵机蓝白风 ──

    var uiTheme: UiTheme
        get() = runCatching { UiTheme.valueOf(p().getString(KEY_UI_THEME, null) ?: "WECHAT") }
            .getOrDefault(UiTheme.WECHAT)
        set(v) = p().edit().putString(KEY_UI_THEME, v.name).apply()

    // ── 更新说明（2026-08-17 加）：上次运行版本 ──

    /**
     * 上次运行版本号。OTA 升级装好新包后 App 数据保留，下次启动据此判断是否弹「更新说明」。
     * "" = 从未记录（全新安装），首启直接写入当前版本、不弹窗。
     */
    var lastSeenVersion: String
        get() = p().getString(KEY_LAST_SEEN_VERSION, null) ?: ""
        set(v) = p().edit().putString(KEY_LAST_SEEN_VERSION, v).apply()
}

/** 连接通道选择 */
enum class ConnectionMode(val label: String) {
    AUTO("自动"),
    BLE("BLE"),
    SPP("经典蓝牙"),
}
