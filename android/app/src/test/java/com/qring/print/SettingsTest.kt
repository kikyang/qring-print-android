package com.qring.print

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置持久化测试（#5b 参数按内容类型记忆）：
 * 内容页参数快照（text/image/card）存取往返 + 条码类型记忆。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsTest {

    @Test
    fun `内容页参数 存取往返`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        Settings.init(ctx)
        val json = """{"type":"image","mode":"ATKINSON","trim":true}"""
        Settings.saveContentPref("image", json)
        assertEquals("保存的 JSON 应原样读回", json, Settings.loadContentPref("image"))
        assertNull("未保存的类型应返回 null", Settings.loadContentPref("text"))
    }

    @Test
    fun `条码类型 记忆与兜底`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        Settings.init(ctx)
        // 默认 QR
        assertEquals("QR_CODE", Settings.barcodeType)
        Settings.barcodeType = "CODE_128"
        assertEquals("CODE_128", Settings.barcodeType)
    }
}
