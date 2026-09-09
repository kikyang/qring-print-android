## v0.7.6（2026-09-05）主题改为 8 套

### 变更
- **界面主题改为 8 套差异化风格**（移除旧微信风 / xyprt 简洁风 / 喵喵机蓝白风）：
  - 紫罗兰（QringPrint 风）
  - 墨绿纸感（xyprt 升级版）
  - 暗色效能（Linear/Raycast 风）
  - 手账纸感（横线笔记本风）
  - 热敏黑白（点阵/小票/等宽）
  - 奶油多彩（Clay 风）
  - Apple Bento（黑白 + Apple Blue）
  - 玻璃拟态（Liquid Glass）
- **主题选择 UI 升级**：「我的 → 界面主题」改为竖向列表，色卡 + 名称 + 勾选态，选完立即生效
- 新增设计稿 `docs/ui-design/`（HTML 预览 + `previews/a.png` ~ `h.png`）

### 质量
- 198 例自动化测试全过
- R8 release APK 约 1.0MB
- Android 13+（minSdk 33），需授予蓝牙权限
