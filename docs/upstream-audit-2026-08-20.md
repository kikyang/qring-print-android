# 上游 GitHub 巡检报告（2026-08-20）

> 周期：每日收集 | 关键词：「错题小印」「qring」
> 对比基线：upstream-audit-2026-08-19.md

## 一、本轮发现

### 已知仓库动态

| 仓库 | 状态 | 变化 |
|---|---|---|
| Thisko/QrintPrint（HarmonyOS） | 无变化 | 停在 8-10（更新icon） |
| lztttt/QrintPrint-Android | 无变化 | 停在 8-16 v1.5.0 |
| soulxyz/xyprt_android | 无功能变化 | pushed 8-19，main 提交仍停 8-13（chore/整理仓库），无新功能 |
| snowboys/QrintPrint-Windows | 无变化 | 停在 8-12（.gitignore/README 清理） |
| bzhou830/QringPrint | 无变化 | 停在 8-07 initial commit |
| kikyang/qring-print-android（我方） | v0.7.3 | 8-18 已发，8-19 仅巡检文档提交 |

### 观察名单动态

| 仓库 | 状态 | 变化 |
|---|---|---|
| yiran168/suda-win-web | 无变化 | 停在 8-17（README 顶加 Web 在线链接） |
| ZhaYi-Miao/QrintPrint-Windows | 无变化 | 停在 8-16 v1.1.2（画布编辑器大升级） |
| ZhaYi-Miao/QrintPrint-Web-Console | 无变化 | 停在 8-13 Initial commit |
| BA4RFY/QringAndroid | 无变化 | 源码包仍在，停在 8-12 |
| tanadiejiang/pocket_print | 无变化 | 停在 8-12 V1.5.0（BLE 修复+Win BLE 直连） |
| Thisko/QrintPrint-Web | 不可访问 | API 仍 404，维持移除观察 |

### 新仓库

- 无新增相关仓库。
- 「错题小印」检索出现 `gaoxiangyang2022/math_problems`、`jackli01030/shiyi-math-practice`、`limin6661/edu-tools-kit` 等均为**数学题库/练习网站**（错题本方向但不涉打印机协议），排除。
- 「qring」检索新出现 `akizu815-create/QRing`（8-11，日企法人官网站点，TypeScript），确认无关，排除。

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

- 今天上游无任何新功能值得立刻合入安卓客户端，无需评估重复吸收。
- 上游整体进入低频期（多家 8-12~8-16 后无实质更新）。
- **巡检频率调整（用户 8-20 定）**：由每日降为**每 3 天一次**（已建持久定时任务 f77d875b，9:19 触发）；完成 3 次每 3 天周期巡检后，视上游活跃度再决定是否降为一周一次。
