package com.qring.print

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 条码/二维码生成（2026-08-11 加，参考 QrintPrint-Windows 的 render.py）：
 * QR（方块适配 320x320 再缩放）+ 7 种一维码（384 宽条），带内容校验。
 * 输出白底黑点 Bitmap，走图片通道打印。
 */
object BarcodeGenerator {

    /** 条码类型 */
    data class BarcodeType(val label: String, val format: BarcodeFormat, val hint: String)

    val TYPES: List<BarcodeType> = listOf(
        BarcodeType("二维码 QR", BarcodeFormat.QR_CODE, "任意文字/链接，支持中文"),
        BarcodeType("Code128", BarcodeFormat.CODE_128, "任意 ASCII 字符"),
        BarcodeType("Code39", BarcodeFormat.CODE_39, "字母数字及 - . 空格 $ / + %"),
        BarcodeType("EAN-13", BarcodeFormat.EAN_13, "12~13 位数字（自动补校验位）"),
        BarcodeType("EAN-8", BarcodeFormat.EAN_8, "7~8 位数字"),
        BarcodeType("UPC-A", BarcodeFormat.UPC_A, "11~12 位数字"),
        BarcodeType("ITF", BarcodeFormat.ITF, "偶数位数字"),
        BarcodeType("Codabar", BarcodeFormat.CODABAR, "数字及 - $ : / . +"),
    )

    /** 内容校验：返回 null=有效，否则返回原因（仿 Windows 版 validate_barcode） */
    fun validate(type: BarcodeType, data: String): String? {
        val d = data.trim()
        if (d.isEmpty()) return "内容为空"
        if (type.format == BarcodeFormat.QR_CODE) {
            if (d.toByteArray(Charsets.UTF_8).size > 1500) return "内容过长（二维码容量限制）"
            return null
        }
        return try {
            encodeBitmap(type, d, verifyOnly = true)
            null
        } catch (e: Exception) {
            type.hint
        }
    }

    /**
     * 生成条码位图。QR：方块 320x320；一维码：384 宽 x 160 高条。
     * @param verifyOnly 只校验合法性（编码即校验）
     */
    @Throws(Exception::class)
    fun encodeBitmap(type: BarcodeType, data: String, verifyOnly: Boolean = false): Bitmap {
        val d = data.trim()
        val hints = mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix: BitMatrix = if (type.format == BarcodeFormat.QR_CODE) {
            QRCodeWriter().encode(d, BarcodeFormat.QR_CODE, 320, 320, hints)
        } else {
            MultiFormatWriter().encode(d, type.format, 384, 160, hints)
        }
        if (verifyOnly) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return matrixToBitmap(matrix)
    }

    /** BitMatrix → 白底黑点 Bitmap */
    private fun matrixToBitmap(matrix: BitMatrix): Bitmap {
        val w = matrix.width
        val h = matrix.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }
}
