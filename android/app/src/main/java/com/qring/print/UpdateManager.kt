package com.qring.print

import android.app.Activity
import android.content.Context
import android.util.Log
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
    // 检查改走 jsDelivr（国内可达）。
    // 2026-09-09（#29）：**三个源都取、按版本号取最大**，不再「谁先成功用谁」——
    // 实测 jsDelivr data API 的版本列表索引会长期滞后（v0.7.5 发布 8 天后仍未收录、
    // v0.7.6 当天也未收录，而 @v{version} 显式路径一直 200），它原本是第一优先级，
    // 导致新版本被误判成「已是最新」。@main/version.json 实测发版后秒级生效，是主力源。
    // 下载一律走 jsDelivr CDN @v{version} tag 路径（tag 不可变，永久缓存正确）。
    private const val JSDELIVR_VERSION_URL =
        "https://cdn.jsdelivr.net/gh/$GITHUB_OWNER/$GITHUB_REPO@main/version.json"
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
            // 多源取值（2026-09-09 #29 修）：三个源各自 best-effort 取回并解析成候选，
            // 再按版本号取**最大**——不能只看第一个成功的源。
            // 背景：jsDelivr data API 的版本列表索引会长期滞后（实测 v0.7.5 发布 8 天后、
            // v0.7.6 发布当天都没被收录，而 @v{version} 显式路径一直正常），
            // 它原本是第一优先级 → 会把新版本误判成「已是最新」，用户永远收不到更新。
            val candidates = withContext(Dispatchers.IO) {
                listOfNotNull(
                    runCatching { parseDataApi(httpGet(JSDELIVR_DATA_URL)) }.getOrNull(),
                    runCatching { parseVersionJson(httpGet(JSDELIVR_VERSION_URL)) }.getOrNull(),
                    runCatching { parseGithubLatest(httpGet("$API_BASE/releases/latest")) }.getOrNull(),
                )
            }
            val best = pickNewest(candidates) ?: run {
                withContext(Dispatchers.Main) {
                    listener.onResult("检查更新失败：网络异常或版本信息解析失败（jsDelivr 与 GitHub 均不可达）")
                }
                return@launch
            }

            if (ReleaseNotes.isNewer(best.version, current)) {
                withContext(Dispatchers.Main) {
                    listener.onUpdateAvailable(best.version, best.notes, best.apkUrl)
                }
            } else {
                withContext(Dispatchers.Main) { listener.onResult(null) }
            }
        }
    }

    /** 单个源解析出的候选版本（纯数据，便于 JVM 单测） */
    internal data class Candidate(val version: String, val notes: String, val apkUrl: String)

    /** @v{version} tag 路径的 APK 下载地址（tag 不可变，冷缓存秒级生效） */
    private fun cdnApkUrl(version: String): String = "$JSDELIVR_CDN_BASE$version/$APK_REPO_PATH"

    /**
     * 从各源候选中挑出**版本号最高**的一个（2026-09-09 #29）。
     * 空版本号忽略；全为空返回 null。
     */
    internal fun pickNewest(candidates: List<Candidate>): Candidate? =
        candidates.filter { it.version.isNotBlank() }
            .reduceOrNull { best, cur -> if (ReleaseNotes.isNewer(cur.version, best.version)) cur else best }

    /** jsDelivr data API：{"versions":[{"version":"0.5.1"},...]}（最新在前，无 v 前缀） */
    internal fun parseDataApi(body: String): Candidate? = runCatching {
        val arr = JSONObject(body).optJSONArray("versions") ?: return@runCatching null
        val v = arr.optJSONObject(0)?.optString("version")?.removePrefix("v").orEmpty()
        if (v.isEmpty()) null else Candidate(v, "", cdnApkUrl(v))
    }.getOrNull()

    /** 仓库内 @main/version.json：{"version":"0.5.3","notes":"..."} */
    internal fun parseVersionJson(body: String): Candidate? = runCatching {
        val obj = JSONObject(body)
        val v = obj.optString("version").removePrefix("v")
        if (v.isEmpty()) null
        else Candidate(v, obj.optString("notes", "").trim().take(500), cdnApkUrl(v))
    }.getOrNull()

    /** GitHub releases/latest：tag_name / body / assets.browser_download_url */
    internal fun parseGithubLatest(body: String): Candidate? = runCatching {
        val obj = JSONObject(body)
        val v = obj.optString("tag_name").removePrefix("v")
        if (v.isEmpty()) null else {
            val asset = obj.optJSONArray("assets")?.let { arr ->
                (0 until arr.length()).firstNotNullOfOrNull { i ->
                    val a = arr.optJSONObject(i)
                    if (a?.optString("name")?.endsWith(".apk") == true) a else null
                }
            }
            val url = asset?.optString("browser_download_url").orEmpty()
            Candidate(v, obj.optString("body", "").trim().take(500), url.ifEmpty { cdnApkUrl(v) })
        }
    }.getOrNull()

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
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            Log.d("UpdateManager", "GET ${urlStr.substringBefore("?")} -> $code, ${body.length}B")
            return body
        } catch (e: Exception) {
            Log.e("UpdateManager", "GET ${urlStr.substringBefore("?")} 失败: ${e.javaClass.simpleName}: ${e.message}")
            throw e
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
                    // 2026-08-13：Android 13+ 装 APK 需「允许安装未知应用」授权。
                    // 未授权则跳系统设置页引导，用户开完回来重新点更新即装（一次性，之后免授权）。
                    if (!activity.packageManager.canRequestPackageInstalls()) {
                        Toast.makeText(
                            activity, "首次安装需先授权「允许安装未知应用」", Toast.LENGTH_LONG
                        ).show()
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}")
                            )
                        )
                        onDone(false)
                        return@withContext
                    }
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
}
