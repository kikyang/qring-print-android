# QringPrint 开发记录

> 记录范围:2026-08-03 ~ 08-06(项目已基本完成)
> 项目:HarmonyOS 6.1.1 (API 24) 热敏打印机 App「错题小印」
> 设备:Qring / BeePrt BY 系列 58mm 蓝牙热敏打印机(经典蓝牙 SPP)

---

## 一、起点

会话开始时,项目里只有:

- 13 个 SVG 图标(前一轮会话生成,`entry/src/main/resources/base/media/ic_*.svg`)
- `module.json5` 里配好的蓝牙权限

**界面代码一行都没有** —— `pages/Index.ets` 还是 DevEco 默认的 565 字节 "Hello World" 模板。

---

## 二、完成的内容

### 2.1 首页 UI(按参考图还原)

| 文件 | 职责 |
|---|---|
| `common/Theme.ets` | 设计令牌:颜色 / 尺寸 / 字号,全部集中在此 |
| `model/HomeModel.ets` | 宫格与 Tab 静态数据 |
| `model/PrinterStatus.ets` | `@ObservedV2` 全局状态模型 + `AppStorageV2` 单例 |
| `view/PrinterStatusCard.ets` | 渐变状态卡,四种连接状态各有视觉 |
| `view/QuickActionGrid.ets` | 快速打印 2×2 宫格 |
| `view/HomePage.ets` | 顶栏 + 状态卡 + 宫格 |
| `pages/Index.ets` | Navigation 路由 + Tabs 壳 + 自绘圆角底部导航 |

### 2.2 蓝牙与驱动层

| 文件 | 职责 |
|---|---|
| `bluetooth/BtPermission.ets` | `ACCESS_BLUETOOTH` 运行时申请 + 设置页兜底 |
| `bluetooth/PrinterDiscovery.ets` | 蓝牙开关监听、配对列表、扫描发现、设备名过滤 |
| `bluetooth/PrinterConnection.ets` | SPP 连接态机、分包收发、查询时序、轮询、持久化重连 |
| `bluetooth/QringProtocol.ets` | 纯协议层:命令常量、状态位解析、故障文案 |
| `bluetooth/RasterEncoder.ets` | 图片解码、灰度化、光栅打包、文本渲染 |
| `bluetooth/Dither.ets` | 三种抖动算法(纯计算,不依赖 ImageKit) |
| `view/DevicePickerSheet.ets` | 设备选择半模态 |

### 2.3 打印功能页

| 文件 | 职责 |
|---|---|
| `view/TextPrintPage.ets` | 文字打印 + 排版设置(字体/字号/BIU/字间距/行间距) |
| `view/ImagePrintPage.ets` | 图片打印 + 抖动算法选择 + 实时预览 |
| `view/CodePrintPage.ets` | 条码打印(一维/二维切换、内容校验、防抖生成预览) |

### 2.4 自定义打印画布编辑器

| 文件 | 职责 |
|---|---|
| `view/CustomPrintPage.ets` | 画布编辑器:插入文字/图片/条码,拖拽移动、手柄缩放、双击编辑,保存/另存为模板,打印 |
| `view/CanvasElementView.ets` | 单元素显示 + 拖拽 + 缩放手柄(图片/条码等比缩放、文字字号/宽度手柄) |
| `view/ElementEditSheets.ets` | 元素编辑半模态,按 kind 分支,改动即时写回元素模型 |
| `model/CanvasModel.ets` | 画布文档模型,坐标一律用打印点(1 点 = 1/8mm,画布固定 384 点宽) |
| `common/ElementRender.ets` | 渲染:文字/图片/条码各自二值化后 OR 合并到一张二值画布 |
| `model/TemplateStore.ets` | 模板保存/加载/查找(preferences 落盘) |
| `model/HistoryStore.ets` | 打印历史持久化 + 缩略图 |

### 2.5 模板 / 历史 / 我的 三个 Tab

| 文件 | 职责 |
|---|---|
| `view/TemplatePage.ets` | 模板列表,点卡片进画布编辑器,支持重命名 |
| `view/HistoryPage.ets` | 打印历史,点记录跳对应打印页重新打印 |
| `view/MinePage.ets` | 我的页 |

---

## 三、Qring 私有协议要点

协议来自对 `com.zxxk.xiaoyin.App` 的逆向整理,仅供互操作参考。

**这不是标准 ESC/POS 状态协议。** 只有走纸(`ESC J`)和光栅位图(`GS v 0`)两条沿用了 ESC/POS,状态查询和电量走的是自己的 `10 FF` 系列命令。

### 命令表

