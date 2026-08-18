# 上游 GitHub 巡检报告（2026-08-18）

> 周期：每日收集（攒批后再统一动手）| 关键词：「错题小印」「qring」
> 对比基线：upstream-audit-2026-08-16.md（8-17 做过只读巡检未落盘，本轮合并记录）

## 一、本轮发现

### 已知仓库动态

| 仓库 | 状态 | 变化 |
|---|---|---|
| Thisko/QrintPrint（HarmonyOS） | 无变化 | 停在 8-10 |
| lztttt/QrintPrint-Android | 无变化 | 停在 8-16 v1.5.0 |
| soulxyz/xyprt_android | 无功能变化 | pushed 8-17 晚，仍为 star-history 自动刷新，main 无新提交 |
| snowboys/QrintPrint-Windows | 无变化 | 停在 8-12 |
| bzhou830/QringPrint | 无变化 | 停在 8-07 |
| kikyang/qring-print-android（我方） | v0.7.0 | 8-16 已发 v0.7.0，无新提交 |

### 观察名单动态

| 仓库 | 状态 | 变化 |
|---|---|---|
| yiran168/suda-win-web | 新增观察 | 8-16 创建，8-17 仍在更新；Windows/Web 端，MIT，含自研 Word 排版、公式排版、过热续打、多份打印 |
| ZhaYi-Miao/QrintPrint-Windows | 有更新 | 8-16 v1.1.2：画布编辑器大升级、文字增强、过热续打、多份打印、检查更新 |
| ZhaYi-Miao/QrintPrint-Web-Console | 无变化 | 停在 8-13 |
| BA4RFY/QringAndroid | 无变化 | 源码包仍在 |
| tanadiejiang/pocket_print | 无变化 | 停在 8-12 |
| Thisko/QrintPrint-Web | 不可访问 | API 仍 404，维持移除观察 |

### 新仓库

- 无新增相关仓库。
- `qring` 检索中出现的 `elifsuttatli-alt/qringpi`、`fitaccessng/qring_*` 等仍为无关项目，排除。

## 二、下一版候选池（持续累积）

| 候选 | 来源 | 状态 |
|---|---|---|
| PPT 导入 | 用户确认 | 已记待办 #17 |
| 公式排版 + 自研 Word/PPT/Excel 解析排版引擎 | yiran168/suda-win-web | 已记待办 #18 |
| 多份打印 | ZhaYi-Miao v1.1.2 / suda-win-web / 用户确认 | 已记待办 #19 |
| 多界面/UI 主题可选 | 用户偏好 xyprt 简洁风 + 当前微信风 | 已记待办 #20 |
| 过热行级断点续打 | ZhaYi-Miao / suda-win-web | 观察，暂未排期 |
| CSV 自定义表格 / 标签设置 | BA4RFY/QringAndroid | 观察，暂未排期 |

## 三、结论

- 今天无紧急新功能需要立刻合入。
- 继续按“每日收集、攒批动手”的方式执行。
