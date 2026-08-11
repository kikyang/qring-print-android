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
     * 默认 AUTO：先试 BLE 透传，空壳（无响应）自动回退经典蓝牙。
     * 旧数据无此 key 时 valueOf 抛异常，兜底 AUTO。
     */
    var connectionMode: ConnectionMode
        get() = runCatching { ConnectionMode.valueOf(p().getString(KEY_CONNECTION_MODE, null) ?: "AUTO") }
            .getOrDefault(ConnectionMode.AUTO)
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
}

/** 连接通道选择 */
enum class ConnectionMode(val label: String) {
    AUTO("自动"),
    BLE("BLE"),
    SPP("经典蓝牙"),
}
