package com.qring.print

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 函数表达式解析/求值（2026-08-27 加，函数图像打印用）。
 *
 * 支持：
 * - 运算符 + - * / % ^（^ 右结合、优先于一元负号外的 * /）
 * - 括号嵌套
 * - 一元正负号（-x^2 = -(x^2)）
 * - 变量 x；常量 pi、e
 * - 函数 sin cos tan sqrt abs ln log（log = log10）
 *
 * 仅**语法**错误抛 [EvalException]；数值域问题（1/0、sqrt(-1)、ln(0)）按 IEEE 返回
 * Infinity/NaN 不抛——这样 `validate` 检测结构错误时不误伤合法表达式。
 */
object ExpressionEvaluator {

    class EvalException(message: String) : Exception(message)

    /** 求值 y=f(x)。@throws EvalException 语法错误 */
    fun evaluate(expr: String, x: Double): Double {
        val p = Parser(expr, x)
        val v = p.parseExpr()
        if (p.any()) throw EvalException("多余字符：${p.rest()}")
        return v
    }

    /** 语法校验（不求值语义）。合法返回 null，否则错误信息。 */
    fun validate(expr: String): String? = try {
        evaluate(expr, 1.0)
        null
    } catch (e: EvalException) {
        e.message
    }

    private class Parser(private val s: String, private val x: Double) {
        private var i = 0

        fun parseExpr(): Double {
            var v = parseTerm()
            while (true) {
                skipWs()
                if (!any()) return v
                when (s[i]) {
                    '+' -> { i++; v += parseTerm() }
                    '-' -> { i++; v -= parseTerm() }
                    else -> return v
                }
            }
        }

        private fun parseTerm(): Double {
            var v = parseFactor()
            while (true) {
                skipWs()
                if (!any()) return v
                when (s[i]) {
                    '*' -> { i++; v *= parseFactor() }
                    '/' -> { i++; v /= parseFactor() }
                    '%' -> { i++; v %= parseFactor() }
                    else -> return v
                }
            }
        }

        private fun parseFactor(): Double {
            skipWs()
            var sign = 1.0
            if (any() && (s[i] == '+' || s[i] == '-')) {
                if (s[i] == '-') sign = -1.0
                i++
            }
            var base = parseAtom()
            skipWs()
            if (any() && s[i] == '^') {
                i++
                val exp = parseFactor()   // 右结合：2^3^2 = 2^(3^2)
                base = base.pow(exp)
            }
            return sign * base
        }

        private fun parseAtom(): Double {
            skipWs()
            if (!any()) throw EvalException("表达式不完整")
            return when {
                s[i].isDigit() || s[i] == '.' -> parseNumber()
                s[i] == '(' -> {
                    i++
                    val v = parseExpr()
                    skipWs()
                    expect(')')
                    v
                }
                s[i] == 'x' || s[i] == 'X' -> { i++; x }
                else -> {
                    val name = readName()
                    if (name.isEmpty()) throw EvalException("无法识别：'${s[i]}'")
                    when (name) {
                        "pi" -> kotlin.math.PI
                        "e" -> kotlin.math.E
                        "sin", "cos", "tan", "sqrt", "abs", "ln", "log" -> {
                            skipWs()
                            expect('(')
                            val arg = parseExpr()
                            skipWs()
                            expect(')')
                            applyFun(name, arg)
                        }
                        else -> throw EvalException("未知符号：$name")
                    }
                }
            }
        }

        private fun parseNumber(): Double {
            val start = i
            while (any() && (s[i].isDigit() || s[i] == '.')) i++
            val tok = s.substring(start, i)
            return tok.toDoubleOrNull() ?: throw EvalException("数字格式错误：$tok")
        }

        private fun readName(): String {
            val start = i
            while (any() && (s[i].isLetter() || s[i].isDigit() || s[i] == '_')) i++
            return s.substring(start, i)
        }

        private fun applyFun(name: String, arg: Double): Double = when (name) {
            "sin" -> sin(arg)
            "cos" -> cos(arg)
            "tan" -> tan(arg)
            "sqrt" -> sqrt(arg)
            "abs" -> abs(arg)
            "ln" -> ln(arg)
            "log" -> log10(arg)
            else -> throw EvalException("未知函数：$name")   // 不可达
        }

        private fun skipWs() { while (any() && s[i].isWhitespace()) i++ }
        fun any() = i < s.length
        fun rest() = s.substring(i)
        private fun expect(c: Char) {
            if (!any() || s[i] != c) throw EvalException("期望 '$c'，实际 ${if (any()) "'${s[i]}'" else "结尾"}")
            i++
        }
    }
}
