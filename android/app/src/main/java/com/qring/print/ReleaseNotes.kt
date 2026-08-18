package com.qring.print

/**
 * 更新说明（2026-08-17 加）：OTA 更新装好后，下次启动弹出「更新说明」对话框。
 *
 * 数据源为内置更新日志 [LOG]——发版时在顶部新增当前版本条目，与仓库 version.json 的 notes 同步。
 * 判断流程：MainActivity 启动时比对上次运行版本（Settings.lastSeenVersion）与当前版本，
 * 有新版本则展示 [notesSince] 收集到的说明。纯 Kotlin 零依赖，便于 JVM 单测。
 */
object ReleaseNotes {

    // 版本 → 更新说明，新版本在前。发版时在顶部新增一条（与 version.json notes 保持一致）。
    private val LOG = listOf(
        "0.7.1" to "· OTA 升级后新增「更新说明」弹窗\n" +
            "· 支持跨版本收集更新日志（跳版本也能看到）",
        "0.7.0" to "· 图片增强升级：Sauvola / Wolf / Bradley 三种算法可选\n" +
            "· 增强强度可调（弱 / 标准 / 强）\n" +
            "· 一键增强链路升级（高分辨率光照补偿 + 二值化）",
        "0.6.3" to "· 修复 OTA 升级最后一步「没有权限安装」的问题（首次需授权「允许安装未知应用」）",
        "0.6.2" to "· 连接固定为经典蓝牙（SPP），修复条码 / 图片偏淡问题\n" +
            "· 已连接状态显示真实通道（SPP / BLE）",
        "0.6.1" to "· 修复 OTA 检查更新失败（补 INTERNET 权限）",
        "0.6.0" to "· 打印提速（SPP 块间延迟归零）\n" +
            "· 界面整合：首页 = 启动台，打印页 = 工作区\n" +
            "· 功能入口收敛 + 文案通俗化",
        "0.5.5" to "· 首发版：文字 / 图片 / 条码 / 错题卡 / 文档（PDF / Word / Excel）打印",
    )

    /** 数字分段版本比较：a > b？（0.6.10 > 0.6.9，忽略非数字段） */
    fun isNewer(a: String, b: String): Boolean {
        val x = a.split('.', '-').mapNotNull { it.toIntOrNull() }
        val y = b.split('.', '-').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(x.size, y.size)) {
            val xv = x.getOrElse(i) { 0 }
            val yv = y.getOrElse(i) { 0 }
            if (xv != yv) return xv > yv
        }
        return false
    }

    /**
     * 收集比 [fromVersion] 新的所有版本说明（含跳版本），新版本在前。
     * [fromVersion] 已是最新（或高于日志全部版本）返回 null。
     */
    fun notesSince(fromVersion: String): String? {
        val items = LOG.filter { isNewer(it.first, fromVersion) }
        if (items.isEmpty()) return null
        return items.joinToString("\n\n") { "【${it.first}】\n${it.second}" }
    }
}
