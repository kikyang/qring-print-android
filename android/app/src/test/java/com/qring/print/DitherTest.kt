package com.qring.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抖动算法单元测试（2026-08-12 补）：
 * NONE 阈值、Floyd/Atkinson 误差扩散的输出密度与像素守恒。
 */
class DitherTest {

    private fun gray(width: Int, height: Int, fill: Int): GrayImage =
        GrayImage(IntArray(width * height) { fill }, width, height)

    private fun countBlack(out: ByteArray): Int = out.count { it == 1.toByte() }

    // ── NONE 模式 ────────────────────────────────────────────────

    @Test
    fun `NONE阈值黑白划分`() {
        // 128 阈值：127 → 黑，129 → 白
        val g = GrayImage(intArrayOf(10, 127, 128, 129, 250), 5, 1)
        val out = Dither.toBinary(g, DitherMode.NONE, 128)
        assertArrayEquals(byteArrayOf(1, 1, 0, 0, 0), out)
    }

    @Test
    fun `NONE阈值可调`() {
        val g = GrayImage(intArrayOf(200), 1, 1)
        // 阈值 212：200 < 212 → 黑
        assertEquals(1, Dither.toBinary(g, DitherMode.NONE, 212)[0].toInt())
        // 阈值 150：200 ≥ 150 → 白
        assertEquals(0, Dither.toBinary(g, DitherMode.NONE, 150)[0].toInt())
    }

    @Test
    fun `NONE输出长度等于像素数`() {
        val out = Dither.toBinary(gray(48, 64, 100), DitherMode.NONE, 128)
        assertEquals(48 * 64, out.size)
    }

    // ── 均匀灰的密度统计 ──────────────────────────────────────────

    @Test
    fun `Floyd均匀128约一半黑`() {
        val out = Dither.toBinary(gray(64, 64, 128), DitherMode.FLOYD_STEINBERG, 128)
        val black = countBlack(out)
        // 误差扩散保持总体密度：128 灰应接近 50%（允许 ±8%）
        assertTrue("黑像素比例 ${black.toDouble() / 4096}", black in 1700..2400)
    }

    @Test
    fun `Atkinson均匀128也保持密度`() {
        val out = Dither.toBinary(gray(64, 64, 128), DitherMode.ATKINSON, 128)
        val black = countBlack(out)
        // Atkinson 丢弃 2/8 误差，密度略低但仍在 50% 附近（±12%）
        assertTrue("黑像素比例 ${black.toDouble() / 4096}", black in 1500..2400)
    }

    @Test
    fun `抖动模式忽略传入阈值`() {
        // 抖动模式固定 PIVOT=128：与 NONE 的阈值语义不同
        val g = GrayImage(intArrayOf(100), 1, 1)
        assertEquals(Dither.PIVOT, 128)
        // Floyd 下 100 < 128 → 黑（与传入 threshold 无关）
        assertEquals(1, Dither.toBinary(g, DitherMode.FLOYD_STEINBERG, 212)[0].toInt())
    }

    // ── 极端输入 ──────────────────────────────────────────────────

    @Test
    fun `全黑全白边界`() {
        val allBlack = Dither.toBinary(gray(32, 32, 0), DitherMode.FLOYD_STEINBERG, 128)
        assertEquals(32 * 32, countBlack(allBlack))
        val allWhite = Dither.toBinary(gray(32, 32, 255), DitherMode.FLOYD_STEINBERG, 128)
        assertEquals(0, countBlack(allWhite))
    }

    @Test
    fun `渐变图Atkinson比Floyd更省墨`() {
        // 亮灰渐变（150~200）：Atkinson 丢 2/8 误差 → 白像素更多
        val data = IntArray(64 * 64) { 150 + (it / 64) }
        val g = GrayImage(data, 64, 64)
        val floyd = countBlack(Dither.toBinary(g, DitherMode.FLOYD_STEINBERG, 128))
        val atk = countBlack(Dither.toBinary(g, DitherMode.ATKINSON, 128))
        assertTrue("floyd=$floyd atk=$atk", floyd >= atk)
    }

    // ── 蛇形扫描（2026-09-09 吸收上游 lztttt v1.6.0）──────────────────

    @Test
    fun `蛇形扫描奇偶行方向交替`() {
        // 手工推演金标准（PIVOT=128，FS 权重 7/16、3/16、5/16、1/16）：
        // 输入 3x2：行0=[0,128,255]，行1=[128,128,128]
        // 行0（左→右）：0→黑(err 0)；128→白(err -127，向右 7/16、向下 -3/16/-5/16/-1/16)；
        //               199.4375→白(err -55.5625)
        //   → 行1 缓冲 = [104.1875, 77.8945, 102.6997]
        // 行1（蛇形→右→左）：102.6997→黑；77.8945+44.93=122.8256→黑；104.1875+53.74=157.92→白
        //   → [0,1,1]；若行1 仍左→右（旧单向实现）会得到 [1,1,0] —— 本用例即区分两种实现。
        val g = GrayImage(intArrayOf(0, 128, 255, 128, 128, 128), 3, 2)
        val out = Dither.toBinary(g, DitherMode.FLOYD_STEINBERG, 128)
        assertArrayEquals(byteArrayOf(1, 0, 0, 0, 1, 1), out)
    }

    @Test
    fun `蛇形扫描保持墨量密度`() {
        // 行方向交替不改变总体墨量：均匀 128 灰仍应接近 50%
        val floyd = countBlack(Dither.toBinary(gray(64, 64, 128), DitherMode.FLOYD_STEINBERG, 128))
        assertTrue("floyd 黑像素比例 ${floyd.toDouble() / 4096}", floyd in 1700..2400)
        val atk = countBlack(Dither.toBinary(gray(64, 64, 128), DitherMode.ATKINSON, 128))
        assertTrue("atkinson 黑像素比例 ${atk.toDouble() / 4096}", atk in 1500..2400)
    }
}

private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
    org.junit.Assert.assertArrayEquals(expected, actual)
}
