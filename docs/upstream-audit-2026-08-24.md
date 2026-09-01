# 上游 GitHub 巡检报告（2026-08-24）

> 周期：**每 3 天一次**（3 天周期第 2 次，8-20 → 8-24 → 约 8-26）
> 对比基线：upstream-audit-2026-08-20.md
> 说明：8-23（DOM 23）定时任务未落报告，本轮 8-24 手动执行补齐该周期

## 一、本轮发现

### 新仓库（重点）

| 仓库 | 说明 | 评估 |
|---|---|---|
| **yiran168/suda-Android** | 8-20 创建，Kotlin/Jetpack Compose/M3，MIT（保留 Thisko 版权声明），本地优先不申请网络权限，最低 Android 7.0 | **本轮最大发现**，功能列表异常丰富（见下），已列入高价值候选池 |

suda-Android 是 yiran168（suda-win-web / Web 端作者）新推的 Android 端，**独立实现非 HAP 转 APK**。功能亮点（相对我方现状的增量）：

- **离线中文 OCR**：拍照/相册识别，识别行按原图位置转可编辑文字层，模型随 APK 内置不联网 —— **我方没有**，高价值但工作量大
- **变量数据批量打印**：CSV/TSV/Excel/WPS OOXML 导入，`{{列名}}` 绑定文字/条码/表格/流水号，记录预览 + 整表批量 —— **我方没有**，高价值，与待办 #12/#24 天然衔接
- **递增流水号**：批量打印逐份递增 —— 我方没有，小工作量，可并入 #12
- **19 种一维/二维条码**（输入清洗/校验位重算/QR 安全回退）—— 我方仅 QR，中价值
- **文档直印全格式**：PDF/DOCX/PPTX/旧版 DOC/WPS/PPT/DPS/XLSX/XLS/ET/TXT/MD，旧二进制 Office 走 Android POI 5.5.1 —— 与 #17/#18 部分重叠
- 可编辑表格元素、10–57mm 无极纸宽 + 标签预设、40 款字体/竖排/字重、多选画布/对齐/组合

⚠️ 仓库仅 2 天、0 star，长功能列表存在宣传未实现风险（lztttt 曾有前科）。**吸收前须代码级核查**。

### 已知仓库动态

| 仓库 | 状态 | 变化 |
|---|---|---|
| Thisko/QrintPrint（HarmonyOS） | 无变化 | 停在 8-10（更新icon） |
| lztttt/QrintPrint-Android | 无变化 | 停在 8-16 v1.5.0 |
| soulxyz/xyprt_android | 无功能变化 | main 停 8-20（v1.3.0 版本号提升 + versionCode 调整，均 chore 元数据）；pushed 8-24 系 star-history 分支，非功能 |
| snowboys/QrintPrint-Windows | 无变化 | 停在 8-12 |
| bzhou830/QringPrint | 无变化 | 停在 8-07 initial commit |
| kikyang/qring-print-android（我方） | v0.7.3 | 停在 8-19（巡检文档） |

### 观察名单动态

| 仓库 | 状态 | 变化 |
|---|---|---|
| yiran168/suda-win-web | 无代码变化 | commits 停 8-17；8-22/8-23 的 pushed 系 gh-pages/元数据，无代码提交 |
| tanadiejiang/pocket_print | 无代码变化 | commits 停 8-12 V1.5.0；8-21 updated 非代码事件 |
| ZhaYi-Miao/QrintPrint-Windows | 无变化 | 停在 8-16 |
| ZhaYi-Miao/QrintPrint-Web-Console | 无变化 | 停在 8-13 |
| BA4RFY/QringAndroid | 无变化 | 停在 8-12 |
| Thisko/QrintPrint-Web | 不可访问 | API 仍 404，维持移除观察 |

### 无关项排除

- 「qring」检索出现 smart-ring 系（wielorzeczownik/pulse-layer、lukr-99/ring-set、TheJ4nitorNG、MultAI 等）与量子 QRNG 系（btq-ag/QRiNG、I4cTime/homebrew-tap）及学生项目（2026-dmu-quiz-language、fitaccessng），均与热敏打印机无关，排除。

## 二、功能吸收评估

**结论：suda-Android 是本轮唯一实质性新功能来源，但均为中/大工作量项，且仓库过新，本轮先列候选池，不做吸收决定。**

新增候选池（持续累积）：

| 候选 | 来源 | 价值 | 与待办关系 |
|---|---|---|---|
| 变量数据批量打印（CSV/Excel 导入 + {{列名}} 绑定 + 记录预览） | suda-Android | 高 | #12 批量打印、#24 模板生成器升级方向 |
| 离线中文 OCR（拍照/相册，识别行转文字层） | suda-Android | 高 | 独立新功能，工作量最大 |
| 递增流水号 | suda-Android | 中 | 可并入 #12 |
| 19 种条码码制扩展 | suda-Android | 中 | 现有条码 QR 扩展 |
| 文档直印补 XLSX/XLS/ET + 旧版 Office | suda-Android | 中 | #17/#18 后续完善 |

已吸收项确认（不重复）：PDF/批量/表格/Markdown/课程表（lztttt）、PPT 导入/Word 公式排版（#17/#18）、三算法增强（#16）、照片旋转缩放（#15）、多份打印（#19）、主题（#20）——上述 suda-Android 均有同类实现，我方已吸收或等价，无需重复并入。

## 三、结论

- 本轮唯一实质性发现 = **yiran168/suda-Android**（新仓库），已列候选池，吸收前需代码级核查（防宣传未实现）。
- 其余全部上游/观察名单无实质更新，整体仍处低频期。
- **巡检频率进度**：每 3 天周期已完成 2 次（8-20、8-24）。按用户 8-20 定：**第 3 次（约 8-26）后主动询问是否降为一周一次**。本轮不询问。
