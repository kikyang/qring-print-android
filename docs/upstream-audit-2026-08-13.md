# 上游 GitHub 巡检报告（2026-08-13）

> 周期：每日（上游更新频繁期；后续转每周）| 关键词：「错题小印」「qring」| 目的：发现新项目/新更新，吸收有价值功能
> 对比基线：upstream-audit-2026-08-12.md

## 一、本轮发现

| 仓库 | 状态 | 变化（vs 8-12 报告） | 价值评估 |
|---|---|---|---|
| Thisko/QrintPrint（HarmonyOS） | 已参考 | 无新提交（8-10 icon 后静止） | 跳过 |
| lztttt/QrintPrint-Android | 已参考 | **8-12 release v1.2.0**：图片打印增强（对比度/亮度/锐度三滑块）+ TODO/日程/课程表/标签纸状态检测 | **中——亮度/锐度滑块未吸收** |
| soulxyz/xyprt_android | 已参考 | 8-12 仅 README/WEB 链接文档 | 跳过 |
| snowboys/QrintPrint-Windows | 已参考 | 8-12 仅 .gitignore/README/源码包整理 | 跳过 |
| bzhou830/QringPrint（uniapp） | 已参考 | 无新提交（8-07 后静止） | 跳过 |
| **Thisko/QringPrint-Web** | **新仓库（8-12 创建，8-13 仍更新）** | QringPrint **Web 端**：Web BLE 打印（protocol/printer/barcode/qrcode/markdown 零依赖实现，384 点宽条码画布，Cloudflare 部署） | 方向信息：跨平台 Web 打印可行；条码已被我们 zxing 覆盖 |
| **BA4RFY/QringAndroid** | **新仓库（8-12 创建）** | 仅 QringPrinter.zip（APK 打包）+ 截图 + README，**无源码** | 无关，排除（疑似 APK 搬运） |

## 二、功能差距分析（我们 v0.5.5 vs 上游）

### 已吸收（8-11/8-12 已完成，勿重复）
- OTA 检查更新（lztttt 8-11 修复）→ **v0.5.4/0.5.5 已实现**（jsDelivr data API 主源 + version.json fallback）
- 自定义画布元素排版（三方共识）→ **CanvasEditor.kt / CanvasLayout.kt 已实现**
- 口算题/涂鸦/描边/打印历史/状态灯/体检拦截（历次已吸收）
- 条码打印（Thisko Web 同功能）→ **BarcodeGenerator.kt 已实现且更全**：QR + Code128/EAN-13/EAN-8/UPC-A 等 7 种（zxing），Thisko 的零依赖 Code128 无需吸收

### 未吸收且有价值
| 功能 | 来源 | 说明 | 工作量 | 建议 |
|---|---|---|---|---|
| ~~图片亮度/锐度滑块~~ | lztttt v1.2.0 | **勘误（2026-08-13 代码级核查）**：release notes 声称"对比度/亮度/锐度三滑块"，但 HEAD 全库 grep 无任何实现代码——实际仅有阈值滑块（误差扩散时映射为亮度偏移 brightnessShift）与打印浓度。**上游宣传未实现，无代码可合入** | 无现成代码 | **无需行动**：若将来要做，亮度=灰度加性偏移、锐度=3×3 卷积，在二值化前灰度域实现（自写，约 1 天） |

### 无价值/已排除
- 状态检测覆盖扩展（lztttt v1.2.0）：我们已有全局体检拦截（连接/缺纸/低电），跳过
- BA4RFY/QringAndroid：无源码 APK 包，跳过
- Thisko Web 端本身：跨平台方向，与安卓客户端不冲突，记录观察

## 三、本轮合成决定

1. **无强制合入项**。lztttt 亮度/锐度滑块经代码级核查**未实现**（仅 release notes 宣传），无代码可合入；如用户有图片效果需求再自写
2. Thisko/QringPrint-Web 记入观察名单（Web BLE 打印是可行的跨平台补充，若未来要网页端打印可参考其 protocol.js/printer.js）

## 四、下轮巡检备忘（2026-08-14）

- 复查各仓库新提交（重点：lztttt 是否继续发版、Thisko/QringPrint-Web 是否持续开发）
- 复查关键词新仓库（gh search repos "错题小印" / qring）
- 关注：BA4RFY/QringAndroid 是否放出源码（当前仅 APK）
