package com.qring.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 函数表达式求值测试（2026-08-27 加）：
 * 四则/幂/括号/一元负号/常量/函数/变量/空白/语法错误。
 */
class ExpressionEvaluatorTest {

    private fun ev(expr: String, x: Double = 0.0): Double =
        ExpressionEvaluator.evaluate(expr, x)

    private fun close(a: Double, b: Double, eps: Double = 1e-9) =
        assertTrue("$a ≈ $b", Math.abs(a - b) < eps)

    @Test
    fun `四则运算 优先级`() {
        close(ev("1+2*3"), 7.0)
        close(ev("10-2*3"), 4.0)
        close(ev("7/2"), 3.5)
        close(ev("7%3"), 1.0)
    }

    @Test
    fun `括号`() {
        close(ev("(1+2)*3"), 9.0)
        close(ev("2*(3+4)"), 14.0)
        close(ev("((2+3))"), 5.0)
    }

    @Test
    fun `幂 右结合`() {
        close(ev("2^3"), 8.0)
        close(ev("2^3^2"), 512.0)   // 2^(3^2)
        close(ev("4^0.5"), 2.0)
    }

    @Test
    fun `一元负号`() {
        close(ev("-2+3"), 1.0)
        close(ev("2*-3"), -6.0)
        close(ev("-x^2", 2.0), -4.0)   // -(x^2)
        close(ev("(-x)^2", 2.0), 4.0)
        close(ev("2^-3"), 0.125)       // 2^(-3)
    }

    @Test
    fun `常量 pi e`() {
        close(ev("pi"), Math.PI)
        close(ev("2*pi"), 2 * Math.PI)
        close(ev("e"), Math.E)
    }

    @Test
    fun `函数`() {
        close(ev("sin(0)"), 0.0)
        close(ev("cos(0)"), 1.0)
        close(ev("sqrt(9)"), 3.0)
        close(ev("abs(-5)"), 5.0)
        close(ev("ln(1)"), 0.0)
        close(ev("log(100)"), 2.0)    // log10
    }

    @Test
    fun `变量 x`() {
        close(ev("2*x+1", 3.0), 7.0)
        close(ev("x^2", 3.0), 9.0)
        close(ev("sin(x)", Math.PI / 2), 1.0)
    }

    @Test
    fun `空白忽略`() {
        close(ev(" 1 + 2 "), 3.0)
        close(ev("( 1 + 2 ) * 3"), 9.0)
    }

    @Test
    fun `小数`() {
        close(ev("0.5*2"), 1.0)
        close(ev(".5+.5"), 1.0)
        close(ev("1.25+1.25"), 2.5)
    }

    @Test
    fun `复合表达式`() {
        close(ev("sin(x)+x/5", 0.0), 0.0)
        close(ev("sqrt(abs(x-4))", 0.0), 2.0)
        close(ev("ln(x)+1", Math.E), 2.0)
    }

    @Test
    fun `数值域不抛异常 返回Infinity或NaN`() {
        // 1/0 → Infinity（不抛），sqrt(-1) → NaN（不抛）
        assertTrue(ev("1/x", 0.0).isInfinite())
        assertTrue(ev("sqrt(-1)").isNaN())
        assertTrue(ev("ln(0)").isInfinite())
    }

    @Test
    fun `语法错误 validate 返回消息`() {
        assertNotNull("空串", ExpressionEvaluator.validate(""))
        assertNotNull("1+", ExpressionEvaluator.validate("1+"))
        assertNotNull("2**3", ExpressionEvaluator.validate("2**3"))
        assertNotNull("未知符号", ExpressionEvaluator.validate("foo(1)"))
        assertNotNull("多余字符", ExpressionEvaluator.validate("1+2)"))
        assertNotNull("缺右括号", ExpressionEvaluator.validate("(1+2"))
        assertNotNull("缺失数", ExpressionEvaluator.validate("1+*2"))
    }

    @Test
    fun `合法表达式 validate 返回 null`() {
        assertNull("x^2", ExpressionEvaluator.validate("x^2"))
        assertNull("1/x 合法（不求值）", ExpressionEvaluator.validate("1/x"))
        assertNull("sin(x)+x", ExpressionEvaluator.validate("sin(x)+x"))
    }

    @Test
    fun `中文函数名报错`() {
        assertNotNull(ExpressionEvaluator.validate("sin(1)中文"))
    }

    @Test
    fun `evaluate 抛 EvalException`() {
        try {
            ev("1+")
            assertTrue("应抛异常", false)
        } catch (e: ExpressionEvaluator.EvalException) {
            assertTrue(true)
        }
    }

    @Test
    fun `X大小写均可`() {
        close(ev("X*2", 3.0), 6.0)
    }
}
