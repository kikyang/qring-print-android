# 上游 GitHub 巡检报告（2026-09-17）

> 周期：**一周一次**（2026-08-27 定案，9-01 由 3 天降为一周一次）。上次巡检 9-09，本次 9-17（**逾期 1 天**，应巡日 9-16）
> 对比基线：upstream-audit-2026-09-09.md
> 数据来源：GitHub REST API（api.github.com），本机 curl 直连**可用**（本轮 200）。
> 提交时间 / 消息 / 版本号均来自 API 原始返回，未做推断。

## 〇、本轮前置事件：定时巡检任务丢失

用户问「定时跑了吗」——**没有跑**。原因已定位，是两个机制叠加：

1. **CronCreate 的循环任务 7 天自动过期**（触发最后一次后自动删除，属设计上限，非故障）。8-20 前后建立的巡检任务（ID `f77d875b`）约在 **8-27 前后自然到期**——这正好解释了 9-09 报告里那句「逾期 6 天补做」：**从 8-27 起就已无人自动执行**，9-09 那次是手动补做的。
2. **非持久（session-only）任务随进程退出消失**，关掉 Claude 窗口即清空。

证据：`.claude/scheduled_tasks.json` 内容为 `{"tasks": []}`，mtime 停在 **2026-09-09 09:56**（最后一次清理留下的空壳）。

**根本矛盾：每周的周期 > 7 天过期上限，单靠 CronCreate 永远撑不到下一次触发。** 本轮已改为「preflight 体检报警 + Windows 计划任务」双保险，见文末第五节。

## 一、本轮发现

### 已知仓库动态（主跟踪）

| 仓库 | 状态 | 变化 |
|---|---|---|
| lztttt/QrintPrint-Android | 无变化 | 停 8-29（v1.6.0，tag/Release 均为 v1.6.0，Release 已确认存在） |
| Thisko/QrintPrint（HarmonyOS） | 无变化 | 停 8-12；作者另有新仓库 `Thisko/Katabump-Renewal`（8-25，续期脚本）→ **无关，排除** |
| soulxyz/xyprt_android | **无代码变化** | main 停 8-20（versionCode 1030011）；`pushed_at` 9-17 来自 `star-history` 分支的 `github-actions[bot]`「chore: refresh star history」——**非代码**（已逐条核对提交） |
| snowboys/QrintPrint-Windows | 无变化 | 停 8-12 |
| bzhou830/QringPrint | 无变化 | 停 8-07 |
| kikyang/qring-print-android（我方） | 远端停 9-09（v0.7.7） | 无新 issue；**5 个 open issue 均未回帖**（见第四节） |
| **Xia8250/QringPrint** | **新增（本轮重点）** | 9-12 建、9-15 停，Kotlin，27.8MB → 见下 |

### 观察名单动态

| 仓库 | 状态 | 变化 |
|---|---|---|
| ZhaYi-Miao/QrintPrint-Windows（ThermoPrint） | 无变化 | 停 8-25 v1.1.3 |
| yiran168/suda-Android | 无变化 | 停 8-26 |
| yiran168/suda-win-web | 无变化 | 停 8-23（`updated_at` 9-01 系 gh-pages 部署索引） |
| BA4RFY/QringAndroid | 无变化 | 停 8-12 |
| tanadiejiang/pocket_print | 无变化 | 停 8-12 |
| ZhaYi-Miao/QrintPrint-Web-Console | 无变化 | 停 8-13 |
| Thisko/QringPrint-Web | 无变化 | 停 8-12 |
| xlyun20030701-dev/math-wrong-notebook | 无变化 | 停 9-05（建库当日即停，Flutter 错题整理+打印） |

### 新仓库（本轮新增）

| 仓库 | 建立 | 语言 | 评估 |
|---|---|---|---|
| **Xia8250/QringPrint** | 9-12 | Kotlin | **同类客户端（HarmonyOS + Android），入主跟踪** —— 详见下节 |
| Chen171111/agentskill 等「错题」泛命中 | — | — | 题库 / 工具类，与打印机协议无关 → 排除 |