| 用途 | 字节 |
|---|---|
| 启用打印 | `10 FF F1 02` + `1F B2 10` |
| 停止 | `10 FF F1 45` |
| 唤醒 | 12 个 `00` |
| 查状态 | `10 FF 40` → 1 字节 |
| 查电量 | `10 FF 50 F1` → 2 字节,**第 2 字节**是百分比 |
| 查型号 | `10 FF 20 F0` |
| 查固件 | `10 FF 20 F1` |
| 打印浓度 | `10 FF 10 00 <level>` |
| 走纸 | `1B 4A <n>`(n 单字节,>255 需拆条) |
| 光栅位图 | `1D 76 30 <mode> <wL> <wH> <hL> <hH>` + 数据 |

### 状态字节(`10 FF 40` 响应)

| 位 | 含义 |
|---|---|
| `0x01` | 正在打印 |
| `0x02` | 机身异常 / 开盖 |
| `0x04` | 缺纸 |
| `0x08` | 电池电压低 |
| `0x10` | 过热 |

字节为 `0` 表示一切正常。

### 异步帧

- `0xAA` —— 打印完成 ACK
- `FF 01/02/03/04` —— 主动上报:缺纸 / 开盖 / 过热 / 低电量

### 打印时序

```
enable → thickness → wakeup → feed(10) → 光栅头+数据 → feed(100) → stop → 等 ACK
```

### 收发规则

- 每包最大 **1024 字节**,包间 **1ms**
- 查询套路:**清空输入 → 发命令 → 等 150ms → 读响应**
- 光栅编码:每行 **48 字节**(384 点 / 8),**MSB first**(bit7 = 最左像素),**置 1 = 黑**

---

## 四、两次结论更正

这两处我最初判断错了,后来查 SDK / 读 Python 实现推翻,记录下来避免以后重犯。

### 4.1 电量:从「拿不到」到「能拿到」

最初结论:标准 ESC/POS 指令集没有电量查询,所以参考图里的「电量 85%」是假数据。

**这个判断对标准 ESC/POS 成立,但对这台机器不成立。** 它走的是 Qring 私有协议,协议里有独立的电量命令 `10 FF 50 F1`。参考图那个数字是真实数据。

中途还有个插曲:发现了平台级的 `connection.on('batteryChange')`(蓝牙 HFP/HID 电量通道)。但这台打印机不走那条,只会回 `-1`,订阅它反而会把私有协议查到的真值覆盖掉 —— **最终去掉了这个订阅**。

### 4.2 构建方式:从「只能 DevEco」到「命令行可用」

最初结论:项目根目录没有 `hvigorw` 包装器,所以只能在 DevEco Studio 里点 Build。

实际上 DevEco 安装目录自带全局工具链:

```
<DevEco 安装目录>/tools/hvigor/bin/hvigorw.js
<DevEco 安装目录>/tools/node/node.exe
```

命令行完全可以编译。

---

## 五、踩过和避开的坑

### ArkUI / ArkTS

1. **`@Builder` 多参数是按值传递** —— 状态变量变化不会触发 Builder 内部刷新。必须收敛成单个对象参数。
   实际造成过 bug:权限提示从「本次拒绝」变「永久拒绝」时停在同一分支只改文案,界面不更新。

2. **`Grid` 嵌在 `Scroll` 里必须给显式高度** —— 否则子项一次性全量加载,或高度直接塌陷成 0。

3. **`navDestination` 只接受顶层 `@Builder` 函数引用**,传内联 lambda 编译不过。

4. **V1 / V2 装饰器不能混用** —— `@ObservedV2` 对象的变更只在 `@ComponentV2` 中触发刷新。本项目全量用 V2。

5. **`getContext(this)` 已废弃** —— 用 `this.getUIContext().getHostContext()`。

### 图形与渲染

6. **离屏画布默认用 vp 单位** —— 3 倍屏上 `384vp` 会画成 1152px,出来就不是 384 点宽。用 `LengthMetricsUnit.PX` 建画布,另在灰度化时加了一道宽度兜底缩放。

7. **`PhotoViewPicker` 返回媒体库 URI**(`file://media/...`),`image.createImageSource(uri)` 处理不了,必须先 `fileIo` 打开成 fd。
   迷惑点:预览能正常显示,因为 `Image` 组件原生支持媒体库 URI —— **走的不是同一条路,预览正常不代表能解码**。

8. **解码期就缩放**(`desiredSize`),不要先全尺寸解码再缩 —— 手机照片几千像素宽,全解是几十 MB。

9. **`letterSpacing` 必须在 `measureText` 之前设** —— 它计入测量结果,漏设的话字间距一拉大就折行不准、右边溢出。

10. **Canvas 2D 没有原生下划线** —— 只能自己量出行宽再画实心矩形。粗细要随字号缩放,固定 1px 在大字号下细得几乎打不出来。

11. **抖动是同步密集计算** —— 直接跑会占满 UI 线程,加载态根本渲染不出来。计算前要 `await` 让出一帧。

