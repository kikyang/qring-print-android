package com.qring.print

import org.json.JSONArray

/**
 * 系统模板注册表（#5e 模板系统打通）。
 *
 * 常用模板宫格的数据源改为「内置 JSON」，不再把模板定义硬编码在 UI 代码里。
 * 每一条 = 宫格图标(icon) + 显示名(label) + 生成动作键(build)。
 * 生成动作键 → 实际生成逻辑的分发在 MainActivity.runSystemTemplate 里
 * （课程表/单词表/每日计划走纯位图绘制，口算题走弹窗）。
 * 扩展新系统模板只需在 REGISTRY_JSON 加一行。
 */
data class SystemTemplate(
    val icon: String,   // Design.Icons 图标名（assets/icons/<name>.png）
    val label: String,  // 宫格显示名
    val build: String,  // 生成动作键，见 ACTION_*
)

object SystemTemplates {

    const val ACTION_COURSE = "courseTable"
    const val ACTION_WORD = "wordList"
    const val ACTION_PLAN = "dailyPlan"
    const val ACTION_MATH = "mathDialog"

    /** 内置 JSON 注册表：系统模板清单（含口算题弹窗） */
    private val REGISTRY_JSON = """
        [
          { "icon": "course", "label": "课程表",   "build": "courseTable" },
          { "icon": "word",   "label": "单词表",   "build": "wordList" },
          { "icon": "plan",   "label": "每日计划", "build": "dailyPlan" },
          { "icon": "math",   "label": "口算题",   "build": "mathDialog" }
        ]
    """.trimIndent()

    /** 解析内置 JSON → 模板列表（字段缺失的行跳过） */
    fun load(): List<SystemTemplate> {
        val arr = JSONArray(REGISTRY_JSON)
        val out = ArrayList<SystemTemplate>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val label = o.optString("label")
            if (label.isEmpty()) continue
            out.add(SystemTemplate(
                icon = o.optString("icon"),
                label = label,
                build = o.optString("build"),
            ))
        }
        return out
    }
}
