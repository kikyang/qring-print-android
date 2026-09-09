package com.qring.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 更新检查多源解析/择优测试（2026-09-09 加，#29）。
 *
 * 背景：jsDelivr data API 的版本列表索引会长期滞后（v0.7.5 发布 8 天后仍未收录、
 * v0.7.6 当天也未收录，而 @v{version} 显式路径一直正常）。旧实现「谁先成功用谁」且
 * data API 排第一 → 新版本被误判成「已是最新」。现改为各源都取、按版本号取最大。
 *
 * 解析用到 org.json（纯 JVM 单测里是 Android stub 会抛异常）→ 走 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateManagerTest {

    private fun candidate(v: String, notes: String = "", url: String = "u-$v") =
        UpdateManager.Candidate(v, notes, url)

    // ── pickNewest：跨源取最高 ────────────────────────────────────────

    @Test
    fun `跨源取最高版本`() {
        // data API 滞后在 0.7.4，@main/version.json 已是 0.7.6 → 必须选 0.7.6
        val best = UpdateManager.pickNewest(
            listOf(
                candidate("0.7.4", "", "data-api-url"),
                candidate("0.7.6", "说明", "version-json-url"),
                candidate("0.7.2"),
            )
        )
        assertEquals("0.7.6", best?.version)
        assertEquals("说明", best?.notes)
        assertEquals("version-json-url", best?.apkUrl)
    }

    @Test
    fun `候选顺序不影响结果`() {
        val asc = UpdateManager.pickNewest(listOf(candidate("0.7.4"), candidate("0.7.6"), candidate("0.7.5")))
        val desc = UpdateManager.pickNewest(listOf(candidate("0.7.6"), candidate("0.7.5"), candidate("0.7.4")))
        assertEquals("0.7.6", asc?.version)
        assertEquals("0.7.6", desc?.version)
    }

    @Test
    fun `空候选与空版本号被忽略`() {
        assertNull(UpdateManager.pickNewest(emptyList()))
        assertNull(UpdateManager.pickNewest(listOf(candidate(""), candidate("   "))))
        // 空版本混在里面时，仍能挑出有效项
        assertEquals("0.6.3", UpdateManager.pickNewest(listOf(candidate(""), candidate("0.6.3")))?.version)
    }

    @Test
    fun `两位数段比较不是字符串比较`() {
        // 0.7.10 > 0.7.9（字符串比较会判错）
        assertEquals("0.7.10", UpdateManager.pickNewest(listOf(candidate("0.7.9"), candidate("0.7.10")))?.version)
    }

    // ── 三个源的解析 ──────────────────────────────────────────────────

    @Test
    fun `解析 data API 取列表最新一条`() {
        val body = """{"type":"gh","name":"kikyang/qring-print-android",
            "versions":[{"version":"0.7.4"},{"version":"0.7.3"}]}"""
        val c = UpdateManager.parseDataApi(body)
        assertEquals("0.7.4", c?.version)
        assertEquals(
            "https://cdn.jsdelivr.net/gh/kikyang/qring-print-android@v0.7.4/releases/app-release.apk",
            c?.apkUrl,
        )
    }

    @Test
    fun `解析 versionJson 带说明与 tag 下载地址`() {
        val body = """{"version":"0.7.6","notes":"v0.7.6：主题 + 修复"}"""
        val c = UpdateManager.parseVersionJson(body)
        assertEquals("0.7.6", c?.version)
        assertEquals("v0.7.6：主题 + 修复", c?.notes)
        assertEquals(
            "https://cdn.jsdelivr.net/gh/kikyang/qring-print-android@v0.7.6/releases/app-release.apk",
            c?.apkUrl,
        )
    }

    @Test
    fun `解析 GitHub latest 用资产下载地址`() {
        val body = """{"tag_name":"v0.7.6","body":"说明","assets":[
            {"name":"notes.txt","browser_download_url":"https://example.com/n.txt"},
            {"name":"app-release.apk","browser_download_url":"https://example.com/app.apk"}]}"""
        val c = UpdateManager.parseGithubLatest(body)
        assertEquals("0.7.6", c?.version)
        assertEquals("说明", c?.notes)
        assertEquals("https://example.com/app.apk", c?.apkUrl)
    }

    @Test
    fun `GitHub 无 apk 资产时回退到 tag 路径`() {
        val c = UpdateManager.parseGithubLatest("""{"tag_name":"v0.7.6","assets":[]}""")
        assertTrue("应回退到 jsDelivr tag 路径", c?.apkUrl?.contains("@v0.7.6/releases/app-release.apk") == true)
    }

    @Test
    fun `坏 JSON 或缺字段返回 null`() {
        assertNull(UpdateManager.parseDataApi("not json"))
        assertNull(UpdateManager.parseVersionJson("""{"notes":"没有 version"}"""))
        assertNull(UpdateManager.parseGithubLatest("""{"body":"没有 tag"}"""))
        assertNull(UpdateManager.parseDataApi("""{"versions":[]}"""))
    }
}
