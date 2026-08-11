package com.qring.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color

/**
 * 光栅编码：Bitmap → 384 点宽二值光栅（每行 48 字节，MSB first，置 1 = 黑）。
 *
 * 移植自 QringPrint 的 RasterEncoder：
 * - 图片按比例缩放到 384 宽，高度任意
 * - 透明像素按白色合成（alpha==0 视为不打印）
 * - 灰度 → 二值：NONE 模式用阈值（文字 212 / 图片 128），
 *   抖动模式（Floyd-Steinberg / Atkinson）恒用 128 中点（见 Dither.PIVOT）
 * - 打包规则与官方 SDK 一致：每行 48 字节，MSB first，置 1 = 黑
 */
object RasterEncoder {

    /** 文字二值化阈值。官方 App 打文字用 212，比图片高很多，笔画才不会被吃掉 */
    const val THRESHOLD_TEXT = 212
    /**
     * 文本/表格渲染高度上限（像素行）。Excel 几千行直接渲染会把 Bitmap 撑到
     * GB 级 OOM（2026-08-11 真机闪退根因：1.9GB 分配失败）。
     * 384 宽 × 30000 行 × 4B ≈ 46MB，加灰度和二值数组约 140MB，安全线内。
     */
    const val MAX_TEXT_HEIGHT = 30000
    /** 图片二值化阈值（仅 NONE 模式生效） */
    const val THRESHOLD_IMAGE = 128

    /**
     * 把任意 Bitmap 编码为光栅数据。
     *
     * @param mode 抖动模式：照片用 FLOYD_STEINBERG（层次细腻），
     *             线稿/二维码用 NONE（最锐利），ATKINSON 对比度更高
     */
    fun encode(
        bitmap: Bitmap,
        mode: DitherMode = DitherMode.NONE,
        threshold: Int = THRESHOLD_IMAGE,
        contrast: Int = 0,
    ): RasterData {
        val gray = extractGrayPublic(bitmap)
        val data = if (contrast != 0) {
            // 对比度调节（xyprt 移植，2026-08-11）：膝形 S 曲线，PDF 打印默认 contrast=10
            val float = FloatArray(gray.data.size) { gray.data[it].toFloat() }
            val adjusted = Contrast.adjust(float, contrast)
            GrayImage(IntArray(adjusted.size) { adjusted[it].toInt() }, gray.width, gray.height)
        } else {
            gray
        }
        val binary = Dither.toBinary(data, mode, threshold)
        return packPublic(binary, data.width, data.height)
    }

    /**
     * Bitmap → 384 宽灰度图（最近邻采样）。
     * 透明像素按白底合成再转灰度（0.299R + 0.587G + 0.114B）。
     */
    fun extractGrayPublic(bitmap: Bitmap): GrayImage {
        val srcW = bitmap.width
        val srcH = bitmap.height
        val scale = WIDTH_DOTS.toDouble() / srcW
        // 高度上限（2026-08-11：大图/长文档位图高度无上限会 OOM 闪退，
        // 与 MAX_TEXT_HEIGHT/OUTLINE_MAX_HEIGHT 同级别内存安全线）
        val height = minOf(maxOf(1, (srcH * scale).toInt()), MAX_TEXT_HEIGHT)

        val gray = IntArray(WIDTH_DOTS * height)
        for (y in 0 until height) {
            val srcY = (y / scale).toInt().coerceIn(0, srcH - 1)
            for (x in 0 until WIDTH_DOTS) {
                val srcX = (x / scale).toInt().coerceIn(0, srcW - 1)
                val pixel = bitmap.getPixel(srcX, srcY)
                val a = (pixel ushr 24) and 0xFF
                // 透明按白底合成，再转灰度
                val alpha = a / 255f
                val r = ((pixel shr 16) and 0xFF) * alpha + 255 * (1 - alpha)
                val g = ((pixel shr 8) and 0xFF) * alpha + 255 * (1 - alpha)
                val b = (pixel and 0xFF) * alpha + 255 * (1 - alpha)
                gray[y * WIDTH_DOTS + x] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            }
        }
        return GrayImage(gray, WIDTH_DOTS, height)
    }

    /**
     * 二值数据 → 光栅字节。编码规则与官方 SDK 一致：
     * 每行 48 字节，MSB first（bit7 = 最左像素），置 1 = 黑。
     */
    fun packPublic(binary: ByteArray, width: Int, height: Int): RasterData {
        val data = ByteArray(WIDTH_BYTES * height)
        val limit = minOf(width, WIDTH_DOTS)   // 超出 384 的列丢弃，不足留白（0 = 白）
        for (y in 0 until height) {
            val rowBase = y * width
            val outBase = y * WIDTH_BYTES
            for (x in 0 until limit) {
                if (binary[rowBase + x].toInt() == 1) {
                    data[outBase + (x shr 3)] = (data[outBase + (x shr 3)].toInt() or (0x80 shr (x and 7))).toByte()
                }
            }
        }
        return RasterData(WIDTH_BYTES, height, data)
    }

