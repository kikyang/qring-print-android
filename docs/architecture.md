# 错题小印打印客户端 · 架构说明

> 给人类看的架构文档：不解释每一行代码，而是讲清楚"这套系统是怎么拼起来的、每个部件为什么存在"。
> 读完本文你应该能回答：一条错题从手机屏幕到热敏纸，中间经历了什么？
> 最近同步：v0.7.5（2026-09-01）。

---

## 1. 系统全景

```
┌───────────────────┐    经典蓝牙 SPP（主力）      ┌────────────────────┐
│  安卓客户端 (App)  │ ───────────────────────▶ │  错题小印 X1 打印机  │
│  （本仓库的代码）   │   RFCOMM 串口 1024B/1ms     │  （ISSC 透传芯片）   │
└───────────────────┘ ◀─────────────────────── └────────────────────┘
        │ 查询响应 / ACK / 故障上报
        └─ BLE 透传（兜底）：FF02 写 / FF01 通知，96B/40ms
```

**核心事实（X1 机型实测）**：打印机的"大脑"只认字节流。手机把一串精心编排的字节
（打印指令 + 图片光栅数据）通过蓝牙发过去，打印机按字节顺序执行：
先使能打印头 → 设置加热强度 → 唤醒 → 走纸 → 逐行加热出图 → 停止 → 回一个"完成"信号。

整个客户端本质上是一个**字节序列生成器 + 蓝牙搬运工**。

---

## 2. 通信层：双通道（BLE 透传 + 经典蓝牙 SPP）

打印机是**双模**设备（BLE + 经典蓝牙 SPP 都有），2026-08-13 定案：
**SPP 为主力、BLE 兜底**（早前以 BLE 为主是过时结论，见下）。

- **SPP 通道（主力）**：RFCOMM 标准串口，传输快（1024B/1ms）、查询实测可响应
  （`10 FF 40/50/70` 正常返回）。SPP 快 → 行间隔短 → 打印头残热积累 → 同浓度下
  墨色更深更实（用户实测"又黑又快"）。早前"SPP 单向、查询无响应"结论系
  BLE 占用时连接失败的假象
- **BLE 透传通道（兜底）**：`FF00` 服务，`FF02` 写 / `FF01` 通知，
  连接必须指定 `TRANSPORT_LE`（双模设备默认走 BR/EDR 会连不上 GATT）。
  打印效果比 SPP 差（慢、墨色淡），入口藏进「关于 → 调试台」供扫档/诊断

**客户端同时支持两条通道**（`PrinterConnection` 接口 + 双实现）：

```
PrinterConnection（接口）
 ├── BlePrinterConnection  # BLE 透传：FF02 写 / FF01 通知
 └── SppPrinterConnection  # 经典蓝牙：RFCOMM socket + daemon 读线程
```

连接分派（`PrinterHolder.connect`）：
- **手动指定**：BLE / 经典蓝牙 / AUTO，设置里可选
- **AUTO（默认）= SPP 优先、BLE 兜底**（2026-08-13 实测反转）：先连 SPP，
  连上即视为可用（能打印即成功，不强制查询验证——单向机型查询无响应也不影响
  打印）；SPP 失败（纯 BLE 版无 SPP 服务/信道被拒）回退 BLE 透传。UI 侧连接
  对话框实时显示阶段文案 + 进度条（AUTO 最坏约 60 秒：SPP 31s + BLE 30s 预算，
  典型 3~8 秒）
- 打印机单连接约束：切通道前先断开另一通道的 active 连接（`closeOther`）

发送节奏（踩过最深的坑之一：**节奏决定打印质量**）：
- BLE：96 字节/包 + 40ms 间隔（2026-08-13 手机扫档定稿：96B 稳定、128B 卡死；
  比旧基线 32B/80ms 快 6 倍）
- SPP：RFCOMM 无 MTU 限制，1024 字节/块 + 1ms

---

## 3. 协议层：私有指令 + 少量标准指令

打印机不完全遵守标准 ESC/POS 打印机协议，是"半私有的"：

