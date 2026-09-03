# Personal-Accounting-App 交付概览

本项目为「Java 生态 个人双端同步记账系统」完整工程化交付，代码位于仓库根目录，已按开源标准组织（MIT + `.gitignore` + README + 环境变量脱敏）。

## 桌面端已打成可运行分发包

> 无需安装 Java / Maven（已内置 JDK 运行时）。

- 双击版：`outputs/portable3/PersonalAccounting/PersonalAccounting.exe`（**最新：含收入/支出切换、全量对账、`ApiResponse.data` 解包修复、JavaFX 17**）
- 便携版 zip：`outputs/PersonalAccounting-Desktop-Portable-v0.1.0.zip`

> 请用 **`outputs/portable3/`**。旧 `outputs/release/`、`outputs/portable/`、`outputs/portable2/` 均为早期/未覆盖版本，可删除。

首次使用会在用户目录 `accounting/` 下生成 H2 数据库与设备 ID；离线即可记账/看报表。想手机↔电脑同步，需另起云端后端。

> 说明：当前交付为**便携式 app-image**（自带运行时的绿色版）。真正的 `.msi/.exe` 安装向导需要安装 WiX Toolset 后再用 `jpackage --type msi/exe` 产出；如果你需要，我可以继续。

## 通过文件快速定位

- 架构与 ER：`../docs/ER-diagram.md`、`../docs/ARCHITECTURE.md`
- 同步与 LWW：`../docs/SYNC-PROTOCOL.md` + `../backend/src/main/java/com/personal/accounting/service/impl/SyncServiceImpl.java`
- REST API：`../docs/API.md`
- AI 分析占位接口：`../docs/AI-ANALYSIS.md` + `../backend/src/main/java/com/personal/accounting/service/ai/LlmClient.java`
- 语音记账与小组件：`../docs/VOICE-AND-WIDGET.md`
- 背景与 UI：`../docs/UI-SPEC.md`
- 云端初始化：`../sql/init.sql`

## 三大端模块

| 模块 | 构建 | 本地库 | 入口 |
| --- | --- | --- | --- |
| `backend/` | Maven | 无（主库 MySQL） | `AccountingApplication` |
| `android/` | Gradle | SQLite | `MainActivity` |
| `desktop/` | Maven | H2 | `DesktopApp` |

## 运行入口

- 后端：`cd backend && mvn spring-boot:run`（需设置 `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`/`SYNC_TOKEN_SECRET`）
- Android：Android Studio 打开 `android/`，真机改 `SyncConfig.BASE_URL`
- 桌面：`cd desktop && mvn -s ..\maven-settings-local.xml javafx:run`（首次在用户目录 `accounting/` 生成 H2 与设备 ID）

## 亮点实现位置

- LWW 合并：后端 `SyncServiceImpl`（push 权威合并）+ 客户端 `SyncManager.upsertLww`
- 语音解析：`../android/app/src/main/java/com/personal/accounting/android/voice/VoiceInputParser.java`
- 小组件：`../android/app/src/main/java/com/personal/accounting/android/widget/QuickAddWidgetProvider.java`
- 加密备份：`../desktop/src/main/java/com/personal/accounting/desktop/service/BackupService.java`
- AI 消费分析：`../backend/src/main/java/com/personal/accounting/service/impl/AiReportServiceImpl.java` + `../desktop/src/main/java/com/personal/accounting/desktop/service/AiReportGenerator.java`

## 一键出包（构建与发布）

见 `../docs/BUILD-AND-RELEASE.md`。三个产物对应的入口：

- 后端：`../docker-compose.yml` → `docker compose up -d`
- 桌面包：`../scripts/build-desktop-installer.ps1`
- Android APK：`../scripts/build-android-release.ps1 -GenerateKeystore`