    /**
     * 光栅 → 预览位图：把"实际会打印成什么样"渲染出来（白底黑点）。
     * 打印前预览防废纸；黑白两色下 RGBA 各通道相同。
     *
     * @param verticalScale 光栅打印模式的高度放大倍数：
     *   图片通道 = 行合并(m=2 数据) → verticalScale=2（模拟 m=2 垂直复制，高度还原）
     *   文字通道 = m=0 → 1:1
     */
    fun rasterToPreviewBitmap(raster: RasterData, verticalScale: Int = 1): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH_DOTS, raster.height * verticalScale, Bitmap.Config.ARGB_8888)
        val data = raster.data
        val wb = raster.widthBytes
        for (y in 0 until raster.height) {
            val rowBase = y * wb
            val isBlack = IntArray(minOf(WIDTH_DOTS, wb * 8)) { x ->
                if ((data[rowBase + (x shr 3)].toInt() and (0x80 shr (x and 7))) != 0) Color.BLACK else Color.WHITE
            }
            for (rep in 0 until verticalScale) {
                val yy = y * verticalScale + rep
                for (x in isBlack.indices) bmp.setPixel(x, yy, isBlack[x])
            }
        }
        return bmp
    }

    /**
     * 行合并减半：每 2 行 OR 合并成 1 行（奇数行保留最后一行）。
     * 用途：m=2 双倍高打印前把数据高度减半，双打提黑度同时高度还原不变形。
     * OR 合并保留所有黑点（1px 细线不丢，只变粗），比隔行采样糊得轻。
     */
    fun halveRows(raster: RasterData): RasterData {
        val w = raster.widthBytes
        val outRows = (raster.height + 1) / 2
        val out = ByteArray(w * outRows)
        for (i in 0 until outRows) {
            val r1 = i * 2
            val r2 = r1 + 1
            for (b in 0 until w) {
                val v = raster.data[r1 * w + b]
                out[i * w + b] = if (r2 < raster.height) {
                    (v.toInt() or raster.data[r2 * w + b].toInt()).toByte()
                } else {
                    v
                }
            }
        }
        return RasterData(w, outRows, out)
    }

    /**
     * 多图拼接成一张 384 宽位图（在 Bitmap 层拼，之后统一走增强/抖动管线，视觉一致）。
     * @param layout 0 = 单列堆叠；1 = 双列（每张 184 宽，左右排，省纸）
     */
    fun composeImages(images: List<Bitmap>, layout: Int): Bitmap {
        require(images.isNotEmpty()) { "没有图片" }
        if (layout == 0) {
            // 单列：每张等比缩到 384 宽，上下堆叠
            var totalH = 0
            val scaled = images.map { img ->
                val h = maxOf(1, (img.height * WIDTH_DOTS.toFloat() / img.width).toInt())
                totalH += h
                Bitmap.createScaledBitmap(img, WIDTH_DOTS, h, true)
            }
            val out = Bitmap.createBitmap(WIDTH_DOTS, totalH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(Color.WHITE)
            var y = 0
            for (bmp in scaled) {
                canvas.drawBitmap(bmp, 0f, y.toFloat(), null)
                y += bmp.height
            }
            return out
        } else {
            // 双列：每张 184 宽；第 i 张在第 i%2 列、第 i/2 行；行高取该行两图最大高
            val colW = (WIDTH_DOTS - GAP) / 2
            val rows = (images.size + 1) / 2
            val cellH = IntArray(images.size)
            val rowH = IntArray(rows)
            val scaled = ArrayList<Bitmap>(images.size)
            images.forEachIndexed { i, img ->
                val h = maxOf(1, (img.height * colW.toFloat() / img.width).toInt())
                cellH[i] = h
                rowH[i / 2] = maxOf(rowH[i / 2], h)
                scaled.add(Bitmap.createScaledBitmap(img, colW, h, true))
            }
            val totalH = rowH.sum()
            val out = Bitmap.createBitmap(WIDTH_DOTS, totalH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(Color.WHITE)
            val rowTop = IntArray(rows)
            for (r in 1 until rows) rowTop[r] = rowTop[r - 1] + rowH[r - 1]
            images.indices.forEach { i ->
                val col = i % 2
                val row = i / 2
                canvas.drawBitmap(
                    scaled[i],
                    (col * (colW + GAP)).toFloat(),
                    (rowTop[row] + (rowH[row] - cellH[i]) / 2).toFloat(),  // 行内垂直居中
                    null
                )
            }
            return out
        }
    }

    private const val GAP = 16

    /**
     * 文本 → 光栅：白底黑字，按宽度自动换行。
     * 文字固定走 NONE 模式 + 高阈值 212（笔画不被吃掉）。
     * @param bold 加粗（伪粗体，小字号下更实）
     * @param align 对齐：0 左 / 1 中 / 2 右（2026-08-11 加，参考 QrintPrint-Windows）
     */
    fun encodeText(
        text: String,
        fontSizePx: Int = 48,
        lineSpacingPx: Int = 8,
        bold: Boolean = false,
        align: Int = 0,
    ): RasterData {
        val padding = 8
        val width = WIDTH_DOTS - padding * 2
        val paint = android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG
        ).apply {
            textSize = fontSizePx.toFloat()
            color = Color.BLACK
            isFakeBoldText = bold
        }

        // 预排版：按宽度换行（中文没有词边界，逐字符）
        val fm = paint.fontMetrics
        val lineHeight = (fm.bottom - fm.top).toInt() + lineSpacingPx
        val lines = mutableListOf<String>()
        var cur = ""
        for (ch in text) {
            val test = cur + ch
            if (paint.measureText(test) <= width && ch != '\n') {
                cur = test
            } else {
                if (cur.isNotEmpty()) lines.add(cur)
                cur = if (ch == '\n') "" else ch.toString()
            }
        }
        if (cur.isNotEmpty()) lines.add(cur)
        // 高度上限保护（2026-08-11）：Excel/长文档直接渲染会 OOM 闪退（真机 1.9GB 教训）。
        // 超过上限截断并加提示行，纸也不至于打几米长。
        val maxLines = ((MAX_TEXT_HEIGHT - padding * 2) / lineHeight).coerceAtLeast(1)
        if (lines.size > maxLines) {
            lines.subList(maxLines - 1, lines.size).clear()
            lines.add("…内容过长，已截断")
        }

        val height = maxOf(1, lines.size * lineHeight + padding * 2)
        val bmp = Bitmap.createBitmap(WIDTH_DOTS, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        var y = padding.toFloat() - fm.top
        for (line in lines) {
            val x = when (align) {
                1 -> padding + (width - paint.measureText(line)) / 2f   // 居中
                2 -> padding + (width - paint.measureText(line))        // 右对齐
                else -> padding.toFloat()                               // 左对齐
            }
            canvas.drawText(line, x, y, paint)
            y += lineHeight
        }
        return encode(bmp, DitherMode.NONE, THRESHOLD_TEXT)
    }

    // ── 描边（Canny / LINES，xyprt 移植 2026-08-11）────────────

    /** 描边高度上限（384 宽 × 3 万行内存可控），超出截断 */
    private const val OUTLINE_MAX_HEIGHT = 30000

    /**
     * Bitmap → 384 宽 argb 数组 + isGlyph 掩码（与 extractGrayPublic 同采样）。
     * alpha >= 128 视为"实体像素"（含白色背景），参与描边算法；透明像素视为背景。
     */
    private fun extractArgb(bitmap: Bitmap): Pair<IntArray, BooleanArray> {
        val srcW = bitmap.width
        val srcH = bitmap.height
        val scale = WIDTH_DOTS.toDouble() / srcW
        val height = minOf(maxOf(1, (srcH * scale).toInt()), OUTLINE_MAX_HEIGHT)
        val argb = IntArray(WIDTH_DOTS * height)
        val isGlyph = BooleanArray(WIDTH_DOTS * height)
        for (y in 0 until height) {
            val srcY = (y / scale).toInt().coerceIn(0, srcH - 1)
            for (x in 0 until WIDTH_DOTS) {
                val srcX = (x / scale).toInt().coerceIn(0, srcW - 1)
                val pixel = bitmap.getPixel(srcX, srcY)
                val i = y * WIDTH_DOTS + x
                argb[i] = pixel
                isGlyph[i] = (pixel ushr 24) and 0xFF >= 128
            }
        }
        return argb to isGlyph
    }

    /**
     * 描边模式：完全不经过灰度/对比度，直接对 argb 像素做边缘检测
     * （xyprt QuickPrintRenderer.toMono 的 OUTLINE 分支语义）。
     *
     * @param invert 反色：整体翻转掩码（黑变白、白变黑）
     */
    fun encodeOutline(
        bitmap: Bitmap,
        method: OutlineMethod = OutlineMethod.CANNY,
        sensitivity: Int = 88,
        thickness: Int = 1,
        smooth: Boolean = false,
        invert: Boolean = false,
    ): RasterData {
        val (argb, isGlyph) = extractArgb(bitmap)
        val h = isGlyph.size / WIDTH_DOTS
        val edge = when (method) {
            OutlineMethod.CANNY -> Canny.detect(argb, isGlyph, WIDTH_DOTS, h, sensitivity, thickness, smooth)
            OutlineMethod.LINES -> Outline.trace(argb, isGlyph, WIDTH_DOTS, h, sensitivity, thickness, smooth = smooth)
        }
        val final = if (invert) BooleanArray(edge.size) { !edge[it] } else edge
        val binary = ByteArray(final.size) { if (final[it]) 1 else 0 }
        return packPublic(binary, WIDTH_DOTS, h)
    }
}