| 用途 | 指令 | 说明 |
|---|---|---|
| 打印使能 | `10 FF F1 02` | 每次打印前必须先使能 |
| 停止 | `10 FF F1 45` | 打印前复位、打印后收尾 |
| 加热浓度 | `10 FF 10 00 n` | **合法范围 0~2**（3/4 会报错），定稿 2 |
| 唤醒 | `00 × 12` | 12 个空字节，唤醒芯片 |
| 查状态 | `10 FF 40` | 1 字节状态位：开盖/缺纸/过热/低电/打印中 |
| 查电量 | `10 FF 50 F1` | 第 2 字节是百分比 |
| 设备信息 | `10 FF 70` | `设备名\|MAC\|MAC\|固件版本\|SN\|电量` |
| 走纸 | `1B 4A n`（标准 ESC J） | 按点行走纸 |
| 光栅图 | `1D 76 30 m ...`（标准 GS v 0） | 图片打印的唯一途径 |

**协议层设计**：`QringProtocol.kt` 只负责"拼字节、解析字节"，
不碰蓝牙连接——纯函数式，好测试。

---

## 4. 打印流水线：一条错题是怎么变成纸上的内容的

这是全系统最核心的部分，分两条通道（文字和图片走了完全不同的路）：

### 4.1 文字通道（简单直接）

```
用户输入文字
  → 画到内存里的白色位图上（384 点宽，按字号排版换行）
  → 二值化（灰度 < 212 判黑，文字专用高阈值，笔画不被吃掉）
  → 每行打包成 48 字节（384 点 ÷ 8）
  → 按 m=0 模式发给打印机（原始分辨率，本来就黑，不需要特殊处理）
```

### 4.2 图片通道（弯弯绕绕，但都是实测踩坑踩出来的）

```
照片/试卷图
  → [可选] 自动裁白边（去掉四周桌面/留白）
  → [可选] 消除笔（把红笔/蓝笔批改痕迹替换成白色）
  → 等比缩放到 384 点宽
  → [可选] 一键增强（直方图拉伸去灰雾 + Sauvola 自适应二值化，拍试卷神器）
  → [可选] 抖动（Floyd-Steinberg / Atkinson，让照片有层次而不是一片黑）
  → 二值化阈值可调（黑白化阶段调"哪些算黑"，与打印浓度"黑得多黑"独立叠加）
  → 二值化：每行 48 字节，MSB first，置 1 = 黑
  → ★ 行合并减半（每 2 行 OR 合并成 1 行）
  → 按 m=2 模式（双倍高）发给打印机
```

**描边模式**（独立管线，不经过灰度/对比度，2026-08-11 移植自 xyprt）：
```
Bitmap → argb 数组 + 透明度掩码（alpha≥128 视为实体像素）
  → Canny.detect（高斯模糊 → Sobel 平方幅度 → 方向量化 → NMS
     → 自适应双阈值（99 百分位 × 灵敏度指数公式）→ 8 邻域滞后连接）
  或 Outline.trace（墨水对比度边缘，只记"比邻居更黑"的暗侧）
  → [可选] 平滑（prune 剪毛刺 + despeckle 去小连通块）
  → thicken 加粗（线宽 1~3）
  → [可选] 反白 → 打包 → m=2 打印
```

**为什么图片要"行合并 + 双倍高"这套组合拳？**——这是打黑方案定稿的过程：

1. 直接打印（m=0）显色偏淡：固件对每个点加热时间固定且很短，浓度已经调到最高档也救不回来
2. 发现 m=2（双倍高）打出来更黑：固件把每一行数据**加热两遍**，墨色自然更深
3. 但 m=2 是标准"双倍高"——每行数据打印成两行，**图片会被纵向拉长 2 倍**
4. 解法：发送前先把数据**每 2 行合并成 1 行**（OR 合并，1 像素细线不会丢），
   发 m=2 后高度刚好还原 → **黑度提升 + 比例不变形** ✅

### 4.3 打印时序（每次打印的固定仪式）

