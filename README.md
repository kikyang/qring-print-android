# 错题小印打印客户端（Qring Print Android）

学科网「错题小印」热敏打印机的**安卓替代客户端**。

官方 App 已下架、服务器已关闭，本客户端通过逆向蓝牙协议让打印机恢复可用。
基于 **BLE 透传通道**（X1 机型实测），支持文字 / 图片 / 错题卡 / 常用模板打印。

> 协议逆向参考自 [Thisko/QrintPrint](https://github.com/Thisko/QrintPrint)（MIT，HarmonyOS 版）：
> 官方 App `com.zxxk.xiaoyin.App` 私有协议已被其完整逆向并真机验证。
> 本工程在其基础上针对 **X1 机型（BLE 通道）** 验证、修正并重写了安卓客户端。

## 支持机型

- **X1**（BLE 透传通道：写 FF02 / 通知 FF01，ISSC 芯片）——本客户端的主要目标，K80 真机实测通过
- 理论上兼容 Qring/BeePrt BY 系列（协议同源），未逐一验证

## 功能

### 打印
- **文字打印**：字号（小/中/大）+ 加粗，自动换行排版，黑白预览
- **图片打印**：多选图、单列/双列拼接省纸；抖动三模式（无 / Floyd-Steinberg / Atkinson）；
  消除笔（去红/蓝笔批改）；自动裁白边；一键增强（直方图拉伸 + Sauvola 自适应二值化，拍试卷推荐）
- **错题卡**：题目图（可选，支持多图拼接）+ 错因 + 知识点 + 订正/举一反三手写区（练习本横线版式）
- **常用模板**：课程表 / 单词表 / 每日计划，一键生成打印
- **打印前自动预览确认**：所有打印先渲染实际效果图，满意才打，取消零耗纸

### 协议与硬件
- X1 控制通道 = BLE 透传（FF02 写 / FF01 通知），SPP 是空壳（RFCOMM 能连但数据无人消费）
- 打印时序：STOP复位 → ENABLE → 浓度 → WAKEUP → ESC@ → 光栅 → 走纸 → STOP → 等 ACK
- 光栅模式：文字 m=0；图片「行 OR 合并减半 + m=2 双倍高」——黑度提升且比例不变形（X1 实测定稿）
- 打印体检：开盖/缺纸/过热/低电量实时拦截；电量/状态轮询
- 调试台（藏于「我的 → 关于」）：收发 hex 日志、原始命令、打印测试页

### UI
- Material 3 风格（学习绿主题，支持系统深色模式）、Material Symbols 矢量图标
- 底部三 Tab：首页（设备状态 + 功能宫格）/ 打印（文字/图片/错题卡二级切换）/ 我的（设备管理 + 关于）

## 构建

环境：JDK 17 + Android SDK（compileSdk 34，minSdk 33）+ Gradle 8.7

```bash
cd android
gradle assembleRelease    # 正式签名（需自行配置 keystore）
gradle assembleDebug      # 调试版
```

> 注意：工程路径含非 ASCII 字符时需在 `gradle.properties` 保留
> `android.overridePathCheck=true`。

## 目录结构

```
错题小印打印机逆向/
├── android/               # 安卓客户端（Kotlin，本仓库主体）
│   └── app/src/main/java/com/qring/print/
│       ├── BlePrinterConnection.kt  # BLE 连接/分包/打印时序/查询
│       ├── QringProtocol.kt         # 私有协议层（命令/状态位/光栅头）
│       ├── RasterEncoder.kt         # 光栅编码/行合并/预览渲染
│       ├── Dither.kt                # 抖动（无/Floyd/Atkinson）
│       ├── ImageEnhancer.kt         # 一键增强/消除笔/自动裁白边
│       ├── TemplateBuilder.kt       # 错题卡模板
│       ├── TemplateLibrary.kt       # 课程表/单词表/每日计划
│       ├── SelfTest.kt              # 打印测试页
│       ├── Design.kt                # M3 设计系统（含 Material Symbols 图标）
│       └── MainActivity.kt          # 三 Tab 主界面
├── docs/
│   ├── architecture.md   # 人类可读的架构说明（推荐先读）
│   ├── protocol.md       # 完整协议（指令表/状态位/时序/光栅编码）
│   └── 实物联调手册.md    # 联调操作手册
├── reference/qrintprint/ # QrintPrint 源码归档（MIT，仅参考）
└── 电脑端验证脚本见仓库 temp 说明（BLE 扫描/打印/协议核对）
```

## 关键协议要点（X1 实测）

- 查询：状态 `10 FF 40`、电量 `10 FF 50 F1`、设备信息 `10 FF 70`
  （`设备名|MAC|MAC|固件版本|SN|电量`）、固件 `10 FF 20 F1`
- 浓度合法范围 **0~2**（3/4 报 ER），定稿 2
- 光栅 `GS v 0`：m=1 有 0x00 字节 bug 勿用；m=2 是标准双倍高
  （配合行合并实现黑度提升 + 不变形）；m=3 双倍宽有超出打印头风险
- **不要发 ENABLE2（1F B2 10）**：X1 固件不识别，会被文本引擎渲染成「固」字乱码
- 发送节奏：BLE 分包 32B + 无确认写 + 80ms 间隔（快速写会丢包）
- 完整细节见 `docs/protocol.md`

## 免责声明

- 本软件仅供学习与个人使用；逆向对象为本人合法拥有的设备
- 官方 App 与服务器已不可用，本项目与学科网无任何关联
- 使用风险自行承担，勿用于商业分发

## 致谢

- [Thisko/QrintPrint](https://github.com/Thisko/QrintPrint) —— 协议逆向的起点（MIT）
- [snowboys/QrintPrint-Windows](https://github.com/snowboys/QrintPrint-Windows) —— 同类项目参考