检索词「错题小印」「QringPrint」全量复核：本轮生态内**仅 Xia8250 一个新面孔**，其余命中均为题库/成绩/A4 打印方向（gaoxiangyang2022、limin6661 等），维持排除。

## 二、重点：Xia8250/QringPrint 剖析

### 是什么

- **双端仓库**：HarmonyOS 工程（`AppScope/`、`hvigor/`、`oh-package.json5`、`entry/`）+ 一份内嵌的 Android Gradle 工程（`android-build-copy/`）。
- **README 自述**：基于 **Thisko/QrintPrint**（HarmonyOS 客户端）二次开发的 Android 版；**LICENSE = MIT © 2026 Thisko**；明确写着「目前实现的是 **SPP** 通道，BLE 通道暂未实现」。
- **实际包体是「浣熊快印」**：`android-build-copy` 里的 App 包名 `huanxongkuaiyin.com`，版本 **4.1.0（versionCode 41）**，APK **266MB**（含 ONNX Runtime + OpenCV + Tesseract 本地库，支持离线 OCR），最低 Android 8.0 / 目标 14，自有 release.keystore 签名。
- **开发方式**：仓库含 `GIT_SETUP.md`、`push_update.ps1`、`gen_update_json.ps1`、`rewrite_mine_body.txt`，文档里直接写「让 Codex 改 `UpdateConfig.kt`」→ **AI 辅助开发 + jsDelivr OTA**，与我们同一条路子。
- **版本谱系**：v4.1.0 发布说明标注 9-11（仓库 9-12 建于其后），说明 **4.x 有更早的历史版本**（提及「老版本文字打印页的广告横带模式」），并非三日速成。

### 与我方的关系（衍生性核查）

- **不衍生自我方**：包名、架构、功能集均不同；README 感谢的是 Thisko，**全文未提及 kikyang/qring-print-android**。
- **无许可风险**：对方 MIT © Thisko，与我方 LICENSE/NOTICE 无冲突。

### 领先/落后对比

| 维度 | 我方 | Xia8250 |
|---|---|---|
| 通道 | **SPP + BLE 双通道**（均已实测定稿） | 仅 SPP（README 明说 BLE 未实现） |
| APK 体积 | **1.0MB**（R8） | 266MB |
| OTA | 三源取值 + 按版本号取最高（v0.7.7 修 #29） | 单一 jsDelivr `@latest` + `update.json` |
| 离线 OCR | 无 | 有（代价即 266MB） |
| 广告横带 | 无 | 有 |

**结论：我方在通道完整性与体积上明显领先；对方唯一的结构性差异是「用 266MB 换离线 OCR」。**

## 三、功能吸收评估

**结论：本轮唯一新功能来源 = Xia8250 v4.1.0。经代码级核查，1 项我方已实现、2 项入候选池（均为低-中低优先）、1 项明确否决。**

### 代码级核查（核对我方源码）

| Xia8250 v4.1.0 项 | 我方现状（代码核查） | 结论 |
|---|---|---|
| 预览 = 硬阈值二值化「所见即所打」 | `MainActivity.kt:3056 imagePreviewRaster` = `halveRows(raster)` + `rasterToPreviewBitmap(verticalScale=2)`——**预览直接由待发送的 1-bit 光栅生成**，与打印路径同一份数据 | **无需吸收**（我方已实现） |
| 出纸余量 360 点（45mm）→ **24 点（3mm）**，修「打印完仍走纸」 | `PrintJobRunner.kt:50 FEED_AFTER = 100` 点行 ≈ **12.5mm**（203dpi）；`Settings` 可调 0~255，UI 给「无/1段/2段」 | **候选（低优先）**：默认 100→24 每张省约 9.5mm 纸；但 100 源自 QrintPrint-Windows 参考值，须**实机 A/B 验证「撕纸干净」不倒退**再改 |
| **广告横带**：整条文字整体旋转 90°、字号自动最大化填满纸宽、加粗锁定、换行强制合并 | 文字打印有 大号/加粗/居中；**旋转仅对图片**（`ImageTransform.rotate` 0/90/180/270），无「整条旋转」文字模式 | **候选（中低）**：把文字条位图整体 rotate(90) 即可复用现有链路，实现成本低；但场景窄（店铺招牌/货架标签），需用户确认要不要 |
| 离线 OCR（ONNX Runtime + OpenCV + Tesseract） | 我方无 OCR，APK 1.0MB | **否决**：对方为此付出 266MB（我方 260 倍），与我方「轻量 OTA」定位直接冲突；若做 OCR 须另寻系统/在线轻量方案 |
| 标签纸模式（横向偏移/可打印宽度/校准） | 我方无标签纸模式 | 维持不入池（场景不同，9-09 已否决） |