```
STOP（复位）→ ENABLE（使能）→ 浓度 → 唤醒 → ESC@（解析器复位）
→ 前走纸 10 点 → 光栅数据（按 64 行分块，SPP 块间 0ms / BLE 块间 150ms）→ 后走纸 100 点
→ STOP → 等 ACK（0xAA）
```

几个容易踩的坑（都已定稿规避）：
- **不要发 ENABLE2（1F B2 10）**：X1 固件不识别，会被文本引擎渲染成"固"字
- **光栅数据要分块**（每块 ≤64 行）：单块 >~255 行固件状态机错乱回落文本引擎 →
  "固"字瀑布；64 ≪ 255，两通道安全
- **块间延迟按通道**：SPP 0ms（传输快，行间隔短残热积累墨色更实）、
  BLE 150ms（传输慢需等待）。150ms 块间 delay 是打印时间瓶颈，故 SPP 下置 0
- **没有预热条**：实测文字不预热本来就黑，全黑块预热不预热都不黑，预热条纯浪费纸
- **打印期间停止状态轮询**：查询字节混进打印数据流会毁掉整张图

---

## 5. 模板、错题卡与画布：把内容排版成一张图，再走图片通道

错题卡 / 课程表 / 单词表 / 每日计划 / 口算题都不是"特殊打印"——
它们是**先在内存里画好一张 384 点宽的位图**（Canvas 画文字、画线、画表格），
然后走图片通道打印。好处：排版逻辑和打印逻辑完全解耦。

**入口结构（v0.5.1 起）**：打印页顶部 5 个二级 Tab——
文字 / 图片 / 条码 / 文档 / **其它**。「其它」Tab 收纳所有"模板类"功能：

```
其它 Tab
 ├── 系统模板宫格（图标，v0.7.4 扩容）：课程表 / 单词表 / 每日计划 / 口算题 / 批量打印 / 函数图像 / 错题卡 / 错题卡·复习
 │   （一键生成 → 走图片通道；注册表数据驱动 = SystemTemplates.kt，新课只需加一行 JSON）
 │   ├── 批量打印：导入 CSV/Excel，用 {{列名}} 占位符套模板整表一次打完，可开递增流水号 {{序号}}
 │   ├── 函数图像：输入表达式（如 x^2-3）求值并渲染成坐标图打印
 │   └── 错题卡：题目图（可选，多图拼接 + 全套预处理）+ 错因 + 知识点 + 订正/举一反三手写区
 └── 我的模板宫格：画布存下的版式（缩略图 + 点击进画布继续编辑 + 长按删除）
```

这些"模板"与普通打印没有本质区别：**先在内存里摆好一张 384 点宽的位图，再走统一打印流**。
v0.7.4 起所有打印路径（文字/图片/条码/文档/模板/批量）最终汇聚到**统一准备打印页 + 统一打印确认条**
（份数 / 打印浓度 / 前后走纸），保证"先预览、确认再打、走纸一致"。

批量打印与函数图像是 v0.7.4 新增的**两条内容生成管线**（各自独立、纯 Kotlin 可单测）：

- 批量打印（`BatchTemplate` + `CsvTableParser` + `XlsxTableExtractor`）：把 CSV/Excel 的每一行
  绑进 `{{列名}}` 模板，逐条渲染成栅格，逐条持续打印（单条失败不中止、结束后汇总）。
  递增流水号 `{{序号}}` 可开/关，默认从 1 开始。
- 函数图像（`ExpressionEvaluator` + `FunctionGraph`）：表达式求值器（支持 `x^2-3`、三角函数等）
  + 坐标系渲染器，把函数曲线画成 384 宽位图走图片通道。

手写区横线设计成**练习本风格**（横线在格子底边，行高 10mm）——
因为错题卡打印出来是给学生手写订正用的，行距不够会写不下。

