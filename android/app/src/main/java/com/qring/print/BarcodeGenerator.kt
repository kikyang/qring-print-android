package com.qring.print

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import java.util.Locale

/**
 * 条码/二维码生成（2026-08-11 加，参考 QrintPrint-Windows 的 render.py；2026-08-27 扩到 13 种
 * zxing 可写码制 + 输入清洗/校验位重算）。
 *
 * zxing core 3.5.3 里**可生成**(writable)的 BarcodeFormat 共 13 种：QR、Code128、Code39、Code93、
 * EAN-13、EAN-8、UPC-A、UPC-E、ITF、Codabar、DataMatrix、Aztec、PDF-417。
 * （zxing 的 RSS/RSS_EXPANDED/MAXICODE 只有解码没有编码，无法生成，故不在列。）
 *
 * 两类逻辑：
 * - [clean]：输入清洗（纯 Kotlin，可单测）——EAN/UPC/ITF 仅留数字、EAN 家族不改数只校校验位、
 *   ITF 奇数位补前导 0、Code39/93 转大写、Codabar 去空白转大写。
 * - [validate]：合法性校验（纯 Kotlin，可单测）——空值、字符集、长度、EAN/UPC 校验位。
 *   校验通过不代表 zxing 一定编码成功（个别码制细节交由编码器兜底并抛异常），
 *   但常见错误（空/长度/非法字符/校验位错）都会被提前拦截并给出可读提示。
 *
 * [encodeBitmap]：真正渲染白底黑点 Bitmap，走图片通道打印。
 */
object BarcodeGenerator {

    /** 条码类型 */
    data class BarcodeType(val label: String, val format: BarcodeFormat, val hint: String)

    val TYPES: List<BarcodeType> = listOf(
        BarcodeType("二维码 QR", BarcodeFormat.QR_CODE, "任意文字/链接，支持中文（≤约 2000 字节）"),
        BarcodeType("Code128", BarcodeFormat.CODE_128, "任意 ASCII 字符（字母/数字/符号）"),
        BarcodeType("Code39", BarcodeFormat.CODE_39, "大写字母、数字及 - . 空格 $ / + %"),
        BarcodeType("Code93", BarcodeFormat.CODE_93, "大写字母、数字及 - . 空格 $ / + %"),
        BarcodeType("EAN-13", BarcodeFormat.EAN_13, "12~13 位数字（自动补校验位）"),
        BarcodeType("EAN-8", BarcodeFormat.EAN_8, "7~8 位数字（自动补校验位）"),
        BarcodeType("UPC-A", BarcodeFormat.UPC_A, "11~12 位数字（自动补校验位）"),
        BarcodeType("UPC-E", BarcodeFormat.UPC_E, "7~8 位数字（消零压缩，自动补校验位）"),
        BarcodeType("ITF", BarcodeFormat.ITF, "数字（偶数位，奇数自动补前导 0）"),
        BarcodeType("Codabar", BarcodeFormat.CODABAR, "数字及 - $ : / . +（可带 A-D 起止符）"),
        BarcodeType("DataMatrix", BarcodeFormat.DATA_MATRIX, "ASCII 文字/数字（容量较大）"),
        BarcodeType("Aztec", BarcodeFormat.AZTEC, "ASCII 文字/数字（容量大）"),
        BarcodeType("PDF-417", BarcodeFormat.PDF_417, "较长 ASCII 文字/数字（容量最大）"),
    )

    // ---------- 输入清洗（纯逻辑） ----------

    /** 输入清洗：去处非法字符/规范化，返回真正会交给编码器的内容。 */
    fun clean(type: BarcodeType, data: String): String {
        val raw = data.trim()
        var s: String = when (type.format) {
            BarcodeFormat.EAN_13, BarcodeFormat.EAN_8,
            BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
            BarcodeFormat.ITF -> raw.filter { it.isDigit() }
            BarcodeFormat.CODE_39, BarcodeFormat.CODE_93 -> raw.uppercase(Locale.ROOT)
            BarcodeFormat.CODABAR -> raw.filter { !it.isWhitespace() }.uppercase(Locale.ROOT)
            else -> raw
        }
        // ITF 必须偶数位；奇数位补前导 0（输入清洗易用性）
        if (type.format == BarcodeFormat.ITF && s.length % 2 == 1) s = "0$s"
        return s
    }

    // ---------- 合法性校验（纯逻辑） ----------

