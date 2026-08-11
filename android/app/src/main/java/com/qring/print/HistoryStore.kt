package com.qring.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 打印历史（2026-08-11 加，参考 QrintPrint-Windows 的 HistoryStore）：
 * filesDir/history/ 下 index.json 索引 + jobs/<id>.bin（打印光栅，无损重打）+ thumbs/<id>.png。
 * 最多 [MAX] 条，超限丢最旧。
 */
object HistoryStore {

    const val MAX = 100

    /** 一条历史记录 */
    data class Job(
        val id: String,
        val type: String,        // 文字/图片/错题卡/模板/条码
        val title: String,
        val ts: Long,
        val widthBytes: Int,
        val height: Int,
        val mode: Int,
        val halve: Boolean,
        val thumbFile: String,
    )

    @Volatile
    private var dir: File? = null

    private fun d(): File = checkNotNull(dir) { "HistoryStore.init(context) 必须先调用" }

    fun init(context: Context) {
        if (dir == null) {
            val root = File(context.filesDir, "history")
            File(root, "jobs").mkdirs()
            File(root, "thumbs").mkdirs()
            dir = root
        }
    }

    // ── 索引 ──

    private fun indexFile() = File(d(), "index.json")

    private fun readIndex(): JSONArray = try {
        JSONArray(indexFile().readText())
    } catch (e: Exception) {
        JSONArray()
    }

    private fun writeIndex(arr: JSONArray) {
        indexFile().writeText(arr.toString())
    }

    // ── 记录 ──

    /** 打印成功后记录：存光栅 bin（无损重打）+ 缩略图 */
    fun add(type: String, title: String, raster: RasterData, thumb: Bitmap): Job {
        val id = UUID.randomUUID().toString().substring(0, 12)
        val jobsDir = File(d(), "jobs")
        val thumbsDir = File(d(), "thumbs")
        // 光栅数据存 bin
        val bin = File(jobsDir, "$id.bin")
        val out = java.io.ByteArrayOutputStream()
        out.write(raster.widthBytes)
        out.write(raster.height / 256)
        out.write(raster.height % 256)
        out.write(raster.data)
        bin.writeBytes(out.toByteArray())
        // 缩略图（等比缩到 180 宽）
        val scale = 180f / thumb.width
        val thumbBmp = Bitmap.createScaledBitmap(
            thumb, 180, maxOf(1, (thumb.height * scale).toInt()), true
        )
        val thumbFile = File(thumbsDir, "$id.png")
        java.io.FileOutputStream(thumbFile).use { thumbBmp.compress(Bitmap.CompressFormat.PNG, 90, it) }

        val job = Job(
            id = id, type = type, title = title, ts = System.currentTimeMillis(),
            widthBytes = raster.widthBytes, height = raster.height,
            mode = 2, halve = true,
            thumbFile = thumbFile.absolutePath,
        )
        val arr = readIndex()
        val obj = JSONObject().apply {
            put("id", job.id); put("type", job.type); put("title", job.title)
            put("ts", job.ts); put("widthBytes", job.widthBytes); put("height", job.height)
            put("mode", job.mode); put("halve", job.halve)
            put("thumb", job.thumbFile)
        }
        arr.put(0, obj)
        // 超限丢最旧（删 bin + thumb）
        while (arr.length() > MAX) {
            val old = arr.getJSONObject(arr.length() - 1)
            File(old.optString("thumb", "")).delete()
            File(File(d(), "jobs"), "${old.optString("id")}.bin").delete()
            arr.remove(arr.length() - 1)
        }
        writeIndex(arr)
        return job
    }

    // ── 读取 ──

    fun list(): List<Job> {
        val arr = readIndex()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Job(
                id = o.getString("id"), type = o.getString("type"), title = o.getString("title"),
                ts = o.getLong("ts"), widthBytes = o.getInt("widthBytes"), height = o.getInt("height"),
                mode = o.getInt("mode"), halve = o.getBoolean("halve"),
                thumbFile = o.optString("thumb"),
            )
        }
    }

    /** 载入光栅（无损重打） */
    fun loadRaster(job: Job): RasterData? {
        return try {
            val bin = File(File(d(), "jobs"), "${job.id}.bin")
            if (!bin.exists()) return null
            val bytes = bin.readBytes()
            if (bytes.size < 3) return null
            val wb = bytes[0].toInt() and 0xFF
            val h = (bytes[1].toInt() and 0xFF) * 256 + (bytes[2].toInt() and 0xFF)
            val data = bytes.copyOfRange(3, bytes.size)
            RasterData(wb, h, data)
        } catch (e: Exception) {
            null
        }
    }

    fun thumbBitmap(job: Job): Bitmap? {
        return try {
            BitmapFactory.decodeFile(job.thumbFile)
        } catch (e: Exception) {
            null
        }
    }

    fun clear() {
        File(d(), "jobs").listFiles()?.forEach { it.delete() }
        File(d(), "thumbs").listFiles()?.forEach { it.delete() }
        writeIndex(JSONArray())
    }

    fun delete(job: Job) {
        File(job.thumbFile).delete()
        File(File(d(), "jobs"), "${job.id}.bin").delete()
        val arr = readIndex()
        for (i in arr.length() - 1 downTo 0) {
            if (arr.getJSONObject(i).optString("id") == job.id) arr.remove(i)
        }
        writeIndex(arr)
    }

    /** 时间格式化（列表显示） */
    fun formatTime(ts: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
}
