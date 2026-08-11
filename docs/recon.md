# 侦察记录

## ⭐ 重大发现（2026-08-10）：协议已被开源逆向，无需从零抓包

**GitHub 开源项目：[Thisko/QrintPrint](https://github.com/Thisko/QrintPrint)（MIT，★10，2026-08-09 更新）** —— 错题小印系列 58mm 蓝牙热敏打印机的原生 HarmonyOS 客户端，协议通过对官方 App `com.zxxk.xiaoyin.App` 逆向整理得到，作者已在 Nova 14U 真机验证"功能基本可用"。

已归档到 `reference/qrintprint/`（QringProtocol / PrinterConnection / RasterEncoder / PrinterDiscovery / DEVLOG / README）。

**情报要点（直接影响本项目的关键结论）：**
- 打印机 = **Qring / BeePrt BY 系列 58mm 热敏机，经典蓝牙 SPP + BLE 双模**，SPP 通道已验证可用（UUID `00001101-...-00805f9b34fb`，secure 配对）
- **非标准 ESC/POS**：状态/电量/浓度走私有 `10 FF` 系列命令；只有走纸 `ESC J` 和光栅位图 `GS v 0` 沿用标准
- 光栅编码：384 点宽 = 每行 48 字节，MSB first，置 1 = 黑 —— 证实本项目"中文走位图打印"的判断
- 打印时序完整（enable → thickness → wakeup → 前走纸 10 行 → 光栅 → 后走纸 100 行 → stop → 等 ACK 0xAA）
- 状态字节 5 位（打印中/开盖/缺纸/低电/过热）+ 独立电量查询 `10 FF 50 F1`
- 详细协议见 `docs/protocol.md`

**本项目策略更新：**
- ~~从零抓包逆向~~ → **验证 + 移植**：实物到位后用 QringPrint 的协议直接测；若实物型号/固件一致则直接复用
- 交付物仍为 **Python 客户端**（QringPrint 是 ArkTS/鸿蒙，不能直接跑在电脑上，但协议层可完整移植）
- 需要补的工程点：Python 侧 SPP 连接（Windows/Linux 蓝牙 RFCOMM）、光栅编码移植、抖动算法可选

## 参考案例：乐写错题打印机逆向（同类型设备）

来源：[Linux DO - 猫猫日记：乐写错题打印机蓝牙协议逆向](https://linux.do/t/topic/1890646)

要点：
- 乐写官方 App 强制登录，逆向摆脱官方 App 束缚 —— 与本项目动机一致
- 使用工具：GPT 分析协议 + 自改蓝牙调试工具（BluetoothViewer-Android，加 WebSocket 控制）+ Frida 抓蓝牙数据
- **关键发现：经典蓝牙（Classic Bluetooth / SPP）而非 BLE** —— 逆向难度较低
- 成果：完整协议文档 + `print_helloworld.py` 替代打印脚本

经验借鉴：
- 抓蓝牙数据包 → 交给 AI 分析协议 → 控制变量法确定参数位置
- 先判断蓝牙类型，经典蓝牙比 BLE 好逆向

## 现成开源库（若支持标准 ESC/POS）

- [thermal_printer](https://raw.githubusercontent.com/codingdevs/thermal_printer/main/README.md)（Dart/Flutter，pub.dev v1.0.5）— 发现+发送，支持经典蓝牙（Android）/BLE/USB/WiFi；配 flutter_esc_pos_utils 生成 ESC/POS 字节流
- tiny-esc-pos（npm）— Node.js，蓝牙发 ESC/POS 命令，配 react-native-bluetooth-serial

## 常见热敏打印机协议速查

- ESC/POS 标准：初始化 `1B 40`，打印并换行 `0A`，光栅位图打印 `1D 76 30 6D ...`
- 私有协议往往就是 ESC/POS 变体 + 厂商前缀（如 GS ( K 系列命令）
- 错题/便签类打印机常见：58mm 热敏纸，203dpi，位图打印为主

## 待确认（实物到位后填写）

- [ ] 设备型号 / 固件版本 / 外观标识
- [ ] 蓝牙类型（BLE / SPP）
- [ ] BLE 服务 UUID 或 SPP 通道
- [ ] 是否响应标准 ESC/POS 指令
