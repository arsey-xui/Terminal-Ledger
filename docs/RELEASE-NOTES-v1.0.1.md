# v1.0.1

个人双端同步记账系统 —— **离线优先、双端同步**（Android 手机端 + JavaFX 电脑端 + Spring Boot 云端同步服务）。

## 亮点
- **离线优先**：手机端（SQLite）与电脑端（H2）各自维护全量本地数据，断网照常记账/看报表。
- **增量同步（LWW）**：基于 `last_modified` 时间戳，最后写入胜出（Last-Write-Wins），冲突无需人工选择。
- **C 级报表**：流水记录、分类下钻、近 30 天趋势、环比、预算进度、年度热力图、AI 消费分析（规则引擎兜底，可选填 API Key）。
- **快捷记账**：语音解析记账、Android 桌面小组件快速记账。
- **数据安全**：CSV 导出、加密 `.zip` 完整备份、账单照片上传。

## 本次更新（v1.0.1 · 小版本）
- **桌面端：新建分类**——记账页表单新增“新建分类”按钮，可建支出/收入分类（支持填写 emoji 图标），并修正收入记账时误查支出分类的问题。
- **手机端：新增分类可配 emoji**——新增分类对话框支持同时填写分类名称与 emoji 图标。
- **双端：近 30 天每日支出图显示横纵坐标具体数值**——手机端 Y 轴显示金额数值、X 轴日期刻度更清晰；电脑端 X 轴显示具体日期、Y 轴显示金额并标注轴标题。

## 产物
| 平台 | 文件 | 说明 |
| --- | --- | --- |
| 电脑端 | `TerminalLedger-Desktop-1.0.1.zip` | 免安装、自带运行环境，解压后双击 `TerminalLedger\TerminalLedger.exe` |
| 手机端 | `TerminalLedger-1.0.1-arm64-v8a.apk` | 传到手机安装，允许“未知来源应用” |
| 手机端 | `TerminalLedger-1.0.1.zip` | 含 APK，便于分发 |

> 手机端 APK：`.\scripts\build-android-release.ps1`；电脑端：`.\scripts\build-desktop-installer.ps1`。详见 `README.md` / `docs/INSTALL.md`。

---
**License:** MIT · 欢迎 Star / 反馈 Issue
