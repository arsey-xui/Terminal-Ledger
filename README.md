# 终端账本 Terminal-Ledger · 个人双端同步记账系统 

一套 **离线优先、双端同步** 的个人记账系统，覆盖 Android 手机端、JavaFX 电脑端与 Spring Boot 云端同步服务。

---

## 项目简介

- **离线优先**：手机端（SQLite）与电脑端（H2）各自维护全量本地数据，断网时所有操作照常进行。
- **云端同步**：保存记账后自动触发一次，也可手动点「立即同步」；云端按 `last_modified` 做 **最后写入胜出（LWW, Last-Write-Wins）** 合并，冲突无需人工选择。两端当前都是**全量对账**（push 送全量本地数据、pull 从水位 0 翻页拉取，服务端只返回近 3 个月），没有做水位增量，也没有网络状态监听或后台定时同步。手机端设置页显示「上次同步于 X 分钟前」，失败时给出原因并可「重试同步」。
- **C 级报表**：流水记录、分类下钻、跨月趋势（近 6 个月，可切换支出/收入/单个分类；点击或悬停看金额）、近 30 天趋势、环比对比、预算进度条、年度日历热力图。
- **高级亮点**：截图记账（本机离线识别；配了自己的 API Key 可改用多模态大模型，自动填好金额、账户、分类；支持从相册选图，也可以直接在微信/支付宝里把截图「分享」进来）、报表层 AI 消费分析（内置规则引擎兜底，分析里给出本月日平均支出 / 日支出标准差 / 日支出中位数，提示词可自己改）、应用内检查更新（直连 GitHub Releases）、Android 桌面小组件、输入法语音 + 文本解析记账。
- **数据安全**：CSV 导出、加密 `.zip` 完整备份包一键导入导出（手机端与电脑端格式一致、可互相恢复）、账单照片上传。

---

## 技术栈

| 端 | 技术 | 本地存储 | 构建 |
| --- | --- | --- | --- |
| 云端 | Spring Boot 3.x + Spring Data JPA | MySQL | Maven |
| 移动端 | Android 原生 Java | SQLite (Room 风格 DAO) | Gradle |
| 桌面端 | JavaFX (Java 17+) | H2 (嵌入式) | Maven |

同步协议：RESTful API + JSON，全量对账 + 时间戳 LWW 合并（协议支持水位增量，客户端暂未启用）。

---

## 目录结构

```
Terminal-Ledger/
├── backend/        # Spring Boot 3 云端同步服务 (Maven)
├── android/        # Android 原生 Java 客户端 (Gradle)
├── desktop/        # JavaFX 桌面客户端 (Maven)
├── sql/            # 云端 MySQL DDL + 示例数据
├── docs/           # ER 图、架构、同步协议、API、AI 设计
├── README.md
├── LICENSE
└── .gitignore
```

---

## 本地运行步骤

### 0. 一键启动后端（推荐）

无需手动装 MySQL 与 JDK，双击或一条命令即可启动：

- **双击**：`scripts/start-backend.bat`
- **命令行**（仓库根目录）：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-backend.ps1        # 默认开发模式：H2 嵌入式库，无需 MySQL/Docker
powershell -ExecutionPolicy Bypass -File .\scripts\start-backend.ps1 -Docker # Docker Compose 起 MySQL + 后端（生产同构）
powershell -ExecutionPolicy Bypass -File .\scripts\start-backend.ps1 -Stop   # 停止
```

脚本会自动等待健康检查（`GET /api/health`）通过后输出就绪信息；首次启动需下载依赖，稍慢。更多参数（`-Foreground` 前台看日志、`-NoWait` 不等待检查）见脚本头部注释。

### 1. 后端（Spring Boot，手动方式）

```bash
cd backend
export DB_URL=jdbc:mysql://localhost:3306/accounting?useSSL=false&serverTimezone=UTC
export DB_USERNAME=root
export DB_PASSWORD=your-password
export SYNC_TOKEN_SECRET=change-me-in-production
mvn spring-boot:run
```

接口默认地址：`http://localhost:8080/api`，健康检查：`GET /api/health`。

> Windows PowerShell：`$env:DB_PASSWORD="your-password"` 后再运行。

### 1b. 用 Docker 一键启动（推荐，含 MySQL + 后端）

无需手动装 MySQL 与 JDK，一条命令启动整套后端：

```bash
cp .env.example .env     # 首次：按需改密码
docker compose up -d      # 起 MySQL + 后端
```

- 首次会自动通过 `sql/init.sql` 初始化 `accounting` 库与 Demo 数据。
- 后端：`http://localhost:8080`，健康检查 `GET http://localhost:8080/api/health`。
- 停止：`docker compose down`；清数据（含库）：`docker compose down -v`。
- 需要联网下载基础镜像与 Maven 依赖，首次构建稍慢。

