# 错题小印打印机逆向 — 项目接续手册 (2026-08-20)

> 「继续错题小印项目」= 读本文件 + `docs\待办事项清单.md`，再开工。

## 0. 一句话现状

**v0.7.7 已发布**（2026-09-09，GitHub Release = Latest；Android 打印 App + 热敏打印机逆向）。本版修复 **#29「检查更新」漏报新版本**（更新检查改为三源都取、按版本号取最高），并包含 v0.7.6 的全部内容：8 套差异化主题（紫罗兰/墨绿纸感/暗色效能/手账纸感/热敏黑白/奶油多彩/Apple Bento/玻璃拟态）、我的页主题竖向列表、GitHub issue #5 打印确认对话框遮挡修复、图片抖动蛇形扫描。全量 **210 例测试全过**；APK 1,035,488 字节 sha256 `3ea633a2…`。上游巡检定为**一周一次**（最近 9-09，下次 9-16）。

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
- 发版前强制检查：全量测试（单元+界面）→ APK 瘦身/死代码/文案 → **文档同步（见上条）→ 版本号 →** 再发。

## 3. 待办（详见清单）

以 `docs\待办事项清单.md` 为准（当前主线见清单最新更新行）。

## 4. 跨端同步

- 开工前读本文件 + 决策库；收工把决策/待办/dev-log 写回。
- 用户提"上次/微信里说过 X" → 先查 `docs\待办事项清单.md` 与 `dev-log\`，不凭记忆。
