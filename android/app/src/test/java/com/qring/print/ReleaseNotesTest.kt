package com.qring.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新说明测试（2026-08-17 加）：版本数字分段比较 + 版本区间说明收集。
 * 纯 Kotlin 零 Android 依赖，JVM 直跑。
 */
class ReleaseNotesTest {

    @Test
    fun `版本比较 数字分段`() {
        assertTrue("0.7.0 > 0.6.9", ReleaseNotes.isNewer("0.7.0", "0.6.9"))
        assertTrue("0.6.10 > 0.6.9（数字分段非字符串）", ReleaseNotes.isNewer("0.6.10", "0.6.9"))
        assertTrue("1.0.0 > 0.9.9", ReleaseNotes.isNewer("1.0.0", "0.9.9"))
        assertFalse("0.6.9 < 0.6.10", ReleaseNotes.isNewer("0.6.9", "0.6.10"))
        assertFalse("相等不等", ReleaseNotes.isNewer("0.7.0", "0.7.0"))
        assertFalse("旧版本不比新版本新", ReleaseNotes.isNewer("0.5.0", "0.6.0"))
    }

    @Test
    fun `说明收集 当前已最新返回 null`() {
        // 用日志首条而非字面量：发版新增条目时本用例不必手改，仍然检查"已最新 → 不弹说明"
        assertNull("与日志最新版本相同应无说明", ReleaseNotes.notesSince(ReleaseNotes.latestVersion))
    }

    @Test
    fun `说明收集 升级一个版本只含该版本`() {
        val notes = ReleaseNotes.notesSince("0.6.3") ?: error("0.6.3 后有更新，不应为空")
        assertTrue("应包含 0.7.3 说明", notes.contains("【0.7.3】"))
        assertTrue("应包含 0.7.2 说明", notes.contains("【0.7.2】"))
        assertTrue("应包含 0.7.1 说明", notes.contains("【0.7.1】"))
        assertTrue("应包含 0.7.0 说明", notes.contains("【0.7.0】"))
        assertFalse("不应包含 0.6.3 自身", notes.contains("【0.6.3】"))
        assertFalse("不应包含更旧版本", notes.contains("【0.6.2】"))
    }

    @Test
    fun `说明收集 跳多版本全部列出且新版本在前`() {
        val notes = ReleaseNotes.notesSince("0.5.5") ?: error("0.5.5 后有更新，不应为空")
        val i073 = notes.indexOf("【0.7.3】")
        val i072 = notes.indexOf("【0.7.2】")
        val i071 = notes.indexOf("【0.7.1】")
        val i070 = notes.indexOf("【0.7.0】")
        val i060 = notes.indexOf("【0.6.0】")
        assertTrue("应含 0.7.3", i073 >= 0)
        assertTrue("应含 0.7.2", i072 >= 0)
        assertTrue("应含 0.7.1", i071 >= 0)
        assertTrue("应含 0.7.0", i070 >= 0)
        assertTrue("应含 0.6.0（跳版本也列出）", i060 >= 0)
        assertTrue("0.7.3 在 0.7.2 前", i073 < i072)
        assertTrue("0.7.2 在 0.7.1 前", i072 < i071)
        assertTrue("0.7.1 在 0.7.0 前", i071 < i070)
        assertTrue("新版本在前", i070 < i060)
    }

    @Test
    fun `发版一致性 版本号三处必须一致`() {
        // 守卫"发版漏同步"（2026-09-28 加）：build.gradle 的 versionName、ReleaseNotes 最新条目、
        // 仓库根 version.json（OTA 读取的）三处必须同一个号——任一处漏改都会让用户看到错误版本/收不到更新。
        // 路径 android/app → ../../version.json
        val vj = java.io.File("../../version.json")
        assertTrue("找不到仓库根 version.json（测试工作目录=${java.io.File(".").absolutePath}）", vj.exists())
        val manifestVersion = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(vj.readText())?.groupValues?.get(1)
        assertEquals("version.json 应与 ReleaseNotes 最新条目一致", ReleaseNotes.latestVersion, manifestVersion)

        // App 运行时版本号取自 PackageManager（= build.gradle.kts 的 versionName，见 MainActivity 动态读版本），
        // 工程未开 buildConfig，故这里直接读构建脚本文本作为第三处来源
        val gradle = java.io.File("build.gradle.kts")
        assertTrue("找不到 app/build.gradle.kts", gradle.exists())
        val gradleVersion = Regex("versionName\\s*=\\s*\"([^\"]+)\"").find(gradle.readText())?.groupValues?.get(1)
        assertEquals("build.gradle versionName 应与 version.json 一致", manifestVersion, gradleVersion)
    }

    @Test
    fun `说明收集 低于日志最低版本列出全部`() {
        val notes = ReleaseNotes.notesSince("0.5.4") ?: error("0.5.4 后有更新，不应为空")
        assertTrue("含最低 0.5.5", notes.contains("【0.5.5】"))
        assertTrue("含最高 ${ReleaseNotes.latestVersion}", notes.contains("【${ReleaseNotes.latestVersion}】"))
        assertTrue("含 0.7.2", notes.contains("【0.7.2】"))
        assertTrue("含 0.7.1", notes.contains("【0.7.1】"))
        assertTrue("含 0.7.0", notes.contains("【0.7.0】"))
        // 断言"输出条目数 == 日志条目数"（而非写死的数字）：既是完整性检查，发版也不必手改
        assertEquals("全部 ${ReleaseNotes.logSize} 条", ReleaseNotes.logSize, notes.split("【").size - 1)
    }
}