**图片页的自绘入口（v0.6 起单入口）**：🖌 **统一画布**（`CanvasEditor` + `CanvasLayout`）
——涂鸦笔画与文字 / 图片 / 条码元素自由拖拽排版、缩放、置顶，可存为模板复用
（模板 JSON 存 SharedPreferences，图片元素不持久化——内容来自相册 URI，模板只存
位置/尺寸/文字/条码/笔画参数）。核心设计：**逻辑坐标系 384 点宽**（与打印头一致），
预览和打印共用同一渲染管线（`CanvasEditor.render`），所见即所得。
「我的模板」宫格展示已存版式（缩略图 + 点击进画布继续编辑 + 长按删除）。

---

## 6. 可靠性设计

| 机制 | 作用 |
|---|---|
| 打印前体检 | 现查状态字节：开盖/缺纸/过热 → 拦截打印并提示原因 |
| 打印中暂停轮询 | 防止查询字节混入打印数据流 |
| 全链路 try-catch | 蓝牙任何一步异常 → 显示错误文案而不是 App 闪退 |
| **OOM 单独捕获** | OutOfMemoryError 是 Error 不是 Exception，catch 不住——大文件/长图闪退根因；txt 流式读（5MB 上限）、OLE2 全量读（30MB 上限）、光栅/文本高度上限 30000 行 |
| 事件日志落盘 | 连接/断开/打印/异常记到 `files/printlog.txt`，崩溃可复盘 |
| 自动预览确认 | 所有打印先渲染实际效果图，确认才打，取消零耗纸 |
| 连接/解析进度反馈 | 连接进度条 + 阶段文案；文档解析转圈 + 已提取段/行计数，可取消 |
| 文档解析竞态防护 | 新任务取消旧协程（大文件晚完成会覆盖新结果），CancellationException 不吞 |
| BLE 分包 + 节奏控制 | 96B/包 + 40ms 间隔，防丢包；SPP 1024B/块 + 1ms |
| 三通道共享打印时序 | PrintJobRunner 统一编排，BLE/SPP/FakePrinter 三通道跑同一份代码——实物联调只剩 GATT 写 + 热敏头物理两个未知量 |
| **自动化测试（198 例）** | `gradle runUnitTests`：协议（15）/算法（13）/模板·历史·设置（13）/Robolectric 界面（19）/虚拟打印机引擎（21）/端到端（15）/性能基准（3）；其余为 v0.7.x 分批补充——图片增强·变换·裁剪、Markdown、PPT/公式排版、批量打印（CSV/XLSX/模板）、函数图像、OTA 更新说明、条码 13 种与校验/清洗。注意：Gradle Test worker 在中文路径下 classpath 失效，用 JavaExec 任务绕开（见 README） |
| **R8 瘦身** | release 开 minify（AGP 8.5.2），APK 6.4MB → 0.87MB（v0.7.2）→ ~1.0MB（v0.7.5，新增条码码制/批量/函数图后略增）；zxing 自带 consumer rules，mapping 验证功能类全保留 |

### 6.1 OTA 检查更新（v0.5.2 起）

国内手机直连 GitHub API 不通（桌面有代理、App 没有），所以**检查与下载都走 jsDelivr**（国内可达的 GitHub CDN）：

```
我的 → 关于 → 检查更新
  → jsDelivr data API（版本列表，主源；新 tag 收录滞后约 2-3h，属正常）
      → 拿最新版本号 → 构造 jsDelivr CDN @v{版本} 下载 URL
  → 下载 APK（@v tag 路径，tag 不可变 + 冷缓存秒级生效，无滞后）
  → FileProvider 触发系统安装
  fallback：仓库 version.json → GitHub API（海外）
```

发版流程固定为：bump 版本 → 测试 → 构建 → **APK 提交进仓库 `releases/`（.gitignore 加例外）+ version.json 更新** → push → gh release → 微信推送。实测教训：jsDelivr data API 的 tag 索引和 @main 分支指针缓存都不可靠（purge 不掉），**下载必须走 @v{tag} 路径**。

**安装权限（v0.6.3）**：Android 13+ 装 APK 需 `REQUEST_INSTALL_PACKAGES` 权限 +
用户授权「允许安装未知应用」；未授权自动跳系统设置页引导。0.6.1/0.6.2 存量用户
首次升级需手动授权一次（旧包未带权限声明，弹不出引导）。

