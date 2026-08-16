package com.qring.print

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 高分辨率增强 + 三算法（2026-08-16 加） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageEnhancerTest {

    private fun bmp(w: Int, h: Int, black: Boolean): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.eraseColor(if (black) Color.BLACK else Color.WHITE)
        // 中间画一块黑色矩形，避免整图全白/全黑导致算法无差异
        for (y in h / 3 until h * 2 / 3) {
            for (x in w / 3 until w * 2 / 3) {
                b.setPixel(x, y, if (black) Color.WHITE else Color.BLACK)
            }
        }
        return b
    }

    @Test
    fun `高分辨率灰度图长边不超过2048`() {
        val out = ImageEnhancer.enhanceHighResGray(bmp(4000, 2000, false))
        assertTrue(out.width <= ImageEnhancer.HIGH_RES_MAX_EDGE)
        assertEquals(2048, out.width)
        assertEquals(1024, out.height)
    }

    @Test
    fun `小图保持原尺寸`() {
        val src = bmp(384, 100, false)
        val out = ImageEnhancer.enhanceHighResGray(src)
        assertEquals(384, out.width)
        assertEquals(100, out.height)
    }

    @Test
    fun `一键增强输出384宽光栅`() {
        val raster = ImageEnhancer.enhanceToRaster(bmp(800, 400, false))
        assertEquals(384, raster.widthBytes * 8)
        assertTrue(raster.height > 0)
        assertTrue(raster.data.any { it.toInt() != 0 })
    }

    @Test
    fun `三种算法均可输出非空光栅`() {
        for (algo in 0..2) {
            val raster = ImageEnhancer.enhanceToRaster(bmp(600, 300, false), algorithm = algo, strength = 1)
            assertTrue("algorithm $algo", raster.height > 0)
            assertTrue("algorithm $algo", raster.data.any { it.toInt() != 0 })
        }
    }

    @Test
    fun `enhanceGray输出尺寸与输入一致`() {
        val gray = RasterEncoder.extractGrayPublic(bmp(384, 120, false))
        val binary = ImageEnhancer.enhanceGray(gray, algorithm = 2)
        assertEquals(gray.width * gray.height, binary.size)
    }
}
