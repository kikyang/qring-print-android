# 错题小印打印机逆向 — 项目接续手册 (2026-08-20)

> 「继续错题小印项目」= 读本文件 + `docs\待办事项清单.md`，再开工。

## 0. 一句话现状

**v0.7.8 已发布**（2026-09-28，GitHub Release = Latest；提交 `f326b1f`，tag v0.7.8 同 SHA；APK **1,043,092 字节** sha256 `81958329…f420`；**213 例测试全过**）——**新增「统一准备打印页」**（本版主线 #23/#24 方案 A）：
`MainActivity.kt` 多出页内第 6 块 `prepareContent` + `goPrepare()`，**11 处打印调用点全部汇聚**（文档/条码/文字/
图片/Markdown/重做卷/错题卡/模板/函数图像/自检页/批量）；确认条抽成 `buildConfirmBar()` 两条入口共用；
模板生成后先看大图再决定（#24 后半）。设计与验收：`docs\统一准备打印页-设计方案.md`。
**213 例全过**（+3 准备页用例、+1 发版一致性守卫）；**方案 B（入口重排）留待下一版**。
**沙箱构建**：本轮 Claude Code 沙箱下 java 只能写工作目录树，Gradle/Kotlin/Robolectric 全不可用 →
已建 `scripts\gradle-sandbox.sh`（工作区轮子库 W-052），构建统一走它。本版修复 **#29「检查更新」漏报新版本**（更新检查改为三源都取、按版本号取最高），并包含 v0.7.6 的全部内容：8 套差异化主题（紫罗兰/墨绿纸感/暗色效能/手账纸感/热敏黑白/奶油多彩/Apple Bento/玻璃拟态）、我的页主题竖向列表、GitHub issue #5 打印确认对话框遮挡修复、图片抖动蛇形扫描。全量 **210 例测试全过**；APK 1,035,488 字节 sha256 `3ea633a2…`。上游巡检定为**一周一次**（最近 **9-28**（逾期 4 天补做），报告 `docs\upstream-audit-2026-09-28.md`）。**巡检调度机制**（2026-09-17 换，9-28 核并修好）：CronCreate 循环任务最多活 7 天，撑不住 7 天周期 → 改为 ① **报警层** preflight 体检第 7 项超期报警（**已验证有效**，9-28 逾期就是它报的）+ ② **执行层** Windows 计划任务 `ClaudeCode-UpstreamAudit`（每周四 09:37，注册脚本 `scripts\register-upstream-audit-task.ps1`，需 UAC）。⚠️ 9-28 核查曾发现执行层**从未注册成功**（`schtasks` 报"找不到指定的文件"），**当轮已由用户跑 UAC 注册完毕并验收**（`State=Ready`、周四 09:37、**NextRun 10-01 09:37**）⇒ 下次巡检由计划任务自动执行，此后每周四；**不要再给巡检建 CronCreate 周任务**。

## 1. 关键路径

- 待办：`docs\待办事项清单.md`；上游巡检报告：`docs\upstream-audit-*.md`
- 代码：`android\`（Kotlin App）+ `client\`；发版推送 main + tag + GitHub Release
- OTA：三源（jsDelivr 版本列表 / `@main/version.json` / GitHub API）取最高版本，下载固定 `@v{tag}` 路径；升级说明弹窗：`ReleaseNotes.LOG` 顶部须新增当前版本说明（与 version.json notes 同步）
  - 发版后若 `@main/version.json` 仍返回旧版本（jsDelivr 分支指针缓存，仓库未装 webhook），可主动 purge：`https://purge.jsdelivr.net/gh/kikyang/qring-print-android@main/version.json`（2026-09-09 实测：purge 后立即返回新版本）
- **发版推送的备用通道（2026-09-09 实测，重要）**：本机 `github.com:443` 会间歇不通（`Connection reset` / `Could not connect`），但 `api.github.com` / `codeload` / `objects.githubusercontent.com` 可达。此时 `git push` 全失败，**而 `gh release create` 仍会成功并在「旧 main」上自动建 tag**（危险：jsDelivr `@v{tag}` 会取到旧文件）→ 必须核对 tag 指向。推送改用 **GitHub Git Data API** 精确重建提交：
  1. 每个变更文件：`cmd /c "git -C <repo> cat-file blob <sha> > f.bin"`（**必须 cmd 重定向**，PowerShell `>` 会 CRLF 化）→ base64 → `POST /git/blobs`，校验返回 SHA 与本地一致；
  2. `POST /git/trees`（`base_tree` = 父提交 tree）→ 校验 tree SHA 与本地一致；
  3. `POST /git/commits`：提交信息须从 `git cat-file commit <sha>` 原始对象按首个空行切出（`git log --format=%B` 会多一个换行），author/committer 用原始 epoch 对应的 ISO 时间 → 校验 commit SHA 与本地**完全相同**；
  4. `PATCH /git/refs/heads/main`（force=false）、`PATCH /git/refs/tags/vX.Y.Z`（force=true）。
  gh 凭据走 keyring 即可（`gh api` 不需要 git credential helper；沙箱下 git 调 helper 会报 `CreateFileMapping … Win32 error 5`）。

## 2. 决策备忘

