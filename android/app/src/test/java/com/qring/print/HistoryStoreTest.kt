package com.qring.print

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 打印历史参数快照测试（#5a 历史再编辑）：
 * add 带 paramsJson → list 原样读回；不带 → null（老记录兼容）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryStoreTest {

    private fun raster(w: Int = 8, h: Int = 24): RasterData {
        val data = ByteArray(w * h) { if (it % 2 == 0) 0 else 0xFF.toByte() }
        return RasterData(w, h, data)
    }

    private fun thumb(): Bitmap {
        val b = Bitmap.createBitmap(384, 48, Bitmap.Config.ARGB_8888)
        b.eraseColor(0xFFFFFFFF.toInt())
        return b
    }

    @Test
    fun `参数快照 存取往返`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        HistoryStore.init(ctx)
        HistoryStore.clear()
        val r = raster()
        val params = """{"type":"text","text":"错题","font":1,"align":0,"bold":false}"""
        val job = HistoryStore.add("文字", "错题", r, thumb(), params)
        val loaded = HistoryStore.list().first { it.id == job.id }
        assertEquals("params 应原样存回", params, loaded.paramsJson)
        assertEquals("光栅信息应保留", r.widthBytes, loaded.widthBytes)
        assertEquals("光栅高度应保留", r.height, loaded.height)
    }

    @Test
    fun `无参数快照 为 null`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        HistoryStore.init(ctx)
        HistoryStore.clear()
        val job = HistoryStore.add("模板", "模板", raster(), thumb())
        val loaded = HistoryStore.list().first { it.id == job.id }
        assertNull("未传 params 应为 null（老记录兼容）", loaded.paramsJson)
    }
}