12. **预览要关掉图像插值**(`ImageInterpolation.None`)—— 否则放大时点阵被糊成灰,预览就骗人了。

13. **`PixelMap` 是原生内存**,换图/退出都要 `release()`,否则来回切算法会一直堆积。

### 协议与业务逻辑

14. **开盖判断必须排在缺纸前面** —— 上盖打开时纸传感器看不到纸,会把缺纸位一起置起来。此时提示「缺纸」是误导,用户真正该做的是合盖。

15. **打印前体检要现查,不能读轮询缓存** —— 轮询间隔 10s,用户完全可能刚掀开上盖就点了打印。

16. **查不到状态时放行而非拦截** —— 宁可让打印去试一次、失败由 ACK 阶段的故障帧兜住,也不该因为一次查询超时把用户挡在门外。

17. **打印期间必须停掉状态轮询** —— 否则查询字节会混进打印数据流。

18. **断开连接后必须清空硬件读数** —— 否则会残留上次连接的纸张/电量,变成假数据。

19. **蓝牙事件回调必须是具名引用** —— 匿名函数无法 `off()`。

### SDK API 位置

20. **`getUIFontConfig()` 只在 `@ohos.font` 全局命名空间上**,`UIContext.getFont()` 里没有(那里只有 `registerFont` / `getSystemFontList` / `getFontByName`)。所以代码里两种调用方式并存。

21. **`getSystemFontList()` 只返回「已安装字体」** —— 很多机型上这份列表很短甚至是空的。要拿到完整字体列表得配合 `getUIFontConfig()` 的 `generic` + `alias` + `fallbackGroups` 三层。

22. **API 24 下 `PermissionRequestResult` 是正常导出的** —— 早期版本那种「本地声明接口再强转」的绕法不需要。

23. **`USE_BLUETOOTH` / `DISCOVER_BLUETOOTH` 在 API 24 权限表里依然有效**,`module.json5` 不用改。

---

## 六、关键设计决定

| 决定 | 理由 |
|---|---|
| 全量 V2 状态装饰器 | 打印机状态要跨组件共享;V1/V2 混用是已知坑 |
| 底部导航自绘(`Tabs` + `barHeight(0)`) | 参考图是圆角浮起白条,`Tabs` 原生 bar 设不了圆角;自绘既保住页面状态保持又拿到视觉控制权 |
| `Navigation` 而非 `router` | `router` 已在被逐步淘汰 |
| 系统栏染白而非沉浸式全屏 | 沉浸式要自己算安全区插入量给顶栏补 padding,容易错;顶栏本来就是白的,染白系统栏视觉一样无缝 |
| 锁定浅色模式 | 配色按浅色设计,深色模式下会撞色。后续要做深色模式再加 `resources/dark/` |
| 抖动阈值恒用 128 | 误差扩散的前提是量化点落在灰阶中点;用文字那套 212 会让整幅图压黑 |
| 缓存灰度数据 | 切换抖动算法时复用,不重新解码图片 |
| 设备名过滤 `Qring` 前缀 | 用 `discoveryResult` 事件而非 `bluetoothDeviceFind` —— 后者只给 MAC,名字要另查且刚发现时可能还没解析出来,按名字过滤会漏设备 |

---

## 七、未完成 / 未验证

### 7.1 桌面图标(待执行)

需要复制二进制文件,受工具限制未能完成。命令:

```bash
cp "<apk 解压目录>/res/mipmap-xxxhdpi-v4/ic_launcher.png" \
   "<项目根>/AppScope/resources/base/media/foreground.png"
cp "<apk 解压目录>/res/mipmap-xxxhdpi-v4/ic_launcher.png" \
   "<项目根>/entry/src/main/resources/base/media/foreground.png"
cp "<apk 解压目录>/res/mipmap-xxxhdpi-v4/ic_launcher.png" \
   "<项目根>/entry/src/main/resources/base/media/startIcon.png"
```

`AppScope/app.json5` 用的是 `$media:layered_image`,指向 `background` + `foreground` 两层,替换前景层即可,**不用改任何配置**。

> 注意:源图是完整方形设计(自带浅色底和蓝色水波)。放进 layered 前景层后启动器会套圆角遮罩,四角会被裁掉一点。如果裁切后不好看,需要把主体缩小、四周留透明边距 —— 这一步需要图像编辑。

### 7.2 编译验证

**截至 08-04:整个工程从未成功编译过**(构建工具持续不可用)。
**08-06 更正:命令行构建已验证通过** ——
`"<DevEco 安装目录>/tools/node/node.exe" "<DevEco 安装目录>/tools/hvigor/bin/hvigorw.js" assembleHap --no-daemon`
在 08-06 会话中连续多次 BUILD SUCCESSFUL,下方列的未验证点随之基本落定(代码已在真机跑通)。

