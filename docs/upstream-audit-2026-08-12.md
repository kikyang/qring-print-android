# 上游 GitHub 巡检报告（2026-08-12）

> 周期：一周一次 | 关键词：「错题小印」「qring」| 目的：发现新项目/新更新，吸收有价值功能

## 一、本轮发现

| 仓库 | 状态 | 变化 | 价值评估 |
|---|---|---|---|
| Thisko/QrintPrint（HarmonyOS） | 已参考 | 8-10 更新 icon；8-07 加协议解读 Python 测试脚本（仅文字/图片） | 无新功能，跳过 |
| lztttt/QrintPrint-Android | 已参考 | **8-11 修复 OTA**：下载重定向处理 + v1 签名支持 + 安装器异常捕获 | **高——OTA 机制可吸收** |
| soulxyz/xyprt_android | 已参考 | 8-11 仅仓库整理（预览图/Star History 工作流） | 无新功能，跳过 |
| snowboys/QrintPrint-Windows | 已参考 | 8-10 仅文档类提交（开发说明/免责声明） | 无新功能；其自定义画布概念已列入合成候选 |
| **bzhou830/QringPrint（uniapp 跨平台）** | **新仓库（8-07 创建）** | 文字/图片/错题/模板/历史/设备/自定义画布，~3400 行 | 与现有功能重叠；**自定义画布编辑器与 lztttt/snowboys 三方共识**，确认需求 |
| akizu815-create/QRing | 新仓库 | 无描述空仓库 | 无关，排除 |

## 二、功能差距分析（我们的 v0.4.1 vs 上游）

### 已吸收（8-11 已完成，勿重复）
- 口算题生成（lztttt math）→ `MathWorksheet.kt`
- 画布涂鸦（lztttt CanvasView 白底黑笔手绘）→ `DrawCanvasView.kt`
- 描边模式（xyprt Canny/LINES）→ `Canny.kt` / `Outline.kt`
- 打印历史/状态灯/体检拦截/文档打印等（首发 v0.4.1 已有）

### 未吸收且有价值
| 功能 | 来源 | 说明 | 工作量 |
|---|---|---|---|
| **OTA 检查更新** | lztttt（8-11 刚修复） | GitHub Releases 检查 → 下载 APK → FileProvider 安装；适配我们自己的 kikyang/qring-print-android | 小 |
| **自定义画布（元素拖拽排版）** | bzhou830 / snowboys / lztttt 三方共识 | 添加文字/图片/条码元素，拖拽/缩放/置顶，存模板，打印；区别于已有的涂鸦（涂鸦是自由手绘） | 中 |
| 连续标签打印（Label） | lztttt | 标签高度/间隙/份数/页边距，批量小标签（姓名贴/单词卡） | 中 |
| 月历打印（Calendar） | lztttt | 整月日历排版打印 | 中低（已有课程表/每日计划） |

## 三、本轮合成决定

1. **OTA 检查更新** —— 合入（入口：我的 → 关于 → 检查更新）
2. **自定义画布元素排版** —— 合入（入口：打印 Tab 二级「画布」，复用预览/打印通道）

标签/月历下轮按需求评估（用户未提出，暂缓）。

## 四、下轮巡检备忘（2026-08-19 前后）

- 复查本报告各仓库是否有新提交（gh api repos/{owner}/{repo}/commits）
- 复查关键词是否有新仓库（gh search repos "错题小印" / qring）
- 关注：bzhou830/QringPrint 是否持续开发（新仓库活跃度待观察）
