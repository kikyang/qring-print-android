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
}
