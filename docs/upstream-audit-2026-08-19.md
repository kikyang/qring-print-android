# 上游 GitHub 巡检报告（2026-08-19）

> 周期：每日收集 | 关键词：「错题小印」「qring」
> 对比基线：upstream-audit-2026-08-18.md

## 一、本轮发现

### 已知仓库动态

| 仓库 | 状态 | 变化 |
|---|---|---|
| Thisko/QrintPrint（HarmonyOS） | 无变化 | 停在 8-10 |
| lztttt/QrintPrint-Android | 无变化 | 停在 8-16 v1.5.0 |
| soulxyz/xyprt_android | 无功能变化 | pushed 8-19，仍为 star-history 自动刷新，main 无新提交 |
| snowboys/QrintPrint-Windows | 无变化 | 停在 8-12 |
| bzhou830/QringPrint | 无变化 | 停在 8-07 |
| kikyang/qring-print-android（我方） | v0.7.3 | 8-18 已发 v0.7.3，并更新 README 截图与待办清单 |

### 观察名单动态

| 仓库 | 状态 | 变化 |
|---|---|---|
| yiran168/suda-win-web | 无变化 | 停在 8-17 |
| ZhaYi-Miao/QrintPrint-Windows | 无变化 | 停在 8-16 v1.1.2 |
| ZhaYi-Miao/QrintPrint-Web-Console | 无变化 | 停在 8-13 |
| BA4RFY/QringAndroid | 无变化 | 源码包仍在 |
| tanadiejiang/pocket_print | 无变化 | 停在 8-12 |
| Thisko/QrintPrint-Web | 不可访问 | API 仍 404，维持移除观察 |

### 新仓库

- 无新增相关仓库。
- `qring` 检索中出现的 `elifsuttatli-alt/qringpi` 等仍为无关项目，排除。

## 二、候选池（持续累积）

| 候选 | 来源 | 状态 |
|---|---|---|
| 批量打印 | 已有待办 #12 | 待实施 |
| 横版照片旋转推荐 | 已有待办 #13 | 待实施 |
| 系统分享图片入口 | 已有待办 #14 | 待实施 |
| 错题卡融入模板系统 | 已有待办 #8 | 待实施 |
| 统一准备打印页 + 统一打印确认条 | 已有待办 #23 | 部分完成 |
| 模板系统改为生成器 | 已有待办 #24 | 待实施 |
| PPT 图片/版式还原 | 观察 | 后续完善 |
| Word 分栏/图片/复杂样式/真实表格线 | 观察 | 后续完善 |

## 三、结论

- 今天无紧急新功能需要立刻合入。
- 继续按“每日收集、攒批动手”的方式执行。
