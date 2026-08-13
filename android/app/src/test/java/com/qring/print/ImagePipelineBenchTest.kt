package com.qring.print

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图像管线性能基准（2026-08-13 加）：1M 像素灰度图 × 各抖动模式计时，
 * 另有 384 宽长图（上万行）模拟真实打印场景。
 *
 * JVM 参考值（非真机）：同一份 Kotlin 逐像素代码，JVM 与 ART 的 JIT 行为
 * 相近，数量级可参考；超标预警线设得宽松（JVM 值放大 10 倍仍达标才安全）。
 *
 * 结果 println 输出（跑 runUnitTests 时可见），人工抄入 README 基准节。
 * 断言只防完全退化（如 JIT 失效/死循环级别的慢），不卡具体毫秒数——
 * 机器差异会让精确上限 flaky。
 */
class ImagePipelineBenchTest {

    /** 确定性灰度图（不用 Random：结果可复现、跨机器一致） */
    private fun grayOf(width: Int, height: Int): GrayImage {
        val data = IntArray(width * height) { i ->
            (i * 7 + (i / width) * 13 + (i % width) * 31) % 256
        }
        return GrayImage(data, width, height)
    }

    /** 计时一次，返回毫秒 */
    private fun <T> timeMs(block: () -> T): Double {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1e6
    }

    /** 中位数（3 次取样抗抖动） */
    private fun <T> medianMs(block: () -> T): Double {
        val samples = DoubleArray(3) { timeMs(block) }
        samples.sort()
        return samples[1]
    }

    private fun bench(gray: GrayImage, label: String): String {
        val rows = StringBuilder()
        for (mode in DitherMode.entries) {
            val ditherMs = medianMs { Dither.toBinary(gray, mode, RasterEncoder.THRESHOLD_IMAGE) }
            // 全管线 = 抖动 + 打包（encode 的 NONE 阈值路径 + packPublic）
            val pipelineMs = medianMs {
                val binary = Dither.toBinary(gray, mode, RasterEncoder.THRESHOLD_IMAGE)
                RasterEncoder.packPublic(binary, gray.width, gray.height)
            }
            rows.append(
                String.format(
                    "%-22s %6.1f ms       %6.1f ms%n",
                    mode.name, ditherMs, pipelineMs
                )
            )
            // 防退化断言：1M 像素单模式抖动 > 10s 视为异常（JVM 正常 <1s）
            assertTrue("$label ${mode.name} 抖动耗时异常: ${ditherMs}ms", ditherMs < 10_000)
        }
        return "$label（${gray.width}×${gray.height} = ${gray.width * gray.height / 1000}k 像素）\n" +
            "模式                    抖动耗时         抖动+打包\n$rows"
    }

    @Test
    fun `1M像素各抖动模式基准`() {
        val gray = grayOf(1000, 1000)
        println(bench(gray, "1M 像素方图"))
    }

    @Test
    fun `384宽长图基准`() {
        // 384×2604 ≈ 1M 像素（真实打印宽度 + 常见图片高度）
        println(bench(grayOf(384, 2604), "384 宽长图"))
    }

    @Test
    fun `384宽上万行长文档基准`() {
        // 文字长文档场景：384×10000 = 3.84M 像素（MAX_TEXT_HEIGHT 内）
        val gray = grayOf(384, 10_000)
        for (mode in DitherMode.entries) {
            val ms = medianMs { Dither.toBinary(gray, mode, RasterEncoder.THRESHOLD_IMAGE) }
            println(String.format("384×10000（3.84M 像素）%-22s %6.1f ms%n", mode.name, ms))
            assertTrue("长文档 ${mode.name} 抖动耗时异常: ${ms}ms", ms < 30_000)
        }
    }
}
