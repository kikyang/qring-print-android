package com.qring.print

/**
 * Markdown 轻量解析器（2026-08-14 加，纯 Kotlin 零依赖，JVM 全测）。
 *
 * 支持的块级：标题(1-6)、段落、无序/有序列表、代码块(fence)、引用、分割线。
 * 支持的 inline：粗体 / 斜体 / 行内代码 / 链接（只取文字），可一层嵌套。
 * 行数上限 [MAX_LINES] 防超长文档无休止解析。
 */
object MarkdownParser {

    /** 解析行数上限（超长直接截断，防 OOM/卡死） */
    const val MAX_LINES = 2000

    sealed class Block {
        data class Heading(val level: Int, val inline: List<Inline>) : Block()
        data class Paragraph(val inline: List<Inline>) : Block()
        data class BulletList(val items: List<List<Inline>>) : Block()
        data class OrderedList(val items: List<List<Inline>>, val start: Int = 1) : Block()
        data class CodeBlock(val lang: String, val content: String) : Block()
        data class Blockquote(val lines: List<String>) : Block()
        object ThematicBreak : Block()
    }

    sealed class Inline {
        data class Text(val text: String) : Inline()
        data class Bold(val content: List<Inline>) : Inline()
        data class Italic(val content: List<Inline>) : Inline()
        data class Code(val text: String) : Inline()
        data class Link(val text: String, val url: String) : Inline()
    }

    // ── 块级正则 ──

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val THEME = Regex("^(-{3,}|\\*{3,}|_{3,})\\s*$")
    private val BULLET = Regex("^[-*+]\\s+(.*)$")
    private val ORDERED = Regex("^(\\d+)[.)]\\s+(.*)$")
    private val FENCE = Regex("^(`{3,}|~{3,})")

    /** 解析整个 Markdown 文本为块序列（一个 pass + fence 状态机） */
    fun parse(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        var para = mutableListOf<String>()
        var bullets = mutableListOf<String>()
        var ordered = mutableListOf<Pair<Int, String>>()
        var quote = mutableListOf<String>()
        var code = mutableListOf<String>()
        var codeLang = ""
        var inFence = false

        fun flushPara() { if (para.isNotEmpty()) { blocks.add(Block.Paragraph(parseInline(para.joinToString("\n")))); para = mutableListOf() } }
        fun flushBullets() { if (bullets.isNotEmpty()) { blocks.add(Block.BulletList(bullets.map { parseInline(it) })); bullets = mutableListOf() } }
        fun flushOrdered() { if (ordered.isNotEmpty()) { blocks.add(Block.OrderedList(ordered.map { parseInline(it.second) }, ordered.first().first)); ordered = mutableListOf() } }
        fun flushQuote() { if (quote.isNotEmpty()) { blocks.add(Block.Blockquote(quote.toList())); quote = mutableListOf() } }
        fun flushOthers() { flushPara(); flushBullets(); flushOrdered(); flushQuote() }

        var count = 0
        for (raw in markdown.split("\n")) {
            if (++count > MAX_LINES) {
                blocks.add(Block.Paragraph(parseInline("…内容过长，已截断")))
                break
            }
            val line = raw.trimEnd()
            val t = line.trim()

            // fence 内部：原样收集，直到遇到闭合记号
            if (inFence) {
                if (FENCE.containsMatchIn(t)) {
                    inFence = false
                    blocks.add(Block.CodeBlock(codeLang, code.joinToString("\n")))
                    code = mutableListOf(); codeLang = ""
                } else {
                    code.add(line)
                }
                continue
            }
            if (FENCE.containsMatchIn(t)) {
                flushOthers()
                inFence = true
                val marker = FENCE.find(t)!!
                codeLang = t.substring(marker.range.last + 1).trim()
                code = mutableListOf()
                continue
            }

            if (t.isEmpty()) { flushOthers(); continue }

            // 标题
            val heading = HEADING.matchEntire(t)
            if (heading != null) {
                flushOthers()
                blocks.add(Block.Heading(heading.groupValues[1].length, parseInline(heading.groupValues[2])))
                continue
            }
            // 分割线（先于列表判定，`---` 不能当列表项）
            if (THEME.matchEntire(t) != null) {
                flushOthers()
                blocks.add(Block.ThematicBreak)
                continue
            }
            // 引用
            if (t.startsWith(">")) {
                flushPara(); flushBullets(); flushOrdered()
                quote.add(t.removePrefix(">").trimStart())
                continue
            }
            // 无序列表
            val bullet = BULLET.matchEntire(t)
            if (bullet != null) {
                flushPara(); flushOrdered(); flushQuote()
                bullets.add(bullet.groupValues[1])
                continue
            }
            // 有序列表
            val orderedItem = ORDERED.matchEntire(t)
            if (orderedItem != null) {
                flushPara(); flushBullets(); flushQuote()
                ordered.add(orderedItem.groupValues[1].toInt() to orderedItem.groupValues[2])
                continue
            }
            // 普通段落（连续非空行并入一段）
            flushBullets(); flushOrdered(); flushQuote()
            para.add(t)
        }
        flushOthers()
        // 未闭合 fence 兜底为代码块（内容不丢失）
        if (inFence) blocks.add(Block.CodeBlock(codeLang, code.joinToString("\n")))
        return blocks
    }

    /**
     * 行内解析：单个 pass 扫描 ``code`` / **bold** / *italic* / [text](url)，
     * 支持一层嵌套（粗体内可有斜体/行内码）。不成对的标记按字面文本保留。
     */
    fun parseInline(text: String): List<Inline> {
        val out = mutableListOf<Inline>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                // 行内代码
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        out.add(Inline.Code(text.substring(i + 1, end)))
                        i = end + 1
                    } else i++
                }
                // 粗体 **
                c == '*' && i + 1 < n && text[i + 1] == '*' -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i) {
                        out.add(Inline.Bold(parseInline(text.substring(i + 2, end))))
                        i = end + 2
                    } else i++
                }
                // 斜体 *
                c == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i) {
                        out.add(Inline.Italic(parseInline(text.substring(i + 1, end))))
                        i = end + 1
                    } else i++
                }
                // 链接 [text](url)
                c == '[' -> {
                    val close = text.indexOf(']', i + 1)
                    if (close > i && close + 1 < n && text[close + 1] == '(') {
                        val parenEnd = text.indexOf(')', close + 2)
                        if (parenEnd > close) {
                            out.add(Inline.Link(text.substring(i + 1, close), text.substring(close + 2, parenEnd)))
                            i = parenEnd + 1
                        } else i++
                    } else i++
                }
                // 普通文本 run
                else -> {
                    var j = i
                    while (j < n && text[j] != '`' && text[j] != '*' && text[j] != '[') j++
                    if (j > i) {
                        out.add(Inline.Text(text.substring(i, j)))
                        i = j
                    } else i++
                }
            }
        }
        return out
    }
}
