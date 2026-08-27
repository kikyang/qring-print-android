package com.qring.print

/**
 * 变量数据批量打印的模板绑定（2026-08-27 加）。
 *
 * 模板含 `{{列名}}` 占位符，逐条记录替换；可选递增流水号 `{{序号}}`。
 * 绑定规则：先按列名替换（列名与占位符同名时列值优先），
 * 再按需注入 `{{序号}}`（从 1 开始）。未匹配的占位符保留原文（预览可见，
 * 提醒列名写错）。
 */
object BatchTemplate {

    /**
     * 绑定一条记录到模板。
     * @param template 含 {{列名}} 占位符的模板文本
     * @param row 一条记录（列名→值）
     * @param serial 当前流水号（从 1 开始）
     * @param serialEnabled 是否注入 {{序号}}
     */
    fun bind(template: String, row: Map<String, String>, serial: Int, serialEnabled: Boolean): String {
        var out = template
        for ((k, v) in row) out = out.replace("{{$k}}", v)
        if (serialEnabled) out = out.replace("{{序号}}", serial.toString())
        return out
    }
}