---

## 7. 代码地图

```
android/app/src/main/java/com/qring/print/
├── PrinterConnection.kt      # 连接通道抽象接口（双通道统一入口）
├── BlePrinterConnection.kt   # BLE 透传通道：FF02 写/FF01 通知/TRANSPORT_LE/96B·40ms 分包
├── SppPrinterConnection.kt   # 经典蓝牙通道：RFCOMM + daemon 读线程 + 1024B 分包
├── PrinterHolder.kt          # 双实例 + AUTO 分派（SPP 优先/BLE 兜底）+ 测试注入
├── PrintJobRunner.kt         # 打印时序编排（三通道共享，块间延迟按通道）
├── QringProtocol.kt          # 协议字典：所有指令字节、状态位解析、光栅头构造
├── RasterEncoder.kt          # 光栅编码：Bitmap→48字节/行、行合并、预览渲染、多图拼接
├── Dither.kt                 # 抖动：无 / Floyd-Steinberg / Atkinson
├── Canny.kt / Outline.kt     # 描边：Canny 边缘检测 / LINES 墨水对比度 + 加粗
├── Morphology.kt / Contrast.kt  # 形态学去噪 / 对比度膝形曲线
├── ImageEnhancer.kt          # 图片增强：直方图拉伸+Sauvola 二值化、消除笔、自动裁白边
├── ImageTransform.kt         # 图片旋转 0/90/180/270 + 缩放 50%~200%
├── ImageCropDialog.kt        # 手动裁剪（自由/1:1/3:4/4:3）
├── MarkdownParser.kt / MarkdownRenderer.kt  # Markdown 解析 + 384 宽渲染（零依赖）
├── PptxTextExtractor.kt      # PPT pptx 文本提取
├── DocxLayoutExtractor.kt / MathLayout.kt / DocLayoutRenderer.kt  # Word 版式 + OMML 公式排版引擎
├── ReleaseNotes.kt           # OTA 更新说明（跨版本收集，纯 Kotlin 可单测）
├── PdfPrintRenderer.kt       # PDF → 384px 位图（系统 PdfRenderer 逐页+裁白边+拼接）
├── DocxTextExtractor.kt      # Word docx 纯文本（zip + XmlPullParser 流式）
├── XlsxTextExtractor.kt      # Excel xlsx 表格文本（sharedStrings + sheet1）
├── LegacyDocExtractor.kt     # 老格式 doc/xls（OLE2 复合文档解析）
├── TemplateBuilder.kt        # 错题卡模板（含手写区版式）
├── TemplateLibrary.kt        # 课程表/单词表/每日计划模板
├── SystemTemplates.kt        # 系统模板内置 JSON 注册表（v0.6 数据驱动）
├── MathWorksheet.kt          # 口算题生成（随机算式 + 2 列大字号排版，借鉴 lztttt）
├── CanvasEditor.kt           # 统一画布：元素模型 + 384 宽渲染 + 模板 JSON 存取（含涂鸦笔画）
├── CanvasLayout.kt           # 画布视图：拖拽/命中检测/缩放/置顶/涂鸦模式
├── UpdateManager.kt          # OTA：jsDelivr 检查 + 下载 + FileProvider 安装（GitHub fallback）
├── BarcodeGenerator.kt       # 条码/二维码（zxing，13 种可写码制 + 输入清洗/校验位重算）
├── BatchTemplate.kt          # 批量打印占位符绑定（{{列名}} + 递增流水号 {{序号}}，纯逻辑可单测）
├── CsvTableParser.kt         # CSV 解析 → 记录行（含引号/逗号转义）
├── XlsxTableExtractor.kt     # Excel xlsx 表 → 记录行（sharedStrings + 行列）
├── ExpressionEvaluator.kt    # 函数表达式求值器（x^2-3、三角函数等）
├── FunctionGraph.kt          # 函数曲线 → 384 宽坐标图位图
├── SelfTest.kt               # 打印测试页（浓度线/线条/灰阶渐变/文字）
├── PrintLog.kt               # 日志：内存环形缓冲 + 关键事件落盘
├── HistoryStore.kt / HistoryActivity.kt  # 打印历史（无损光栅重打 + 缩略图）
├── Settings.kt               # 打印设置持久化（浓度/走纸/连接模式/阈值/描边参数）
├── DebugActivity.kt          # 调试台：收发 hex 日志/原始命令/BLE 直连（藏于"我的→关于"）
├── Design.kt                 # UI 设计系统：微信小程序风（灰底白卡/微信绿/8px 圆角/线性图标）
├── FakePrinter.kt            # 虚拟打印机协议引擎（字节流状态机，测试/联调仿真）
├── FakePrinterConnection.kt  # 虚拟打印机连接（PrinterConnection 实现，测试注入）
└── MainActivity.kt           # 主界面：三 Tab（首页/打印/我的）+ 全部交互

test/ 目录（198 例，`gradle runUnitTests`）：
├── QringProtocolTest.kt      # 协议字节/状态位/指令构造（15 例）
├── DitherTest.kt / CannyTest.kt  # 抖动/边缘检测算法（13 例）
├── TemplateBuilderTest.kt / HistoryStoreTest.kt / SettingsTest.kt  # 模板/历史/设置（13 例）
├── MainActivityUiTest.kt     # Robolectric 界面测试（19 例：启动/图标/预览/排版/模板/参数记忆/画布/宫格/分工）
├── FakePrinterTest.kt        # 虚拟打印机协议引擎（21 例）
├── FakePrinterE2ETest.kt     # 端到端链路（15 例）
├── ImagePipelineBenchTest.kt # 图像管线性能基准（3 例）
├── ImageEnhancerTest.kt / ImageTransformTest.kt       # 图片增强/旋转缩放（v0.7.0）
├── MarkdownParserTest.kt / MarkdownRendererTest.kt    # Markdown 解析/渲染（v0.7.0）
├── MathLayoutTest.kt         # Word 公式排版（v0.7.2）
├── CsvTableParserTest.kt / XlsxTableExtractorTest.kt / BatchTemplateTest.kt  # 批量打印（v0.7.4）
├── ExpressionEvaluatorTest.kt / FunctionGraphTest.kt  # 函数图像（v0.7.4）
├── ReleaseNotesTest.kt       # OTA 更新说明（v0.7.1）
└── BarcodeGeneratorTest.kt   # 条码 13 种 + 校验/清洗（v0.7.5）
```

