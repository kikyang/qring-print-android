package com.qring.print

import android.content.Context
import android.net.Uri
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * docx OOXML 布局提取（2026-08-18 简化移植自 yiran168/suda-win-web docxParser.ts）。
 *
 * 用 DOM 解析 word/document.xml，保留段落、文字 run（粗体/字号）、OMML 公式、表格。
 * 不还原分栏/文本框等复杂版式；目的是让 Word 文档中的公式能以自研公式排版打印。
 */

sealed class DocBlock
data class DocParagraph(val inlines: List<DocInline>) : DocBlock()
data class DocTable(val rows: List<List<DocCell>>) : DocBlock()
data class DocCell(val blocks: List<DocBlock>)

sealed class DocInline
data class DocText(val text: String, val bold: Boolean, val size: Int) : DocInline()
data class DocMath(val nodes: List<MathNode>) : DocInline()

data class DocLayout(
    val blocks: List<DocBlock>,
    val hasMath: Boolean,
)

object DocxLayoutExtractor {

    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val M_NS = "http://schemas.openxmlformats.org/officeDocument/2006/math"

    fun extract(context: Context, uri: Uri): DocLayout {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法打开 Word 文件")
        var documentXml: ByteArray? = null
        input.use { fileIn ->
            ZipInputStream(fileIn.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        documentXml = zip.readBytes()
                        break
                    }
                    entry = zip.nextEntry
                }
            }
        }
        val bytes = documentXml ?: throw IllegalStateException("不是有效的 Word (.docx) 文件")
        val dbFactory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val doc = dbFactory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val body = doc.getElementsByTagNameNS(W_NS, "body").item(0) as? Element
            ?: throw IllegalStateException("Word 文档没有正文")
        var hasMath = false
        val blocks = parseBlocks(body) { hasMath = true }
        return DocLayout(blocks, hasMath)
    }

    private fun parseBlocks(parent: Element, onMath: () -> Unit): List<DocBlock> {
        val out = mutableListOf<DocBlock>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node !is Element) continue
            when (node.localName) {
                "p" -> out.add(parseParagraph(node, onMath))
                "tbl" -> out.add(parseTable(node, onMath))
                "sdt" -> {
                    val content = childByLocal(node, "sdtContent")
                    if (content != null) out.addAll(parseBlocks(content, onMath))
                }
            }
        }
        return out
    }

    private fun parseParagraph(p: Element, onMath: () -> Unit): DocParagraph {
        val inlines = mutableListOf<DocInline>()
        fun walk(el: Element) {
            val children = el.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node !is Element) continue
                when (node.localName) {
                    "pPr", "bookmarkStart", "bookmarkEnd", "proofErr" -> {}
                    "r" -> parseRun(node)?.let { inlines.add(it) }
                    "oMath", "oMathPara" -> {
                        onMath()
                        inlines.add(DocMath(parseMathTop(node)))
                    }
                    else -> walk(node)
                }
            }
        }
        walk(p)
        return DocParagraph(inlines)
    }

    private fun parseRun(r: Element): DocInline? {
        val text = descendantsByLocal(r, "t")
            .filter { it.namespaceURI == W_NS }
            .joinToString("") { it.textContent ?: "" }
        if (text.isEmpty()) return null
        val bold = descendantsByLocal(r, "b").any { it.namespaceURI == W_NS }
        val szEl = descendantsByLocal(r, "sz").firstOrNull { it.namespaceURI == W_NS }
        val halfPt = szEl?.getAttributeNS(W_NS, "val")?.toIntOrNull() ?: 0
        val size = if (halfPt > 0) (halfPt * 203 / 144).coerceIn(14, 64) else 0
        return DocText(text, bold, size)
    }

    private fun parseTable(tbl: Element, onMath: () -> Unit): DocTable {
        val rows = mutableListOf<List<DocCell>>()
        for (tr in childrenByLocal(tbl, "tr")) {
            val cells = mutableListOf<DocCell>()
            for (tc in childrenByLocal(tr, "tc")) {
                cells.add(DocCell(parseBlocks(tc, onMath)))
            }
            if (cells.isNotEmpty()) rows.add(cells)
        }
        return DocTable(rows)
    }

    // ── OMML 公式解析 ─────────────────────────────────────────

    private fun parseMathTop(el: Element): List<MathNode> {
        if (el.localName == "oMathPara") {
            return childrenByLocal(el, "oMath").flatMap { mathChildren(it) }
        }
        return mathChildren(el)
    }

    private fun mathChildren(el: Element): List<MathNode> {
        val out = mutableListOf<MathNode>()
        val children = el.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && node.namespaceURI == M_NS) {
                mathNode(node)?.let { out.add(it) }
            }
        }
        return out
    }

    private fun mathNode(el: Element): MathNode? {
        val sub = { local: String -> childByLocal(el, local)?.let { mathChildren(it) } ?: emptyList() }
        return when (el.localName) {
            "r" -> {
                val text = descendantsByLocal(el, "t")
                    .filter { it.namespaceURI == M_NS }
                    .joinToString("") { it.textContent ?: "" }
                if (text.isEmpty()) null else MRun(text)
            }
            "f" -> MFrac(sub("num"), sub("den"))
            "sSup" -> MScript(sub("e"), sub("sup"), null)
            "sSub" -> MScript(sub("e"), null, sub("sub"))
            "sSubSup" -> MScript(sub("e"), sub("sup"), sub("sub"))
            "rad" -> {
                val degEl = childByLocal(el, "deg")
                MRad(if (degEl != null) mathChildren(degEl) else null, sub("e"))
            }
            "d" -> {
                val dPr = childByLocal(el, "dPr")
                val beg = childByLocal(dPr, "begChr")?.getAttributeNS(M_NS, "val") ?: "("
                val end = childByLocal(dPr, "endChr")?.getAttributeNS(M_NS, "val") ?: ")"
                MDelim(beg, end, sub("e"))
            }
            "bar" -> MBar(sub("e"))
            "nary" -> {
                val pr = childByLocal(el, "naryPr")
                val chr = childByLocal(pr, "chr")?.getAttributeNS(M_NS, "val") ?: "∑"
                val subHide = childByLocal(pr, "subHide") != null
                val supHide = childByLocal(pr, "supHide") != null
                MNary(
                    chr,
                    if (subHide) null else childByLocal(el, "sub")?.let { mathChildren(it) },
                    if (supHide) null else childByLocal(el, "sup")?.let { mathChildren(it) },
                    sub("e")
                )
            }
            "m" -> MMatrix(
                childrenByLocal(el, "mr").map { mr ->
                    childrenByLocal(mr, "e").map { mathChildren(it) }
                }
            )
            "eqArr" -> MMatrix(childrenByLocal(el, "e").map { listOf(mathChildren(it)) })
            else -> {
                val children = mathChildren(el)
                if (children.isEmpty()) null else {
                    // 未知容器：透明下探，内容不丢
                    if (children.size == 1) children[0] else MRun(children.joinToString(" ") { nodeToText(it) })
                }
            }
        }
    }

    private fun nodeToText(n: MathNode): String = when (n) {
        is MRun -> n.text
        is MFrac -> "(${n.num.joinToString("") { nodeToText(it) }}/${n.den.joinToString("") { nodeToText(it) }})"
        is MScript -> n.base.joinToString("") { nodeToText(it) }
        is MRad -> "√(${n.body.joinToString("") { nodeToText(it) }})"
        is MDelim -> "${n.beg}${n.body.joinToString("") { nodeToText(it) }}${n.end}"
        is MBar -> n.body.joinToString("") { nodeToText(it) }
        is MNary -> "${n.chr}(${n.body.joinToString("") { nodeToText(it) }})"
        is MMatrix -> "[${n.rows.joinToString(";") { row -> row.joinToString(",") { cells -> cells.joinToString("") { nodeToText(it) } } }}]"
    }

    // ── DOM helpers ───────────────────────────────────────────

    private fun childrenByLocal(el: Element?, local: String): List<Element> {
        if (el == null) return emptyList()
        val out = mutableListOf<Element>()
        val children = el.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && node.localName == local) out.add(node)
        }
        return out
    }

    private fun childByLocal(el: Element?, local: String): Element? =
        childrenByLocal(el, local).firstOrNull()

    private fun descendantsByLocal(el: Element, local: String): List<Element> {
        val out = mutableListOf<Element>()
        val list = el.getElementsByTagName("*")
        for (i in 0 until list.length) {
            val node = list.item(i)
            if (node is Element && node.localName == local) out.add(node)
        }
        return out
    }
}
