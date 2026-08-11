package com.qring.print

import android.content.Context
import android.net.Uri

/**
 * 老格式 Office 文档文本提取（.doc / .xls，2026-08-11 加，零依赖）。
 *
 * .doc/.xls 是 OLE2 复合文档（二进制），不是 zip——与 docx/xlsx 完全不同。
 * 实现：
 * 1. OLE2 头解析（魔数 D0CF11E0 + 扇区表 FAT + 目录流）读出目标流
 * 2. .doc：WordDocument 流按 FIB 的 fcMin/fcMac 取正文（UTF-16LE，简单文档连续存储）
 * 3. .xls：Workbook 流按 BIFF8 记录格式找 SST（0x00FC 共享字符串表）提取全部文本
 *
 * 简化边界：.doc 的复杂分片（piece table）/文本框/批注不提取；.xls 只提取字符串
 * 表（不还原单元格行列）。58mm 窄纸纯文本打印可接受，复杂文档建议转存 docx/xlsx。
 */
object LegacyDocExtractor {

    private val OLE2_MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte())

    /** 读 OLE2 复合文档中指定名称流的内容 */
    private fun readOleStream(bytes: ByteArray, streamName: String): ByteArray {
        if (bytes.size < 512 || !bytes.copyOfRange(0, 8).contentEquals(OLE2_MAGIC)) {
            throw IllegalStateException("\u4e0d\u662f\u6709\u6548\u7684 Office \u6587\u6863")
        }
        val sectorShift = u16(bytes, 0x1E)
        val sectorSize = 1 shl sectorShift
        val firstDirectorySector = u32(bytes, 0x2C)
        val firstFatSector = u32(bytes, 0x3C)

        // 读 FAT 表（可能跨多个 FAT 扇区，简化：链式读 FAT 扇区）
        val fatEntries = ArrayList<Int>()
        var fatSector = firstFatSector
        var guard = 0
        // 链终止符在 Int 表示下为负（0xFFFFFFF8+ 溢出），>0 判断即排除
        while (fatSector > 0 && guard++ < 1000) {
            val off = sectorOffset(fatSector, sectorSize)
            if (off + sectorSize > bytes.size) break
            for (i in 0 until sectorSize / 4) {
                fatEntries.add(u32(bytes, off + i * 4))
            }
            // 找下一个 FAT 扇区：FAT 自身也是链（FAT 扇区链在 FAT 表里）
            fatSector = fatEntries.getOrElse(fatSector) { 0xFFFFFFFE.toInt() }
        }

        fun chainBytes(startSector: Int, size: Int): ByteArray {
            val out = ByteArray(size)
            var sector = startSector
            var written = 0
            var g = 0
            while (sector > 0 && written < size && g++ < 100000) {
                val off = sectorOffset(sector, sectorSize)
                val len = minOf(sectorSize, size - written)
                System.arraycopy(bytes, off, out, written, len)
                written += len
                sector = fatEntries.getOrElse(sector) { 0xFFFFFFFE.toInt() }
            }
            return out
        }

        // 目录流：找目标条目
        val dirSize = bytes.size // 兜底：目录流大小从条目读，先给个上限
        val dirStream = chainBytes(firstDirectorySector, dirSize.coerceAtMost(1 shl 20))
        var nameBytes = byteArrayOf()
        var startSector = -1
        var streamSize = 0
        // 目录条目 128 字节一个，直到空条目（name 首字 0）
        var entryOff = 0
        while (entryOff + 128 <= dirStream.size) {
            val nameLen = u16(dirStream, entryOff)
            if (nameLen == 0) break
            // 防御（2026-08-11 真机崩）：异常文件的 nameLen 可为奇数/超界，
            // nameLen-2 会变成 -1 直接 String 越界（regionLength=-1 崩溃）
            val avail = (dirStream.size - entryOff - 2).coerceAtLeast(0)
            val nameLenBytes = nameLen.coerceAtMost(avail)
            val entryName = if (nameLenBytes >= 2) {
                String(dirStream, entryOff + 2, nameLenBytes, Charsets.UTF_16LE)
            } else {
                ""
            }
            val type = dirStream[entryOff + 66].toInt() and 0xFF
            if (type == 0x02 && entryName == streamName) {  // 0x02 = stream
                nameBytes = dirStream.copyOfRange(entryOff, entryOff + 128)
                startSector = u32(dirStream, entryOff + 0x74)
                streamSize = u32(dirStream, entryOff + 0x78)
                break
            }
            entryOff += 128
        }
        if (startSector < 0) throw IllegalStateException("\u6587\u6863\u7ed3\u6784\u5f02\u5e38\uff08\u627e\u4e0d\u5230 $streamName \u6d41\uff09")
        return chainBytes(startSector, streamSize)
    }

    private fun sectorOffset(sector: Int, sectorSize: Int): Int = 512 + sector * sectorSize

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    /**
     * .doc 文本提取：WordDocument 流 FIB fcMin/fcMac → UTF-16LE 正文。
     * FIB 布局：FibBase(32B) + csw(2B) + FibRgW(28B) + cslw(2B) + FibRgLw…
     * fcMin=0x18、fcMac=0x1C（相对 FIB 起始，0x00）。
     */
    fun extractDoc(context: Context, uri: Uri): List<String> {
        val bytes = readAll(context, uri)
        val wordStream = readOleStream(bytes, "WordDocument")
        // 防御：FIB 至少 0x20 字节，流过小则 u32 越界
        if (wordStream.size < 0x20) {
            throw IllegalStateException("\u6587\u6863\u7ed3\u6784\u5f02\u5e38\uff08WordDocument \u6d41\u8fc7\u5c0f\uff09")
        }
        val fcMin = u32(wordStream, 0x18)
        val fcMacRaw = u32(wordStream, 0x1C)
        // fcMac=0 表示文本到流尾
        val fcMac = if (fcMacRaw > fcMin && fcMacRaw <= wordStream.size) fcMacRaw else wordStream.size
        val start = fcMin.coerceIn(0, wordStream.size)
        val end = fcMac.coerceIn(start, wordStream.size)
        // 正文 UTF-16LE；非法字节对自动替换（简单文档无分片时直线可读）
        val text = String(wordStream, start, end - start, Charsets.UTF_16LE)
        // 按 0x07(单元格结束)/0x0B(段标记)/CR/LF 分段落
        return text.split('', '', '\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * .xls 文本提取：Workbook 流 BIFF8 记录 → SST(0x00FC) 共享字符串表。
     * 记录格式：u16 type + u16 length + data。SST 内 string：u16 cch + u8 flags
     * （bit0=1 → UTF-16LE，否则压缩单字节）+ 可选 4 字节 continue 对齐（简化跳过）。
     */
    fun extractXls(context: Context, uri: Uri): List<String> {
        val bytes = readAll(context, uri)
        val wb = readOleStream(bytes, "Workbook")
        val out = ArrayList<String>()
        var off = 0
        while (off + 4 <= wb.size) {
            val type = u16(wb, off)
            val len = u16(wb, off + 2)
            val data = off + 4
            if (data + len > wb.size) break
            if (type == 0x00FC) {  // SST
                var p = data + 8  // 跳过 cstTotal/cstUnique
                var unique = 0
                val cstUnique = u32(wb, data + 4)
                while (unique < cstUnique && p + 3 <= data + len) {
                    val cch = u16(wb, p)
                    val flags = wb[p + 2].toInt() and 0xFF
                    val utf16 = (flags and 0x01) != 0
                    p += 3
                    if (utf16) {
                        val charCount = minOf(cch, (data + len - p) / 2)
                        if (charCount > 0) out.add(String(wb, p, charCount * 2, Charsets.UTF_16LE))
                        p += cch * 2
                    } else {
                        val byteCount = minOf(cch, data + len - p)
                        if (byteCount > 0) out.add(String(wb, p, byteCount, Charsets.ISO_8859_1))
                        p += cch
                    }
                    // 对齐：字符串若跨 BIFF continue 边界有偏移调整，简化跳过
                    unique++
                }
                break
            }
            off = data + len
        }
        if (out.isEmpty()) throw IllegalStateException("\u672a\u80fd\u4ece .xls \u63d0\u53d6\u5230\u6587\u672c\uff0c\u5efa\u8bae\u53e6\u5b58\u4e3a xlsx")
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun readAll(context: Context, uri: Uri): ByteArray {
        // 大小上限（2026-08-11：OLE2 需全量读入，大文件 readBytes 会 OOM 闪退）
        val maxBytes = 30 * 1024 * 1024
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("\u65e0\u6cd5\u8bfb\u53d6\u6587\u4ef6")
        if (bytes.size > maxBytes) {
            throw IllegalStateException("\u6587\u4ef6\u8fc7\u5927\uff08>30MB\uff09\uff0c\u8bf7\u8f6c\u5b58\u4e3a docx/xlsx \u540e\u91cd\u8bd5")
        }
        return bytes
    }
}
