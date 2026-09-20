# 功能介绍

本系统是一套 **离线优先、双端同步** 的个人记账应用，覆盖 Android 手机端、JavaFX 桌面端与 Spring Boot 云端同步服务。所有核心功能均可离线使用，联网后自动做双向增量同步。

## 端能力总览

| 功能 | Android | 桌面端 (JavaFX) | 云端 (Spring Boot) |
| --- | :---: | :---: | :---: |
| 记账（流水 / 分类 / 账户 / 预算 / 模板） | ✅ | ✅ | ✅（REST API） |
| 本地离线存储 | ✅ SQLite | ✅ H2 嵌入式 | ✅ MySQL 权威库 |
| 双向增量同步（LWW 合并） | ✅ | ✅ | 权威存储 + 合并 |
| 报表中心（C 级报表） | ✅ | ✅ | ✅ 可云端计算 |
| AI 消费分析 | — | ✅ | ✅ |
| 语音快捷记账 | ✅ | — | — |
| 桌面小组件（快捷记账） | ✅ | — | — |
| 账单照片上传 | — | — | ✅ |
| CSV 导出 / 加密备份包 | — | ✅ | — |

## 1. 离线优先记账

- 所有写操作只落本地库，界面不依赖网络，断网时记账、查账、报表照常可用。
- 五大业务实体：**账户、分类、流水、预算、模板**；本地（SQLite / H2）与云端 MySQL 表结构同构，差异详见 [docs/ER-diagram.md](ER-diagram.md)。
- 桌面端本地库为 H2 文件库（用户目录 `accounting/`），Android 端为 SQLite。

## 2. 双端增量同步（LWW）

- 每条可同步记录携带 `client_id`（全局唯一）、`last_modified`（修改时间戳）、`deleted`（软删除墓碑）。
- 接口：`POST /api/sync/push`、`/api/sync/pull`、`/api/sync/pull-window`（历史窗口回补）。
- 合并策略：**Last-Write-Wins**，时间戳较大者胜出；软删除保证删除操作也能同步。
- 流量控制：以 `last_modified > 水位` 增量拉取，游标分页（默认 500 条/页），首次默认只拉最近 3 个月。
- 安全：同步令牌由 `SYNC_TOKEN_SECRET` 签名，TTL 默认 168 小时。
- 详见 [docs/SYNC-PROTOCOL.md](SYNC-PROTOCOL.md)。

## 3. 报表中心（C 级报表）

- **月度汇总**：收入 / 支出 / 结余 / 环比对比。
- **分类占比**：按分类统计支出构成。
- **近 30 天趋势**：每日收支走势。
- **预算进度**：按分类预算进度条，含预警级别。
- **年度日历热力图**：全年每日收支热力视图。
- **流水与下钻**：按月流水列表、分类下钻查询。
- 实现：Android 用 MPAndroidChart，桌面端用 JavaFX Charts；云端提供 `/api/report/*` REST 接口，报表也可在云端计算。

## 4. AI 消费分析

- **规则引擎兜底**：未配置大模型 Key 时，按内置规则生成消费分析，保证功能可用。
- **大模型增强**：配置 `AI_API_KEY` / `AI_MODEL` / `AI_BASE_URL` 后，调用 OpenAI 兼容接口，把结构化报表数据拼成上下文生成消费洞察与建议。
- 接口：`POST /api/report/ai`；桌面端内置 `AiReportGenerator`。
- 详见 [docs/AI-ANALYSIS.md](AI-ANALYSIS.md)。

## 5. 语音快捷记账（Android）

- 系统语音识别 + `VoiceInputParser` 解析，支持「午餐 27 元」「打车 15 块」这类口语，自动提取金额并给出分类建议。
- 关键词库覆盖餐饮 / 交通 / 购物 / 住房 / 通讯 / 医疗 / 教育 / 娱乐 / 人情等常见场景。
- 解析失败时回退到「其他」分类，不阻塞记账。
- 详见 [docs/VOICE-AND-WIDGET.md](VOICE-AND-WIDGET.md)。

## 6. Android 桌面小组件

- `QuickAddWidgetProvider` + `QuickAddWidgetActivity`：主屏小组件一键快捷记账，无需打开 App。

## 7. 数据安全与备份（桌面端）

- **CSV 导出**：一键导出流水为 CSV。
- **加密备份包**：`.zip` + **AES-256-GCM**（PBKDF2 口令派生密钥），一键导入 / 恢复；恢复会覆盖当前数据，需二次确认。

## 8. 账单照片上传

- `POST /api/photos`（multipart）上传账单照片，UUID 重命名存储于 `backend/uploads/`。

## 9. 快速上手

- **一键启动后端**：双击 `scripts/start-backend.bat`，或运行 `scripts/start-backend.ps1`（默认 H2 开发模式，无需 MySQL/Docker；加 `-Docker` 起 MySQL 生产同构栈）。
- **客户端地址**：桌面端默认 `http://localhost:8080`；Android 模拟器 `http://10.0.2.2:8080`，真机 `http://192.168.43.84:8080`（App「设置 → 云端地址」可随时改）。
- 出包与安装详见 [docs/BUILD-AND-RELEASE.md](BUILD-AND-RELEASE.md) 与 [outputs/INSTALL-GUIDE.md](../outputs/INSTALL-GUIDE.md)。

## 10. 周期记账（自动续费 / 固定收支）

- **适合场景**：房租、月供、各类会员等「每隔一段时间固定一笔」的收支。
- **规则**：名称、金额、支出/收入、分类、账户、备注 + **间隔（每 N 天 / 周 / 月）** + 首次日期。
  「每 30 天续费」直接填 `30 天`；每月规则按首次日期的「日」重复，当月没有该日自动取月末。
- **待确认清单**：到期不直接入账，而是生成待确认项，**确认（可改金额）后才写入流水**，也可「忽略本期」。
- **幂等**：待确认项 ID 与生成的流水 ID 都是 `rr-<规则ID前8位>-<日期>`，重复生成不会重复，
  两台设备各自确认同一期同步后也只会留下一条流水。
- **同步**：规则跨设备共享（`/api/sync/push|pull` 的 `recurringRules[]`），待确认项只存本机。
- 入口：手机端「设置 → 周期记账」，电脑端左侧导航「周期记账」。详见 [docs/RECURRING.md](RECURRING.md)。

## 相关文档

- [docs/ARCHITECTURE.md](ARCHITECTURE.md) — 整体架构
- [docs/ER-diagram.md](ER-diagram.md) — 数据模型
- [docs/SYNC-PROTOCOL.md](SYNC-PROTOCOL.md) — 同步协议
- [docs/API.md](API.md) — REST API
- [docs/UI-SPEC.md](UI-SPEC.md) — 多端 UI 布局