### 候选池状态更新

| 候选 | 来源 | 状态 |
|---|---|---|
| 图片抖动改蛇形扫描 | lztttt v1.6.0 | **已消化**（v0.7.6 已落地，含金标准测试） |
| 函数图像打印 | ZhaYi-Miao v1.1.3 | **已消化**（v0.7.4） |
| 传输帧头与数据合并单次写入 | lztttt v1.6.0 | 待最小步进实测（维持） |
| 锐化 / USM | lztttt v1.6.0 | 低优先（维持） |
| **出纸余量下调至 24 点** | Xia8250 v4.1.0 | **新增（低优先）** |
| **广告横带（整条旋转文字）** | Xia8250 v4.1.0 | **新增（中低）** |
| suda 系（离线 OCR / 变量批量 / 流水号 / 多码制 / 文档直印） | yiran168 | 维持待核查；其中**离线 OCR 现已有对照样本证明代价过高**，可考虑一并降级 |

## 四、我方仓库 issue（本轮关注）

| # | 标题 | 创建 | 状态 | 本轮结论 |
|---|---|---|---|---|
| **5** | 0.7.4+ 打印对话框前后走纸元素拥挤遮挡，较高图片使打印按钮不可见且不能滚动 | 9-01 | **open（0 评论）** | 代码 9-09 已修（并入 v0.7.6/v0.7.7），但 **GitHub 上未回帖、未关闭** → **本轮应回帖说明并关闭** |
| 2 | 打印机打出来非常淡 | 8-11 | open（4 评论，8-21 更新） | 待回帖：浓度 2 + SPP 直连可缓解（README 已有说明） |
| 4 | UI 不如第一版好看 | 8-14 | open（1 评论） | 待回帖：v0.7.6 已加 8 套主题，请其试用 |
| 1 / 3 | 闲聊 / fork | — | open | 可关闭 |

**注**：9-09 报告已列出 #5 修复，但漏了「回帖+关闭」这一步——issue 至今仍挂在 open 列表里，用户看不到修复。本轮补上。

## 五、结论与后续动作

### 本轮结论

- 上游整体仍处**低频期**：主跟踪仓库**无一有代码变化**（lztttt 停 8-29、soulxyz 9-17 的 pushed 系 bot 索引、Thisko 停 8-12）。
- **唯一新面孔 Xia8250/QringPrint**（9-12）：同为错题小印第三方客户端，HarmonyOS + Android，但只做 SPP、APK 266MB；不衍生自我方、无许可风险。我方在**双通道**与**体积（1.0MB vs 266MB）**上明显领先。
- 可吸收：**0 项**（唯一高价值项「预览=二值化」我方已实现）；新增候选 **2 项**（出纸余量、广告横带，均低-中低优先）。
- 新增待办：**回帖并关闭 issue #5**；#2/#4 回帖。

### 巡检频率维持一周一次，下次 **2026-09-24**

### 调度机制修复（本轮已做）

1. **preflight 体检加巡检过期报警**（`scripts/preflight.py`）：每次会话启动扫描 `projects/*/docs/upstream-audit-*.md`，最新一期超过 7 天即 `⚠` 报警——**不依赖任何长活任务，重启/换会话都看得见**。
2. **Windows 计划任务**（脚本在 `temp/`）：每周固定时间直接拉起 Claude Code 执行巡检，**不依赖会话存活**（原理与微信桥接计划任务相同）。需 UAC 提权注册，命令见 `temp/audit-task-注册说明.md`。
3. CronCreate 不再用于每周任务（7 天上限 < 7 天周期，必然失效）。