```bash
cd <项目根> && \
"<DevEco 安装目录>/tools/node/node.exe" \
"<DevEco 安装目录>/tools/hvigor/bin/hvigorw.js" \
assembleHap --no-daemon
```

主要未验证点:

- `@Entry` + `@ComponentV2` 组合、`onPageShow` 在 V2 下是否可用
- `stateStyles` 内联块
- `$r()` 在模块顶层
- `OffscreenCanvasRenderingContext2D` 能否脱离 Canvas 组件独立出图、`getPixelMap()` 是否可用
  → 这是文字打印的命门,失败的话要改成挂隐藏 `Canvas` 组件渲染
  → **但真机已验证文字打印正常,说明这条路是通的**
- `image.createPixelMap(buffer, options)` 构造预览位图
- `showAlertDialog` 的 `confirm` 字段
- `font.UIFontConfig` 类型引用、`@kit.ArkUI` 是否导出 `font`

### 7.3 真机验证状态

| 功能 | 状态 |
|---|---|
| 蓝牙连接、SPP 收发 | ✅ 已验证 |
| 文字打印 | ✅ 已验证 |
| 图片打印 | ✅ 已验证(修复媒体库 URI 解码后) |
| 自定义打印画布编辑器(插入/拖动/缩放/双击编辑/保存模板) | ✅ 已验证(08-06 修复编辑弹窗后) |
| 模板 / 历史 持久化与跳转 | ✅ 已实现(设备侧细节未逐一回归) |
| 抖动算法三选一 | ✅ 已验证 |
| 排版设置(字体/BIU/间距) | ✅ 已验证 |
| 故障拦截(缺纸/开盖/过热) | ✅ 已验证，过热未触发 |
| 电量读数 | ✅ 已验证 |
| 冷启动自动重连 | ✅ 已验证 |

---

## 八、后续可做(截至 08-06 更新)

- ✅ 文字打印加实时预览 → 已完成(打印预览半模态)
- ✅ 「打印二维码」「自定义打印」两个入口 → 已完成(条码打印页 + 画布编辑器)
- ✅ 模板 / 历史 / 我的 三个 Tab → 已完成
- 图片预览加抖动前后对比(可选)
- 抖动计算挪到 TaskPool,避免长图卡顿
- ✅ 深色模式资源
---

## 九、自定义打印画布编辑器(08-04 之后完成)

画布编辑器是「完成的内容」里最后一块拼图,单独一节记录关键设计 ——
这块绕了最多的 V2 响应式坑。

### 9.1 关键设计

1. **坐标系统一为打印点** —— 模型全存点(dotX/dotY/dotW/dotH),屏幕乘 DISPLAY_SCALE 显示,合成零换算;拖拽的 vp 位移除以缩放比即得点数。
2. **逐元素二值化再 OR 合并** —— 图片要 Floyd、文字要阈值 212、条码要阈值 128 且不能抖动,拍平到一张灰度再统一二值化会毁掉其中两类。
3. **@ObservedV2 数组 ForEach 刷新不可靠的绕法** —— 内容显示靠父组件直接观察 `el.preview`(@Trace);几何变化用 revision 自增 + 重赋数组,再配 if/else 双分支强制重建整棵画布,保证闭包必然读到最新位图。
4. **选中操作排浮在画布最上层** —— 不挂在元素内部(会被其他元素压住);宽度 auto 只包住按钮,不遮挡同高度区域元素的点击。
5. **手势独占组:双击 > 单击 > 拖动** —— 双击放最前,否则单击先成功、双击永远等不到第二下。

## 十、08-06 收尾:两处修复

### 10.1 元素编辑弹窗不弹出 / 渲染错位

- **现象**:双击元素不弹编辑窗;点「保存模板」后编辑窗才以错乱姿态(高度异常、顶到状态栏)叠在保存弹窗后面。
- **根因**:编辑弹窗用的 `bindContentCover` 在沉浸 NavDestination 下渲染错位;且没有 `onDisappear` 回置,`showEditor` 卡死为 true,残留在保存弹窗后面。
- **修复**(`view/CustomPrintPage.ets`):
  - 编辑弹窗改用 `bindSheet`,与文字打印页预览弹窗同一套路(项目里 bindSheet 在沉浸 NavDestination 下可用);
  - 挂在底部按钮行,避免同一节点挂两个 bindSheet 互相覆盖;
  - 补 `onDisappear` 回置 `showEditor`,下滑关闭不残留;
  - `saveTemplate()` 前先 `showEditor = false` 兜底。
- **教训**:沉浸 NavDestination 下弹窗优先用 `bindSheet`;`bindContentCover` 定位/层级不可靠。
