# 错题小印 · 界面设计提案（2026-09-05）

> 目的：基于当前 v0.7.5 已上线界面，参考优秀相关项目外观，先产出几套可选风格。
> 用户已确认 8 套全部采用并随 **v0.7.6 落地**（Design.kt 增加对应 UiTheme 枚举与调色板，我的页主题选择改为竖向列表）；本目录保留为设计稿/参考预览。

## 打开方式

先打开 `index.html` 看 8 套风格总览，再分别打开：

- `style-a-qring.html` —— **A · QringPrint 紫罗兰果冻卡**
- `style-b-xyprt.html` —— **B · xyprt 墨绿纸感极简**
- `style-c-linear.html` —— **C · Linear/Raycast 暗色效能**
- `style-d-notebook.html` —— **D · 错题本手账纸感**
- `style-e-thermal.html` —— **E · 热敏打印黑白墨点**
- `style-f-clay.html` —— **F · Clay 奶油多彩手工纸**
- `style-g-apple.html` —— **G · Apple Bento 黑白极简**
- `style-h-glass.html` —— **H · 玻璃拟态 Liquid Glass**

每套都包含「首页 / 打印 / 我的」三屏静态预览、色板、适合氛围与落地改动。

也可以直接看 `previews/` 下的整页截图：

- `previews/index.png`
- `previews/a.png` ~ `previews/h.png`

## 当前界面状况（作为设计基线）

- 结构：底部三 Tab（首页 / 打印 / 我的）+ 打印页内二级切换（文字 / 图片 / 条码 / 文档 / 其它）
- 现状：v0.7.6 起仅 **8 套差异化主题**（旧微信风/简洁风/蓝白风已移除）
- 设计系统集中在 `android/app/src/main/java/com/qring/print/Design.kt`：
  - `UiTheme` 枚举 + `Palette` 调色板 + `RADIUS_SM` + `buttonRadius()`
  - 卡片、标题、按钮、分段控件均按主题切换
- 已接入：`Design.kt` 增加 8 个枚举和调色板、按主题调整 radius/shape/选中态；我的页用竖向主题列表
- 参考截图：`temp/ref_xyprt/`、`temp/ref_qrint/`、`temp/ref_old/`

## 参考项目与设计依据

| 方案 | 参考对象 | 取什么 |
|---|---|---|
| A | [Thisko/QrintPrint（HarmonyOS 客户端）](https://github.com/Thisko/QrintPrint) | 浅灰紫底、白大卡、紫罗兰主色、彩色圆角功能卡、iOS 式设置页 |
| A | [tanadiejiang/pocket_print](https://github.com/tanadiejiang/pocket_print) | 紫主色 + 圆角打印图标 |
| B | [soulxyz/xyprt_android](https://github.com/soulxyz/xyprt_android) | “Paper + ink + calm teal”，纸白底、墨绿、胶囊、大留白 |
| C | [Linear 设计 Refresh](https://linear.app/now/behind-the-latest-design-refresh) | 冷静、精确、克制；卡片层次与暗色氛围 |
| C | [Raycast Design MD](https://github.com/sweetkey/awesome-design-md/blob/main/design-md/raycast/DESIGN.md) | #07080A 近黑蓝底、双重描边 + 内高光、蓝色交互、正字距 |
| D | [横线笔记本风：ArkUI 让鸿蒙错题页像真实错题本](https://ai6s.net/6a81da56662f9a54cb9d8c88.html) | 横线、米色纸、红蓝批改笔、手账式卡片 |
| E | 热敏打印机实物/回执单 | 纸白底、黑墨、点阵、等宽字体、虚线小票 |
| F | [Clay Design MD](https://github.com/sweetkey/awesome-design-md/blob/main/design-md/clay/DESIGN.md) | 奶油底、燕麦边、果汁色、硬投影、大圆角、虚线结合 |
| G | [Apple Design MD](https://github.com/sweetkey/awesome-design-md/blob/main/design-md/apple/DESIGN.md) | 黑白二分、Apple Blue、Bento 网格、负字距 |
| H | iOS 26 Liquid Glass / Glassmorphism | 渐变背景、半透明磨砂、白色高光描边 |
| 基线 | 现有 v0.7.5 截图 | 保留信息架构与功能，仅换视觉语言 |

## 后续接入代码的映射建议

### 新增枚举

```kotlin
enum class UiTheme(val label: String) {
    QRING("紫罗兰"),
    PAPER("墨绿纸感"),
    LINEARDARK("暗色效能"),
    NOTEBOOK("手账纸感"),
    THERMAL("热敏黑白"),
    CLAY("奶油多彩"),
    APPLE("Apple Bento"),
    GLASS("玻璃拟态"),
}
```

### 需要调整的 Design.kt 函数

- `Palette`：每套新增一组颜色
- `RADIUS_SM`：A 14 / B 14 / C 10 / D 12 / E 4 / F 16 / G 10 / H 18
- `buttonRadius()`：A/B/D/F 胶囊；C/E/G 方角小圆角；H 胶囊；Apple 可做 12px 方角
- `sectionTitle()`：D 红笔标题 + 横线；E 等宽“▸”标签；F 彩色标签
- `header()`：A 自定义紫头卡、B 纸白、C 深色、D 纸顶三色线、H 磨砂
- `card()`：A/B/D/F 大圆角 + 柔和/硬投影；C 双层 ring + 1px 内高光；E 小方角 + 虚线；H 半透明 + backdrop-filter
- 底部导航：A 紫光晕、C 深色半透明、D 笔色点缀、E 虚线、H 磨砂

## 设计原则

1. **不改变功能层级**：三 Tab 与二级打印 Tab 保持现状，避免迁移期功能回退。
2. **可切换**：沿用“我的 → 主题”多主题架构，新增选项即可。
3. **打印预览始终是主角**：任何风格都不能让预览区被装饰淹没。
4. **考虑深色模式**：A/B/D 可制作浅色，C 天然暗色为主。