    /** 内容校验：返回 null=有效，否则返回原因（仿 Windows 版 validate_barcode，但纯逻辑化）。 */
    fun validate(type: BarcodeType, data: String): String? {
        val d = clean(type, data)
        if (d.isEmpty()) return "内容为空"
        return when (type.format) {
            BarcodeFormat.QR_CODE ->
                if (d.toByteArray(Charsets.UTF_8).size > 2000) "内容过长（二维码容量限制）" else null
            BarcodeFormat.CODE_128 ->
                if (d.any { it.code < 32 || it.code > 126 }) "仅支持 ASCII 可打印字符（字母/数字/符号）" else null
            BarcodeFormat.CODE_39, BarcodeFormat.CODE_93 ->
                if (d.any { it !in code39Set }) "仅支持大写字母、数字及 - . 空格 \$ / + %" else null
            BarcodeFormat.EAN_13 -> checkEan(d, 12, 13, "EAN-13")
            BarcodeFormat.EAN_8 -> checkEan(d, 7, 8, "EAN-8")
            BarcodeFormat.UPC_A -> checkEan(d, 11, 12, "UPC-A")
            BarcodeFormat.UPC_E ->
                if (d.length !in 7..8) "UPC-E 需 7~8 位数字"
                else if (d.length == 8) {
                    val expected = mod10Check(d.substring(0, 7))
                    if (d.last() != expected) "UPC-E 校验位应为 $expected（实际 ${d.last()}）" else null
                } else null   // 7 位，交给编码器自动补校验位
            BarcodeFormat.ITF ->
                if (d.length % 2 != 0) "ITF 需偶数位数字" else null
            BarcodeFormat.CODABAR ->
                if (d.any { it !in codebarSet }) "仅支持数字及 - \$ : / . +（可带 A-D 起止符）" else null
            BarcodeFormat.DATA_MATRIX ->
                if (d.toByteArray(Charsets.UTF_8).size > 1556) "内容过长（DataMatrix 容量限制）" else null
            BarcodeFormat.AZTEC ->
                if (d.toByteArray(Charsets.UTF_8).size > 3000) "内容过长（Aztec 容量限制）" else null
            BarcodeFormat.PDF_417 ->
                if (d.toByteArray(Charsets.UTF_8).size > 2000) "内容过长（PDF-417 容量限制）" else null
            else -> null
        }
    }

    // Code39/93 合法字符集（大写字母、数字、- . 空格 $ / + %；* 为起止符不入数据）
    private val code39Set: Set<Char> =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. $/+%".toSet()

    // Codabar 合法字符集（数字、- $ : / . +、可选 A-D 起止符）
    private val codebarSet: Set<Char> =
        "0123456789-\$:/.+ABCD".toSet()

    /**
     * EAN/UPC 校验（mod-10，权重从最右数据位起 3,1,3,1…）。
     * 输入已清洗为纯数字。长度 = dataLen（缺校验位，交给编码器补）或 fullLen（含校验位，需核验）。
     * 返回 null=通过，否则原因。
     */
    private fun checkEan(d: String, dataLen: Int, fullLen: Int, name: String): String? {
        if (d.length !in dataLen..fullLen) return "$name 需 ${dataLen}~${fullLen} 位数字"
        if (d.length == fullLen) {
            val expected = mod10Check(d.substring(0, dataLen))
            if (d.last() != expected) return "$name 校验位应为 $expected（实际 ${d.last()}）"
        }
        return null
    }

    /** mod-10 校验位计算（dataDigits 不含校验位；最右数据位权重 3）。 */
    private fun mod10Check(dataDigits: String): Char {
        var sum = 0
        val n = dataDigits.length
        for (i in 0 until n) {
            val digit = dataDigits[i] - '0'
            val weight = if ((n - 1 - i) % 2 == 0) 3 else 1
            sum += digit * weight
        }
        return ('0' + (10 - sum % 10) % 10)
    }

    // ---------- 渲染（Android Bitmap 层） ----------

    /** 各码制造型区域：2D 方形，PDF-417 宽些，1D 宽条。 */
    private fun dims(format: BarcodeFormat): Pair<Int, Int> = when (format) {
        BarcodeFormat.PDF_417 -> 384 to 240
        BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC -> 320 to 320
        else -> 384 to 160   // 1D
    }

    /**
     * 生成条码位图。QR/DataMatrix/Aztec：320x320；PDF-417：384x240；一维码：384x160 宽条。
     * 内部先 [clean] 再编码，保证清洗与校验一致。
     * @param verifyOnly 只校验合法性（编码即校验）
     */
    @Throws(Exception::class)
    fun encodeBitmap(type: BarcodeType, data: String, verifyOnly: Boolean = false): Bitmap {
        val d = clean(type, data)
        val hints = mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val (w, h) = dims(type.format)
        val matrix: BitMatrix = if (type.format == BarcodeFormat.QR_CODE) {
            QRCodeWriter().encode(d, BarcodeFormat.QR_CODE, w, h, hints)
        } else {
            MultiFormatWriter().encode(d, type.format, w, h, hints)
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