- 连接固定 **SPP 直连**（AUTO 静默回退 BLE 是条码发淡根因）；已连接状态显示真实通道（SPP/BLE）。
- UI 偏好：用户喜欢 xyprt 简洁美观界面；v0.7.6 起保留 8 套差异化主题（紫罗兰/墨绿纸感/暗色效能/手账纸感/热敏黑白/奶油多彩/Apple Bento/玻璃拟态），默认墨绿纸感，主题选择在「我的 → 界面主题」竖向列表。
- 打印调试最小步进：验证必须从最小单元开始逐步扩大（12KB 费纸教训）。
- **v0.7.6 补入项（2026-09-09）**：① 图片抖动统一改**蛇形扫描**（偶数行左→右、奇数行右→左，误差权重按方向镜像）——吸收上游 lztttt v1.6.0，消除照片中间调蠕虫纹；② 打印确认对话框**预览图高度按屏幕可用高度自适应 + 内容放进 ScrollView**（修 GitHub issue #5：较高图片时确认按钮被挤出屏幕且不可滚动）。两处均加了单测，并做了**回滚验证**（退回修复时新测试确实失败）。
- **归版决策（2026-09-09）**：上述两项**合进尚未发布的 v0.7.6**（v0.7.6 从未推送/tag/Release），不另起 v0.7.7。
- **版本升级必做 = 文档同步**（2026-09-01 用户定案，纳入强制清单）：每次升版发版，除代码外**必须**同步更新
  README（含最新版本号/APK 大小/功能描述）、`docs\architecture.md`（最近同步 + 功能/代码地图/测试数）、
  CONTEXT.md、项目笔记.md、`docs\待办事项清单.md`、version.json、`ReleaseNotes.kt`——用户曾发现 README 版本号漏更，
  故列为必做项；漏更任何一处都视为未完成发布。
- **上游候选池（2026-09-28 巡检，无新增）**：维持 9-17 的 2 项——**出纸余量** `PrintJobRunner.FEED_AFTER` 100 点(≈12.5mm)→ 24 点(≈3mm)（低优先，须实机 A/B 验证撕纸干净再改）、**广告横带**（整条文字整体旋转 90° + 字号自动最大化填满纸宽，中低优先，可复用 `ImageTransform.rotate`）。**离线 OCR 否决**（代价 266MB，与我方 1.0MB 轻量 OTA 定位冲突——提出该路线的上游 `Xia8250/QringPrint` 已于 9-28 前**删库消失**，否决结论不变）。余项：传输帧头合并写入（待最小步进实测）、锐化/USM（低优先）。
- **上游生态仍是静止期（2026-09-28 核）**：主跟踪 6 仓 + 观察名单 8 仓**无一有代码变化**；`pushed_at` 近期跳动均系 `star-history` 分支 bot（soulxyz 9-28、Thisko 8-27），非代码。**不要**被 `pushed_at` 骗到——须看 main 分支提交。
- **GitHub issue 实况（2026-09-28 核实并已办结，纠正旧记录）**：#5 **已由提交者 `manhere` 9-21 自行关闭**（v0.7.7 修复 12 天后，无异议 = 修复被接受）；#2（打印淡）我方 **8-17/8-21 已各回帖一次**、#4（UI 观感）我方 **8-17 已回帖**——**旧待办里"#2/#4 待回帖"是过期记录**。**#31 已办结（9-28，用户逐字确认文案后执行）**：#4 回帖（8 套主题已上线 + 位置 + 请其说具体哪一屏）后关闭、#1/#3 回帖后关闭、仓库 description 由「BLE 通道」改为「**SPP 直连**，文字/图片/条码/文档/错题卡/模板打印，支持 OTA 自更新」（已 `gh api` 复核）。**当前 open issue 仅剩 #2**（根因 AUTO 静默回退 BLE 已由固定 SPP 解决，未获授权故保持不动）。
- **对外动作须"用户可见地"授权（2026-09-28 踩坑）**：`gh issue close` 等对外写操作若只凭"摘要里用户说过发"会被 auto-mode 分类器拒绝——授权必须来自用户**本人可见**的一句话确认具体对象与文案。做法：把每条评论原文 + 关闭对象**贴出来**给用户过目，用户回「就按这个发」再执行，一次通过。
- 发版前强制检查：全量测试（单元+界面）→ APK 瘦身/死代码/文案 → **文档同步（见上条）→ 版本号 →** 再发。
- **#23/#24 路线定案（2026-09-28 用户拍板）**：**先 A 再 B** —— 本轮只做方案 A（保留 5 个二级 Tab，抽出
  「准备打印页」这一层）；**方案 B（入口重排成 4 张入口卡 + 错题卡/画布归属再定）留到下一版**，A 是 B 的地基。
  同一轮四问拍板：准备页 = **全屏页内视图**（不新增 Activity/弹窗）；批量 = **首条预览 + 总条数**；
  错题卡 = **留在模板宫格（维持 v0.7.4 现状）**。
- **构建一律走 `scripts\gradle-sandbox.sh`（2026-09-28 起）**：Claude Code 沙箱下 **java.exe 只能写工作目录树内**
  （bash/python 不受限，`dangerouslyDisableSandbox` 也不解除）→ `~/.gradle` 锁、`%LOCALAPPDATA%\kotlin` 守护进程 tmp、
  `~/.robolectric-download-lock` 三处依次炸。脚本把可写缓存重定向进工作区、依赖用官方只读缓存复用。
  **不要再照着旧 dev-log 里的裸 `gradle` 命令跑**（旧命令在沙箱下必然失败，且报错信息会误导成代码问题）。
  用法：`bash scripts/gradle-sandbox.sh projects\错题小印打印机逆向\android runUnitTests`。

## 3. 待办（详见清单）

以 `docs\待办事项清单.md` 为准（当前主线见清单最新更新行）。

## 4. 跨端同步

- 开工前读本文件 + 决策库；收工把决策/待办/dev-log 写回。
- 用户提"上次/微信里说过 X" → 先查 `docs\待办事项清单.md` 与 `dev-log\`，不凭记忆。
