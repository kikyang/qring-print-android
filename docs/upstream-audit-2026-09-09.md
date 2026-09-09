# 上游 GitHub 巡检报告（2026-09-09）

> 周期：**一周一次**（2026-08-27 定案）。上次巡检 8-27，本次 9-09（**逾期 6 天补做**）
> 对比基线：upstream-audit-2026-08-27.md
> 数据来源：GitHub REST API（api.github.com）。**本机 git/gh 直连 GitHub 超时不可用**，本轮数据经
> harness web_fetch 通路获取（逐条 HTTP 200），提交时间/消息均来自 API 原始返回，未做推断。

## 一、本轮发现

### 已知仓库动态（主跟踪）

| 仓库 | 状态 | 变化 |
|---|---|---|
| **lztttt/QrintPrint-Android** | **有实质更新** | **8-29 发布 v1.6.0**（标签校准升级 / 抖动蛇形扫描 / 传输稳定性 / 4 处 bug 修复），APK 35.6MB，下载 44 |
| Thisko/QrintPrint（HarmonyOS） | 无变化 | 主分支停 8-12；`updated_at` 8-27 系索引时间，非代码提交 |
| soulxyz/xyprt_android | **无代码变化** | 主分支停 8-20（versionCode 元数据）；9-09 的 `pushed_at` 来自 `star-history` 分支的 `github-actions[bot]` 提交「chore: refresh star history」，**非代码** |
| snowboys/QrintPrint-Windows | 无变化 | 停 8-12 |
| bzhou830/QringPrint | 无变化 | 停 8-07 |
| kikyang/qring-print-android（我方） | 远端停 9-01（v0.7.5） | 本地 v0.7.6 已就绪未推（GitHub 直连不通）；**新增 5 个 open issue，其中 1 个真实 UI bug** |

### 观察名单动态

| 仓库 | 状态 | 变化 |
|---|---|---|
| ZhaYi-Miao/QrintPrint-Windows（ThermoPrint） | 无变化 | 停 8-25 v1.1.3 |
| yiran168/suda-Android | 无变化 | 停 8-26 |
| yiran168/suda-win-web | 无代码变化 | 停 8-23；`updated_at` 9-01 系 gh-pages 部署索引 |
| BA4RFY/QringAndroid | 无变化 | 停 8-12 |
| tanadiejiang/pocket_print | 无变化 | 停 8-12 |
| ZhaYi-Miao/QrintPrint-Web-Console | 无变化 | 停 8-13 |
| **Thisko/QringPrint-Web** | **名称更正（恢复观察）** | 仓库实名为 `Thisko/QringPrint-Web`（8-12 建、8-12 停、JS/HTML、Pages 已开）。此前记录的 `Thisko/QrintPrint-Web` 因拼写差异返回 404，**并非仓库被删**，本次更正后恢复观察 |
| **xlyun20030701-dev/math-wrong-notebook** | **新增观察** | 9-05 建（Dart/Flutter），描述「一个用来整理小初高错题及打印的 APP」——**与错题小印同类场景**（错题整理 + 打印），暂无打印机协议代码 |

### 新仓库（created > 8-27，检索词「错题小印」「QringPrint」「热敏打印机」）

| 仓库 | 建立 | 语言 | 评估 |
|---|---|---|---|
| xlyun20030701-dev/math-wrong-notebook | 9-05 | Dart | 错题整理 + 打印 App（Flutter），同类场景 → **入观察名单** |
| smallerxuan/s_y2printer | 9-01 | C | 半色调抖动算法库（8bit→1bit）——通用算法参考，**排除**（我方已有 Floyd/Atkinson） |
| Suran-g/BW16_PostCard | 9-08 | Python | BW16 + 50mm 热敏标签海报方案，与 Qring X1 协议无关 → **排除** |

### 无关项排除

- 「错题小印」检索其余命中（gaoxiangyang2022/math_problems 等）仍为数学题库 / 成绩打印方向，不涉打印机协议，维持排除。
- 「QringPrint」检索仅 3 个仓库（lztttt、Thisko/QringPrint-Web、bzhou830），无新增。

## 二、功能吸收评估

**结论：本轮唯一实质新功能来源 = lztttt v1.6.0。其中 1 项高性价比可直接吸收、2 项入候选池、1 项我方已实现（无需重复）、1 项不适用。**

### 代码级核查（本轮已做，核对我方源码）

