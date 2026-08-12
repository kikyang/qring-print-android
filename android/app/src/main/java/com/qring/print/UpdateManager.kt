package com.qring.print

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases OTA 更新检查（2026-08-12 移植自 lztttt/QrintPrint-Android）。
 *
 * 流程：api.github.com/releases/latest 查版本 → 数字分段比较 → 有新版提示 →
 * 下载 APK（手动跟随重定向，GitHub 会 302 到 objects.githubusercontent.com）→
 * FileProvider 触发系统安装。入口：「我的 → 关于 → 检查更新」。
 *
 * 注意：GitHub 直连在国内网络时通时不通，所有失败均降级为 Toast 提示，不阻塞使用。
 */
object UpdateManager {

    private const val GITHUB_OWNER = "kikyang"
    private const val GITHUB_REPO = "qring-print-android"
    private const val API_BASE = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO"

    // 2026-08-12：国内手机直连 api.github.com 不通（桌面有代理可用，App 没有）→
    // 检查改走 jsDelivr data API（实时查 tag，国内可达），下载走 jsDelivr CDN @tag 路径
    // （发版时 APK 需提交进仓库 releases/ 目录）。GitHub API 仅作 fallback。
    private const val JSDELIVR_DATA_URL = "https://data.jsdelivr.com/v1/packages/gh/$GITHUB_OWNER/$GITHUB_REPO"
    private const val JSDELIVR_CDN_BASE = "https://cdn.jsdelivr.net/gh/$GITHUB_OWNER/$GITHUB_REPO@v"
    private const val APK_REPO_PATH = "releases/app-release.apk"

    private const val CHECK_TIMEOUT_MS = 15_000
    private const val DOWNLOAD_TIMEOUT_MS = 120_000

    interface Listener {
        /** 有新版（version 为 tag 去 v 前缀后的版本号） */
        fun onUpdateAvailable(version: String, notes: String, url: String)

        /** 无新版 / 检查失败（message 为失败原因，可空表示正常流程） */
        fun onResult(message: String?)
    }

    /**
     * 检查更新（调用方需已在后台线程/协程作用域）。
     * [activity] 用于下载完成后的安装 Intent；下载进度直接走 AlertDialog 进度条由调用方维护。
     */
    fun check(scope: CoroutineScope, activity: Activity, listener: Listener) {
        scope.launch {
            val current = runCatching {
                activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
            }.getOrDefault("0.0.0")
            // jsDelivr data API 优先（国内可达、实时 tag 列表），失败回退 GitHub API
            val info = withContext(Dispatchers.IO) {
                runCatching { httpGet(JSDELIVR_DATA_URL) }.getOrNull()
                    ?: runCatching { httpGet("$API_BASE/releases/latest") }.getOrNull()
                // 两个源都失败 → null → 网络异常提示
            } ?: run {
                withContext(Dispatchers.Main) {
                    listener.onResult("检查更新失败：网络异常（jsDelivr 与 GitHub 均不可达）")
                }
                return@launch
            }

            runCatching {
                val obj = JSONObject(info)
                // jsDelivr data API：{"versions":[{"version":"0.5.1"},...]}（最新在前，无 v 前缀）
                val jsdVersion = obj.optJSONArray("versions")?.optJSONObject(0)?.optString("version")
                if (!jsdVersion.isNullOrEmpty()) {
                    // 下载走 jsDelivr CDN @tag 路径（发版时 APK 已提交进仓库 releases/）
                    Triple(jsdVersion.removePrefix("v"), "", "$JSDELIVR_CDN_BASE$jsdVersion/$APK_REPO_PATH")
                } else {
                    // GitHub API：tag_name / body / assets.browser_download_url
                    val tag = obj.optString("tag_name").removePrefix("v")
                    val notes = obj.optString("body", "").trim().take(500)
                    val asset = obj.optJSONArray("assets")?.let { arr ->
                        (0 until arr.length()).firstNotNullOfOrNull { i ->
                            val a = arr.optJSONObject(i)
                            if (a?.optString("name")?.endsWith(".apk") == true) a else null
                        }
                    }
                    val url = asset?.optString("browser_download_url").orEmpty()
                    Triple(tag, notes, url)
                }
            }.onSuccess { (tag, notes, url) ->
                if (isNewer(tag, current)) {
                    withContext(Dispatchers.Main) { listener.onUpdateAvailable(tag, notes, url) }
                } else {
                    withContext(Dispatchers.Main) { listener.onResult(null) }
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    listener.onResult("解析版本信息失败：${e.message}")
                }
            }
        }
    }

    /** GET 文本（15s 超时） */
    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CHECK_TIMEOUT_MS
            readTimeout = CHECK_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "QringPrint")
        }
        try {
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 下载 APK 并触发系统安装。
     * [onProgress] 主线程回调 0..100；完成安装后调 [onDone]。
     */
    fun downloadAndInstall(
        scope: CoroutineScope,
        activity: Activity,
        version: String,
        url: String,
        onProgress: (Int) -> Unit,
        onDone: (Boolean) -> Unit,
    ) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val apk = File(dir, "qring-$version.apk")
                    download(url, apk) { p ->
                        // download 回调在 IO 线程（非挂起上下文），进度切主线程用 runOnUiThread
                        if (p % 5 == 0 || p >= 100) {
                            activity.runOnUiThread { onProgress(p) }
                        }
                    }
                    apk
                }.getOrNull()
            }
            if (ok != null) {
                withContext(Dispatchers.Main) {
                    try {
                        val uri = FileProvider.getUriForFile(
                            activity, "${activity.packageName}.fileprovider", ok
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        activity.startActivity(intent)
                        onDone(true)
                    } catch (e: Exception) {
                        // 没有可处理 package-archive 的 Activity（少见）→ 提示手动打开
                        Toast.makeText(activity, "安装失败：${e.message}", Toast.LENGTH_LONG).show()
                        onDone(false)
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "下载失败，请稍后重试", Toast.LENGTH_LONG).show()
                    onDone(false)
                }
            }
        }
    }

    /** 下载并跟随重定向（GitHub 下载 302 → objects.githubusercontent.com） */
    private fun download(urlStr: String, dest: File, onProgress: (Int) -> Unit) {
        var currentUrl = urlStr
        var redirects = 0
        while (redirects < 5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = DOWNLOAD_TIMEOUT_MS
                readTimeout = DOWNLOAD_TIMEOUT_MS
                setRequestProperty("User-Agent", "QringPrint/$GITHUB_REPO")
                instanceFollowRedirects = false
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrEmpty()) throw IllegalStateException("重定向地址为空")
                currentUrl = if (location.startsWith("http")) location
                else java.net.URI(currentUrl).resolve(location).toString()
                redirects++
                continue
            }
            if (code != 200) {
                conn.disconnect()
                throw IllegalStateException("HTTP $code")
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(32 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) onProgress((downloaded * 100 / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            conn.disconnect()
            if (total > 0 && dest.length() != total) throw IllegalStateException("文件不完整")
            return
        }
        throw IllegalStateException("重定向次数过多")
    }

    /** 数字分段比较版本（v1.2.10 > v1.2.9） */
    private fun isNewer(newTag: String, current: String): Boolean {
        val a = newTag.split('.', '-').mapNotNull { it.toIntOrNull() }
        val b = current.split('.', '-').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
