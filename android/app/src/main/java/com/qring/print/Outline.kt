package com.qring.print

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** 描边算法选择 */
enum class OutlineMethod(val label: String) {
    CANNY("Canny"),
    LINES("Lines"),
}

/**
 * 描边（移植自 xyprt/Outline，2026-08-11，反编译翻译；grow 为近似实现）。
 *
 * 两种描边：
 * - [Canny.detect]：梯度边缘检测（见 Canny.kt）
 * - [Outline.trace]：墨水对比度边缘（inkness = 255 - min(R,G,B)，
 *   只记录"比邻居更黑"的暗侧，不会双边重复）
 *
 * 输出 true = 黑点。透明像素（!isGlyph）不参与（视为背景/白）。
 */
object Outline {

    private const val FLOOR = 3f
    /** s < 10 时全灭（只保留轮廓线） */
    private const val SILHOUETTE_BELOW = 10
    private const val DETAIL_GAMMA = 1f

    /**
     * LINES 描边。
     * @param sensitivity 0..100：阈值取最强者线性百分位
     *   (1-(s-10)/90)×(size-1)，s<10 全灭；默认 88 ≈ 保留 ~13%
     * @param thickness 线宽 1..3
     * @param borderIsBackground 贴图像边缘的像素是否算轮廓（默认 false：不算）
     */
    fun trace(
        argb: IntArray,
        isGlyph: BooleanArray,
        width: Int,
        height: Int,
        sensitivity: Int,
        thickness: Int,
        borderIsBackground: Boolean = false,
        smooth: Boolean = false,
    ): BooleanArray {
        val n = width * height
        val inkEdge = FloatArray(n)
        val silhouette = BooleanArray(n)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                if (!isGlyph[i]) continue
                // 透明边界的轮廓（4 邻域任一背景）
                if (touchesBackground(isGlyph, x, y, width, height, borderIsBackground)) {
                    silhouette[i] = true
                }
                inkEdge[i] = maxInkEdge(argb, isGlyph, x, y, width, height)
            }
        }

        val s = sensitivity.coerceIn(0, 100)
        val sorted = ArrayList<Float>()
        for (v in inkEdge) if (v > FLOOR) sorted.add(v)
        sorted.sort()
        val t = if (sorted.isEmpty() || s < SILHOUETTE_BELOW) {
            Float.MAX_VALUE
        } else {
            val idx = ((1f - ((s - 10) / 90f).pow(DETAIL_GAMMA)) * (sorted.size - 1))
                .roundToInt().coerceIn(0, sorted.size - 1)
            sorted[idx]
        }

        val mask = BooleanArray(n) { i -> silhouette[i] || inkEdge[i] >= t }
        val merged = if (smooth) Morphology.smooth(mask, width, height) else mask
        return thicken(merged, isGlyph, width, height, thickness.coerceIn(1, 3))
    }

    /**
     * 描边加粗。
     * thickness=1 原样返回；2 → 1 次 grow；3 → 2 次 grow（1 对称 + 1 非对称）。
     */
    fun thicken(
        src: BooleanArray,
        isGlyph: BooleanArray,
        width: Int,
        height: Int,
        thickness: Int,
    ): BooleanArray {
        val extra = thickness - 1
        if (extra <= 0) return src
        var cur = src
        repeat(extra / 2) { cur = grow(cur, isGlyph, width, height, symmetric = true) }
        if (extra % 2 == 1) cur = grow(cur, isGlyph, width, height, symmetric = false)
        return cur
    }

    /**
     * 膨胀。TODO: 原版 grow 未反编译出（jadx 缺字节码），此处为 8 邻域膨胀近似：
     * 黑像素的 8 邻域（isGlyph 内）置黑。symmetric 保留签名但暂不区分行为，
     * 自检图与 xyprt 截图比对后再定。
     */
    private fun grow(
        src: BooleanArray,
        isGlyph: BooleanArray,
        width: Int,
        height: Int,
        symmetric: Boolean,
    ): BooleanArray {
        val out = src.copyOf()
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!src[y * width + x]) continue
                for (oy in -1..1) {
                    for (ox in -1..1) {
                        if (ox == 0 && oy == 0) continue
                        val nx = x + ox
                        val ny = y + oy
                        if (nx in 0 until width && ny in 0 until height) {
                            val j = ny * width + nx
                            if (isGlyph[j]) out[j] = true
                        }
                    }
                }
            }
        }
        return out
    }

    /** 4 邻域任一背景（isBackground）即视为贴背景 */
    private fun touchesBackground(
        isGlyph: BooleanArray,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        borderIsBackground: Boolean,
    ): Boolean =
        isBackground(isGlyph, x - 1, y, width, height, borderIsBackground) ||
            isBackground(isGlyph, x + 1, y, width, height, borderIsBackground) ||
            isBackground(isGlyph, x, y - 1, width, height, borderIsBackground) ||
            isBackground(isGlyph, x, y + 1, width, height, borderIsBackground)

    /** 界内 = 非 glyph；越界 = borderIsBackground */
    private fun isBackground(
        isGlyph: BooleanArray,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        borderIsBackground: Boolean,
    ): Boolean {
        if (x in 0 until width && y in 0 until height) {
            return !isGlyph[y * width + x]
        }
        return borderIsBackground
    }

    /** 墨水浓度 = 离白色的距离（通道最小值） */
    private fun inkness(p: Int): Int = 255 - min((p shr 16) and 0xFF, min((p shr 8) and 0xFF, p and 0xFF))

    /** 4 邻域最大墨色边缘值 */
    private fun maxInkEdge(
        argb: IntArray,
        isGlyph: BooleanArray,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Float {
        val p = argb[y * width + x]
        val inkP = inkness(p)
        val left = edgeTo(argb, isGlyph, x - 1, y, width, height, p, inkP)
        val right = edgeTo(argb, isGlyph, x + 1, y, width, height, p, inkP)
        val up = edgeTo(argb, isGlyph, x, y - 1, width, height, p, inkP)
        val down = edgeTo(argb, isGlyph, x, y + 1, width, height, p, inkP)
        return maxOf(left, right, up, down)
    }

    /**
     * 单方向墨色边缘：只记"比邻居更黑"的一侧（等墨时按 RGB 打包值破平），
     * 因此边缘落在暗侧、不会双边重复。返回最大通道差。
     */
    private fun edgeTo(
        argb: IntArray,
        isGlyph: BooleanArray,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        p: Int,
        inkP: Int,
    ): Float {
        if (x !in 0 until width || y !in 0 until height) return 0f
        val j = y * width + x
        if (!isGlyph[j]) return 0f
        val q = argb[j]
        val inkQ = inkness(q)
        if (inkP < inkQ || (inkP == inkQ && (p and 0xFFFFFF) < (q and 0xFFFFFF))) return 0f
        val dr = abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF))
        val dg = abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF))
        val db = abs((p and 0xFF) - (q and 0xFF))
        return maxOf(dr, dg, db).toFloat()
    }
}
