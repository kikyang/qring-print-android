# 上游 GitHub 巡检报告（2026-08-27）

> 周期：**每 3 天一次**（3 天周期第 3 次，8-20 → 8-24 → 8-27）
> 对比基线：upstream-audit-2026-08-24.md
> 说明：本轮为 3 天周期第 3 次，按要求在本轮结束后询问用户是否降为一周一次

## 一、本轮发现

### 已知仓库动态（主跟踪）

| 仓库 | 状态 | 变化 |
|---|---|---|
| Thisko/QrintPrint（HarmonyOS） | 无变化 | 停在 8-10（更新icon） |
| lztttt/QrintPrint-Android | 无变化 | 停在 8-16 v1.5.0 |
| soulxyz/xyprt_android | 无变化 | 停在 8-20（chore/versionCode 元数据，非功能） |
| snowboys/QrintPrint-Windows | 无变化 | 停在 8-12 |
| bzhou830/QringPrint | 无变化 | 停在 8-07 initial commit |
| kikyang/qring-print-android（我方） | v0.7.3 | 停在 8-19（巡检文档） |

### 观察名单动态（本轮重点）

| 仓库 | 状态 | 变化 |
|---|---|---|
| **ZhaYi-Miao/QrintPrint-Windows** | **有实质更新** | **8-25 v1.1.3**：更名「热印 - ThermoPrint」；**新增函数图像打印**（表达式解析/求值/渲染器、表达式编辑器控件、HTTP 接口与设置）——**本轮唯一新功能** |
| yiran168/suda-Android | 持续开发确认 | 8-22 连发 6 提交（文档导入/多页照片工作流/Office XML Android 化/旧版 Office 导入 + 手绘照片套索/分页对话框 + 相册套索裁剪/PPT 缩放版式展平），8-26 1 提交（修复多行编辑器字段边界）——**8-24 报告「宣传未实现」风险显著下降，功能逐项落地中** |
| yiran168/suda-win-web | 无代码变化 | commits 停 8-17，release 仍 v1.0.0（8-16）；8-26 检索 updated 系 gh-pages 部署推送，非代码提交 |
| BA4RFY/QringAndroid | 无代码变化 | commits/release 均停 8-12；8-26 检索 updated 为索引假象（pushed_at 8-12） |
| tanadiejiang/pocket_print | 无变化 | 停在 8-12 V1.5.0 |
| ZhaYi-Miao/QrintPrint-Web-Console | 无变化 | 停在 8-13 Initial commit |
| Thisko/QrintPrint-Web | 不可访问 | API 仍 404，维持移除观察 |

### 新仓库

- 无新增相关仓库。
- 「qring」检索新出现 `elifsuttatli-alt/qringpi`（Python，8-12 建，8-19 更新）：文件为 keypad/OLED/SignalR/api_service —— **门禁/智能家居按键工程**，与热敏打印机无关，排除。

### 无关项排除

- 「错题小印」检索其余命中（gaoxiangyang2022/math_problems、jackli01030/shiyi-math-practice、limin6661/edu-tools-kit、lzbtthappy/xmpzy_for_excel）均为数学题库/成绩打印方向，不涉打印机协议，维持排除。
- 「qring」检索其余命中仍为 smart-ring 系（pulse-layer、ring-set）、量子 QRNG 系（btq-ag/QRiNG、I4cTime/homebrew-tap、MultAI qring-*）、学生项目（2026-dmu-quiz-language、fitaccessng）与公司官网（akizu815、TheJ4nitorNG、Burger_QRing、dahiko2 等），均无关，排除。

## 二、功能吸收评估

**结论：本轮唯一新功能 = ZhaYi-Miao 函数图像打印；suda-Android 持续落地已收录候选池特性，无新增大项。**

### 新增候选（1 项）

| 候选 | 来源 | 价值 | 评估 |
|---|---|---|---|
| **函数图像打印**（表达式解析/求值/渲染器 + 表达式编辑器，y=ax²+b/三角函数等，384 宽栅格化出图） | ZhaYi-Miao/QrintPrint-Windows v1.1.3 | 中 | 数学学习向新功能，**我方及 lztttt/suda 均无同类**；契合 #24 模板生成器方向（「函数图像」作为快捷生成入口）。工作量中，纯 Kotlin 零依赖可实现（表达式求值器 + 坐标系渲染）。**本轮仅入候选池，不做吸收决定** |

### suda-Android 落地情况复核

8-22/8-26 提交逐项核对：

- 文档直印全格式（含旧版 Office 导入）、PPT 展平、多页照片工作流 —— 均为 8-24 报告已收录候选池特性的**落地实现**，无新功能承诺。
- 新增「手绘照片套索/相册套索裁剪」—— 我方 #22 手动裁剪为矩形（自由/1:1/3:4/4:3），套索为自由曲线裁剪，属低价值增量，**不入候选池**。
- 结论：suda-Android 宣传未实现风险下降，候选池特性（离线 OCR/变量数据批量/递增流水号/19 种条码/文档直印）维持，待代码级核查时优先验证已落地的文档导入链路。

### 已吸收项确认（不重复）

PDF/批量/表格/Markdown/课程表（lztttt）、PPT 导入/Word 公式排版（#17/#18）、三算法增强（#16）、照片旋转缩放（#15）、多份打印（#19）、主题（#20）、口算题/单词表模板方向（#24）——本轮上游无同类新变化，无需重复并入。

## 三、结论

- 本轮唯一实质性新功能 = **ZhaYi-Miao 函数图像打印**（入候选池，未做吸收决定）。
- **suda-Android 由「宣传未实现风险」转为「持续开发中」，候选池维持待核查**；上游整体仍处低频期（主跟踪仓库全部无变化）。
- **巡检频率进度**：每 3 天周期已完成 **3 次**（8-20、8-24、8-27）。按用户 8-20 定：**本轮结束后询问是否降为一周一次**。
- **2026-08-27 定案：降为一周一次**（用户确认）。后续每 7 天巡检一次；若上游出现新仓库/新功能可提前巡检。