数据流一句话总结：
**UI 参数 → 位图排版 → 二值光栅 → 行合并 → 字节流 → BLE/SPP 分包 → 打印机加热出纸**

---

## 8. 已知边界

- 验证过 **X1 机型两个通道**：BLE 透传版（本机，全功能）+ 经典蓝牙 SPP（2026-08-13 实测：
  能打印出纸、查询 10 FF 40/50/70 可响应，**SPP 为默认主力**；早前"查询无响应"系
  BLE 占用时连接失败的假象）；同源 BY 系列理论兼容，未逐一验证
- 浓度范围 X1 是 0~2（其他机型可能不同，如 Windows 版提到 0~7）
- 老格式 .doc/.xls 提取是简化实现（.doc 复杂分片/文本框不提取；.xls 只取字符串表不还原行列），复杂文档建议转存 docx/xlsx
- 打印头电流限制导致全黑大块显色偏淡是硬件特性，非软件可完全修复
- **OTA 的 jsDelivr 收录延迟**：发版后 2-3 小时内 App 检查更新可能仍提示已是最新（data API 收录滞后，见 6.1）
- Robolectric 的运行时 assets 加载在 AGP 8.5 + Robolectric 4.11 下有兼容问题（FileNotFound）——
  图标类测试走文件系统断言绕开；升级 Robolectric 后可改回运行时断言
- 官方 App 生态已死，本客户端是社区替代方案，与学科网无关联

---

*协议细节见 [protocol.md](protocol.md)，联调步骤见 [实物联调手册.md](实物联调手册.md)。*