| lztttt v1.6.0 项 | 我方现状（代码核查） | 结论 |
|---|---|---|
| 误差扩散改**蛇形扫描**（消除中间调蠕虫纹 / 竖条纹） | `Dither.kt:57-91`：Floyd-Steinberg 与 Atkinson **均为单向从左到右**扩散，每行方向相同 | **可吸收**（高性价比：改动小、纯 Kotlin 可单测，直接改善照片中间调） |
| 传输**帧头 + 数据合并单次写入**、帧间零停顿 | `PrintJobRunner.kt:134/140`：每块**两次 write**（`cmdRasterHeader` + `chunk`），块间 `rasterChunkDelayMs`（SPP=0 / BLE=150） | **入候选池**：合并写可减少写次数；但**不可照搬其 256 行分带**——我方已实测 >255 行触发固件「固」字（`docs/protocol.md`），须在 64 行分块 + BLE 96B/40ms 实测档位上重新验证 |
| 状态轮询**绕过打印互斥锁**导致图片错位 / 断层 | 我方**已实现防护**：`BlePrinterConnection.kt:119`、`SppPrinterConnection.kt:87`「打印任务进行中 —— 期间暂停状态轮询」+ `mutex.withLock` | **无需吸收**（我方已具备其 bug 的对应解法） |
| 锐化移至打印分辨率 | `ImageEnhancer.kt` **无锐化 / USM**（链路 = 高分辨率光照补偿 → 384 缩放 → 三算法二值化） | 不适用；「锐化（USM/边缘加深）」另立候选，低优先 |
| 标签纸打印（横向偏移 / 可打印宽度 / 标签校准） | 我方无标签纸模式（固定 384 点纸宽） | 不入候选（场景不同） |
| 基准测试页（横竖同时分辨率测试） | 我方已有自检页 + 浓度校准 | 不入候选 |

### 新增候选（2 项）

| 候选 | 来源 | 价值 | 评估 |
|---|---|---|---|
| **图片抖动改蛇形扫描** | lztttt v1.6.0 | 高（工作量小） | 交替行方向扩散，消除蠕虫纹/竖条纹；Dither 是纯计算模块，可加 JVM 单测（对齐灰阶直方图 / 中间调块方差）。**同日已落地** → 见文末「四、当日后续」 |
| **传输帧头与数据合并单次写入** | lztttt v1.6.0 | 中 | 减少 BLE 写次数、缩短分带停顿；需按最小步进实测（先 1 张图，再长图）验证不与 64 行分块冲突 |

### 候选池状态更新

- **函数图像打印（ZhaYi-Miao v1.1.3，8-25 入池）→ 已消化**：我方 v0.7.4 已实现（`FunctionGraph.render`，系统模板宫格「函数图像」入口）。
- suda 候选池特性（离线 OCR / 变量数据批量 / 递增流水号 / 多码制 / 文档直印）维持待核查，本轮无新变化。
- 8-24 报告的低价值项（手绘套索裁剪）维持不入池。

### 我方仓库 issue（本轮新增关注）

| # | 标题 | 创建 | 状态 | 备注 |
|---|---|---|---|---|
| **5** | 0.7.4+ 打印对话框前后走纸元素拥挤遮挡，较高图片使打印按钮不可见且不能滚动 | 9-01 | **已修复（同日）** | 真实 UI bug（用户附截图）→ 当日修复：预览图高度按屏幕自适应 + 「预览图 + 确认条」放进 ScrollView；新增 Robolectric 防回归测试，回滚实测可捕获 |
| 2 | 打印机打出来非常淡 | 8-11 | open（4 评论，8-21 更新） | 已定案（浓度 2 + SPP 直连可缓解，README 有说明）；**待回帖说明** |
| 4 | UI 不如第一版好看 | 8-14 | open | v0.7.6 已改 8 套主题 → 可回帖请其试用 |
| 1 / 3 | 闲聊 / fork | — | open | 可关闭 |

## 三、结论

- 上游整体仍处**低频期**：主跟踪仓库仅 lztttt 有实质更新（8-29 v1.6.0）。
- 可吸收 **1 项**（蛇形抖动）、候选 **2 项**（帧头合并写入、锐化）；**1 项我方已实现**（状态轮询互斥）；1 项不适用。
- 8-27 候选「函数图像打印」**已消化**（我方 v0.7.4 已落地）。
- **巡检频率维持一周一次**，下次 2026-09-16。本轮逾期 6 天（8-27 → 9-09），期间会话聚焦 v0.7.6 发版与 VisualAid 项目。
- 我方新增待办：GitHub issue #5（打印对话框遮挡，真实用户 bug）。
- 环境备注：本机 `git ls-remote` / `gh api` 直连 GitHub 超时；本轮巡检经 harness web_fetch（api.github.com）完成，未影响数据完整性。

## 四、当日后续（2026-09-09 会话）

巡检后当日即落地两项（用户确认方向）：

1. **抖动改蛇形扫描**（吸收本轮唯一高性价比项）：`Dither.kt` Floyd-Steinberg / Atkinson 改为偶数行左→右、奇数行右→左，误差权重按扫描方向镜像；新增 `DitherTest` 2 例（**手工推演金标准用例**，可区分蛇形/单向实现；墨量密度守恒）。**回滚实测**：退回单向实现时金标准用例失败 → 测试有效。
2. **修复 GitHub issue #5**（我方用户 bug）：`previewConfirmDialog` 预览图高度按屏幕可用高度自适应 + 「预览图 + 确认条」整体放进 ScrollView；新增 `MainActivityUiTest` 1 例防回归（断言确认条位于 ScrollView 内），**回滚实测**：退回修复时该测试失败。

全量测试 **201 例全过**（198 → 201）；两项均并入尚未发布的 **v0.7.6**（v0.7.6 从未推送/tag/Release，故不另起 v0.7.7）。候选池余项：传输帧头合并写入（待最小步进验证）、锐化（低优先）。