### 2. Android Studio 打开方式

1. 打开 Android Studio。
2. `File -> Open`，选择本仓库的 `android/` 目录。
3. 确认 `app/build.gradle`：`applicationId` 为 `com.personal.accounting.android`、`minSdk` 为 21（Android 5.0+）。
4. 首次运行后，在 App「设置 → 云端地址」填你的后端地址（如 `http://<电脑局域网IP>:8080`；Android 模拟器访问本机用 `http://10.0.2.2:8080`），点「保存地址」。
5. 运行 `app`。

### 3. 电脑端（JavaFX）

**直接运行便携版（推荐）**：双击 `outputs\TerminalLedger\TerminalLedger.exe`（免安装、自带运行环境；也可解压 `outputs\TerminalLedger-Desktop-<version>.zip` 后运行同名 exe）。

**或用源码运行**：

```bash
cd desktop
mvn javafx:run
```

首次运行会在用户目录生成 `accounting/` 数据目录（H2 文件 + 设备号）。在「数据管理 → 设置后端地址」填后端地址后即可启用同步。

> 若 Maven Central 握手失败，可先建一个含镜像的 `maven-settings-local.xml` 并用 `mvn -s ..\maven-settings-local.xml javafx:run`（该文件是本机配置，已加入 `.gitignore`）。

---

## 数据同步架构（简要）

```
[Android SQLite] ──push──▶ [Spring Boot / MySQL] ◀──pull── [JavaFX H2]
        │                            │                        │
        └──── 全量对账 + LWW        ─┘                        └────────┘
```

每条业务记录都带：

- `id`（本地自增）+ `client_id`（全局唯一，UUID 或客户端生成）
- `last_modified`（修改时间戳，LWW 依据）
- `deleted`（软删除墓碑，保证删除能同步）

客户端以 `device_id` + `last_sync_at` 记录同步水位；单次拉取默认限制最近 3 个月，更早数据按需分页。

详细设计见 [docs/SYNC-PROTOCOL.md](docs/SYNC-PROTOCOL.md) 和 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

---

## 环境变量（后端）

在 `backend/src/main/resources/application.yml` 中，敏感项均使用 `${}` 占位符。部署时需设置：

| 变量 | 说明 | 示例 |
| --- | --- | --- |
| `DB_URL` | MySQL 连接串 | `jdbc:mysql://localhost:3306/accounting` |
| `DB_USERNAME` | 数据库用户名 | `root` |
| `DB_PASSWORD` | 数据库密码 | `******` |
| `SYNC_TOKEN_SECRET` | 同步令牌签名密钥 | 至少 32 字节随机值 |
| `AI_API_KEY`（可选） | AI 消费分析的大模型 Key | 留空则用规则引擎 |
| `AI_MODEL`（可选） | 大模型名 | `gpt-4o-mini` |

---

## 数据库初始化

导入云端 DDL 与示例数据：

```bash
mysql -u root -p < sql/init.sql
```

本地 SQLite / H2 的表结构在首次启动时由代码自动创建，无需手工建表。

---

## 模块文档

- [docs/FEATURES.md](docs/FEATURES.md) — 功能介绍
- [docs/ER-diagram.md](docs/ER-diagram.md) — 云端与本地差异点
- [docs/SYNC-PROTOCOL.md](docs/SYNC-PROTOCOL.md) — 同步与 LWW
- [docs/API.md](docs/API.md) — REST API
- [docs/AI-ANALYSIS.md](docs/AI-ANALYSIS.md) — AI 消费分析接口
- [docs/VOICE-AND-WIDGET.md](docs/VOICE-AND-WIDGET.md) — 语音记账与小组件
- [docs/RECURRING.md](docs/RECURRING.md) — 周期记账（自动续费 / 固定收支）
- [docs/RELEASE-NOTES-v1.3.0.md](docs/RELEASE-NOTES-v1.3.0.md) — 当前版本（v1.3.0）更新说明
- [docs/RELEASE-NOTES-v1.2.0.md](docs/RELEASE-NOTES-v1.2.0.md) — v1.2.0（周期记账）更新说明
- [docs/RELEASE-NOTES-v1.1.1.md](docs/RELEASE-NOTES-v1.1.1.md) — v1.1.1（修订版，已并入 v1.2.0 发布）
- [docs/RELEASE-NOTES-v1.1.0.md](docs/RELEASE-NOTES-v1.1.0.md) — 更早版本（v1.1.0）更新说明
- [docs/BUILD-AND-RELEASE.md](docs/BUILD-AND-RELEASE.md) — 桌面包 / Android APK / Docker 一键出包
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — 整体架构
- [docs/UI-SPEC.md](docs/UI-SPEC.md) — 多端 UI 布局

---

## 许可证

本项目基于 [MIT License](LICENSE) 发布。欢迎 Fork 与提交 PR。
