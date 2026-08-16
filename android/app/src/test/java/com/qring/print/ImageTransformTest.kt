package com.qring.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 照片旋转+缩放（2026-08-14 加）尺寸/像素断言。
 * Robolectric LEGACY 下几何图形（drawRect/drawLine）可靠渲染，文字不做断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageTransformTest {

    private fun bmp(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    @Test
    fun `旋转90度交换宽高并缩到384宽`() {
        // 400×200 旋转 90 → 200×400 → 等比缩 384 宽 → 高 400×384/200=768
        val out = ImageTransform.apply(bmp(400, 200), 90, 100)
        assertEquals(384, out.width)
        assertEquals(768, out.height)
    }

    @Test
    fun `旋转180度保持比例`() {
        // 400×200 旋转 180 → 400×200 → 等比缩 384 宽 → 高 200×384/400=192
        val out = ImageTransform.apply(bmp(400, 200), 180, 100)
        assertEquals(384, out.width)
        assertEquals(192, out.height)
    }

    @Test
    fun `缩放200中心裁边到384宽`() {
        // 400×200 放大 200% → 768×384 → 居中裁 384 → 384×384
        val out = ImageTransform.apply(bmp(400, 200), 0, 200)
        assertEquals(384, out.width)
        assertEquals(384, out.height)
    }

    @Test
    fun `缩放50白底居中缩小`() {
        // 400×200 缩小 50% → 192×96 → 白底 384 宽 → 384×96
        val out = ImageTransform.apply(bmp(400, 200), 0, 50)
        assertEquals(384, out.width)
        assertEquals(96, out.height)
    }

    @Test
    fun `默认参数返回原实例零回归`() {
        val src = bmp(384, 100)
        val out = ImageTransform.apply(src, 0, 100)
        assertSame("0°/100% 应返回原实例", src, out)
    }

    @Test
    fun `旋转180不产生原实例`() {
        val src = bmp(384, 100)
        assertNotSame("旋转 180° 应生成新位图", src, ImageTransform.apply(src, 180, 100))
    }

    @Test
    fun `缩放50图像居中留白`() {
        // 全黑源图 400×200 → 50% → 192×96 水平居中于 384 宽画布。
        // 断言「左缘空白 ≠ 图像区」证明水平偏移（居中），不依赖环境是否能渲染纯白背景
        //（Robolectric LEGACY 下 drawColor 为空操作，白底不可断言，留白视觉交 runPreviewCheck）。
        val src = bmp(400, 200).apply { eraseColor(Color.BLACK) }
        val out = ImageTransform.apply(src, 0, 50)
        assertTrue("左缘应空白（未画图区）", out.getPixel(0, 48) != out.getPixel(192, 48))
    }

    @Test
    fun `缩放200中心裁边尺寸正确`() {
        // 400×200 放大 200% → 居中裁 384 宽。裁剪内容在 LEGACY 下 createBitmap(源,..) 子图
        // 返回空白，仅断尺寸；裁边正确性视觉交 runPreviewCheck。
        val src = bmp(400, 200)
        val c = Canvas(src)
        c.drawColor(Color.WHITE)
        c.drawRect(0f, 0f, 200f, 200f, Paint().apply { color = Color.BLACK })
        val out = ImageTransform.apply(src, 0, 200)
        assertEquals(384, out.width)
        assertEquals(384, out.height)
    }
}
