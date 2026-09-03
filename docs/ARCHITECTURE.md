# 整体架构

## 系统组成

```
┌─────────────────┐        ┌─────────────────┐
│  Android (Java) │        │ JavaFX Desktop  │
│  SQLite 离线缓存 │        │ H2 离线缓存      │
└────────┬────────┘        └────────┬────────┘
         │ REST + JSON              │ REST + JSON
         ▼                          ▼
┌──────────────────────────────────────────────────┐
│            Spring Boot 云端同步 + 主库            │
│              MySQL（权威存储）                    │
└──────────────────────────────────────────────────┘
```

## 分层

- **客户端**（Android / Desktop）：离线优先。本地库是唯一交互数据源；网络可用时由 `SyncManager` 做双向增量同步。报表在本地计算，保证 <100ms 查询。
- **云端**：`SyncService` 提供 push/pull；同一套 LWW 规则。报表与 AI 分析也可在云端计算（额外能力）。

## 关键决策

1. **离线优先**：UI 不依赖网络，写操作只落本地库。
2. **客户端为主**：`clientId`、`lastModified`、`deleted` 都由客户端生成，服务端只做合并与存储。
3. **LWW**：冲突由时间戳大小决出，无需用户介入。
4. **增量**：以 `last_modified > 水位` 为增量条件，配合游标分页，控制流量与电量；首拉默认最近 3 个月。
5. **软删除**：避免删除在同步中被丢失。

## 模块划分

```
backend/   Spring Boot 3.x + JPA + MySQL
android/   Android 原生 Java + SQLite + OkHttp + MPAndroidChart
desktop/   JavaFX + H2 + OkHttp + JavaFX Charts
sql/       /sql/init.sql（云端 DDL + Demo）
docs/      架构、协议、API、AI、ER 等
```
