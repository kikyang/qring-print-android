# 上游 GitHub 巡检报告（2026-08-14）

> 周期：每日（上游更新频繁期；后续转每周）| 关键词：「错题小印」「qring」| 目的：发现新项目/新更新，吸收有价值功能
> 对比基线：upstream-audit-2026-08-13.md

## 一、本轮发现

### 已知仓库动态

| 仓库 | 状态 | 变化（vs 8-13 报告） | 价值评估 |
|---|---|---|---|
| Thisko/QrintPrint（HarmonyOS） | 已参考 | 无新提交（8-10 icon 后静止） | 跳过 |
| **lztttt/QrintPrint-Android** | 已参考 | **连续两版**：v1.3.0（8-13）表格/Markdown/错题本/测试页；v1.4.0（8-14）PDF打印/批量打印/横版照片推荐/课程表横版+今日课程 + 大量修复 | **高——经代码级核查全部真实实现** |
| soulxyz/xyprt_android | 已参考 | 8-13 品牌更名「口袋小印」、update gateway 拆独立仓库、legacy 元数据同步（纯工程/品牌操作） | 跳过（无功能增量） |
| snowboys/QrintPrint-Windows | 已参考 | 无新提交（8-12 整理后静止） | 跳过 |
| bzhou830/QringPrint（uniapp） | 已参考 | 无新提交（8-07 后静止） | 跳过 |
| kikyang/qring-print-android（我方） | 活跃 | v0.6.2（固定 SPP）/v0.6.3（OTA 安装权限）已发 | — |

### 新仓库

| 仓库 | 创建 | 说明 | 价值评估 |
|---|---|---|---|
| **ZhaYi-Miao/QrintPrint-Windows** | 8-11 | **第二个 Windows 桌面端**（C# WPF，GPLv3）：v1.0.2 表格/课程表/Markdown/Word 打印，v1.0.3 **HTTP API 远程打印**+运行日志+USB 修复 | 方向信息：桌面远程打印可行；LaTeX 公式文本打印（web 侧）有亮点 |
| **ZhaYi-Miao/QrintPrint-Web-Console** | 8-13 | 上者配套浏览器控制台（纯 HTML 单文件）：远程打印文本/图片/MD/条码/Word/PDF/表格/课程表，服务端渲染二值化回传实时预览，含 **LaTeX 公式模式** | 观察（依赖桌面端 HTTP 服务运行，与安卓直接打印不冲突） |
| **tanadiejiang/pocket_print** | 8-10 | Qring/BeePrt BY 系列 **Flutter 客户端**（Android+Windows，MIT，基于 Thisko 迁移）：13 种条码（含 DataMatrix/PDF417/Aztec）、**系统分享图片直接进打印页**、BLE 写分包+退避重试、Atkinson 抖动 | 中——分享图片入口值得吸收；条码 2D 码场景价值低 |
| dahiko2/Qring_data_export | 7-30 | 实为 **Blivas 智能戒指**（QRing app）备份数据导出页 | 无关，排除（不同产品） |
| gaoxiangyang2022/math_problems、jackli01030/shiyi-math-practice、limin6661/edu-tools-kit | — | 数学练习网页/小程序（共享「错题」概念，非打印机客户端） | 无关，排除 |
| naoki747/qring | 4-14 | 日文 QR 通讯系统 | 无关，排除 |

## 二、功能差距分析（我们 v0.6.3 vs 上游）

### 已吸收（勿重复）
- **PDF 打印** → 文档 Tab 已有（PdfPrintRenderer 252 行，实物联调通过）
- **Sauvola 自适应二值化文档增强** → ImageEnhancer.kt 已有（拍试卷神器）
- **抖动三模式**（阈值/Floyd-Steinberg/Atkinson）→ Dither.kt 已有
- **对比度** → Contrast.kt 已有
- 口算题/单词表/课程表/每日计划/错题卡模板 → TemplateLibrary/SystemTemplates 已有
- 历史再编辑/模板/画布/描边/体检拦截/状态灯（历次已吸收）

### 未吸收且有价值（新）
| 功能 | 来源 | 说明 | 工作量 | 建议 |
|---|---|---|---|---|
| **Markdown 打印** | lztttt v1.4.0 | MarkdownRender.kt 真实实现：标题/加粗/列表/代码块，**按字符流折行保持加粗**，长文分块流式打印防超时。我方文档 Tab 缺 MD（只有 PDF/docx/xlsx） | 中（参考其"解析+布局分离、逐行测高、切块渲染"思路） | **Q2 规划** |
| **批量打印** | lztttt v1.4.0 | txt/MD/图片多选，统一预设（字号/行距/抖动/阈值/浓度）一次应用到所有项，逐项进度+失败跳过汇总，清单存历史可重打。我方已有 PrintJobRunner/History 基础设施 | 中 | **Q2 规划** |
| **横版照片智能推荐** | lztttt v1.4.0 | 宽>高照片弹推荐一键旋转 90°（长边沿出纸方向），避免矮横条丢细节 | 小 | **Q3 可做** |
| **系统分享图片入口** | tanadiejiang/pocket_print | 其他应用「分享」图片直接进打印页（单图进打印页、多图进列表），我方 manifest 无 ACTION_SEND | 小 | **Q3 可做** |
| **课程表横版 + 今日课程** | lztttt v1.4.0 | 整表旋转 90° 横版打印；按当天星期筛选打印今日节次、跨节次合并 | 中 | 可选（我方课程表为竖版模板） |

### 勘误（修正 8-13 报告）
- 上次判定「lztttt 亮度/锐度滑块未实现、无代码可合入」**需修正**：v1.3.0/v1.4.0 中 `ImageTransform.adjustGrayImage()` 已真实实现 **对比度+亮度+锐度**（对比度因子 0.1~3、亮度加性偏移 ×2.55、锐化 3×3 卷积）——方案与我们上次预判完全一致。但**我方已有对比度 + Sauvola 增强覆盖多数场景，亮/锐度价值中低，不并入**，保留观察。

### 无价值/已排除
- soulxyz 品牌更名/拆仓库：纯工程操作
- 状态检测/体检拦截扩展：我方已全局拦截
- ZhaYi-Miao 桌面端+Web 控制台：跨平台方向（桌面/网页），与安卓不冲突；LaTeX 公式模式有亮点但依赖桌面端，记观察
- pocket_print 多出的 2D 条码（DataMatrix/PDF417/Aztec）：错题小印场景价值低，不吸收（如用户需要可后加）
- dahiko2（智能戒指）、数学练习网页类、naoki747：无关

## 三、本轮合成决定

1. **新增两项 Q2 规划**（lztttt v1.4.0 真实实现，有成熟代码可参考）：**Markdown 打印**、**批量打印**
2. **两项 Q3 小改进**：横版照片旋转推荐、系统分享图片入口
3. 亮度/锐度：不并入（我方对比度+Sauvola 已覆盖），保留勘误记录
4. 观察名单新增：ZhaYi-Miao/QrintPrint-Windows（含 HTTP 远程打印 API + LaTeX）、ZhaYi-Miao/QrintPrint-Web-Console、tanadiejiang/pocket_print

## 四、下轮巡检备忘（2026-08-15）

- lztttt 近 3 天发 5 版（v1.0.0→v1.4.0），重点复查 v1.4.0 后续是否修 Markdown/批量 bug；若持续高频则待其稳定后吸收
- 复查关键词新仓库（gh search repos "错题小印" / qring）
- 关注：BA4RFY/QringAndroid 是否放出源码（当前仅 APK）
- Thisko/QringPrint-Web、ZhaYi-Miao、tanadiejiang 是否持续开发
