# 同步协议与 LWW 合并

## 核心思想

每条可同步记录都带三个关键字段：

- `client_id`：全局唯一逻辑主键（UUID）。
- `last_modified`：最后修改时间（Unix epoch 毫秒）。
- `deleted`：软删除墓碑。

客户端以 `device_id` + `last_sync`（水位）记录同步位置。

## 接口

```
POST /api/sync/push          { deviceId, transactions[], categories[], accounts[], budgets[], templates[], recurringRules[] }
POST /api/sync/pull          { deviceId, since, until?, cursor? }  → { serverTime, hasMore, nextCursor, ... }
POST /api/sync/pull-window   { deviceId, since, until, cursor? }   → 历史窗口回补
```

## LWW 合并算法（服务端权威示例）

```java
Optional<Account> existing = accountRepository.findByClientId(incoming.clientId);
if (existing.isEmpty()) {
    accountRepository.save(incoming);          // 新记录：直接落库
} else if (incoming.lastModified > existing.get().lastModified) {
    copy(incoming, existing.get());            // incoming 更新 → 覆盖（LWW 胜出）
    accountRepository.save(existing.get());
} else {
    skip();                                    // 本地较新/相等当前 → 丢弃 incoming
}
```

客户端本地合并用的是**同一规则**（见 `SyncManager` 的 `upsertLww`）。

## 拉取（协议能力）与当前实现

协议侧：

- `pull` 返回 `last_modified > since` 的记录，按 `(last_modified, id)` 游标分页。
- 首次同步默认只拉最近 **3 个月**（`accounting.sync.pull-window-months`），避免全量。
- `pull-window`（`{deviceId, since, until, cursor?}`）用于向更早历史分段回补。

**但两端客户端目前都还没用上这套增量机制**：`SyncManager` 里 `pull.since` 恒为 `0`，
push 也送全量（`listModifiedSince(0)`，含删除墓碑）。因此实际行为是：

- push 全量本地数据（数据量大时由服务端做 LWW 合并）；
- pull 从水位 0 开始翻页，服务端因此只返回近 3 个月；
- `last_sync` 水位与 `pull-window` **只有服务端接口，客户端未调用**，更早历史暂不回补。

个人数据量下全量对账最不容易漏 —— 不依赖水位、也不依赖两端时钟是否一致；代价是流量随数据量线性增长。
数据变大后再切到水位增量即可，协议与服务端都已就绪。

## 时间字段的格式要求

同一条记录里有两个「时间」，格式**不一样**，容易踩：

| 字段 | 形态 | 说明 |
| --- | --- | --- |
| `lastModified` | JSON 数字（Unix epoch 毫秒） | LWW 合并依据，必须是数字，客户端之间靠它定胜负 |
| `createdAt` / `updatedAt` | ISO-8601 文本，如 `2026-09-05T12:00:49.311145` | 纯展示字段，不参与合并 |

**踩过的坑**：手机端 `DatabaseHelper` 写默认分类/账户时，把 `created_at` 存成了毫秒数字的**字符串**
（`"1790943328512"`）。后端按 `LocalDateTime` 解析这段文本直接抛 `DateTimeParseException`，
整次 push 返回 **500** —— 而默认分类/账户是每台设备都有的数据，所以那台设备**完全无法同步**。

两头都做了处理：

- **后端容错**（`common/LenientLocalDateTimeDeserializer`）：`createdAt` / `updatedAt` 同时接受
  ISO 文本、JSON 数字毫秒、以及数字字符串毫秒；空串与无法识别的值按 `null` 处理，绝不因为这两个
  展示字段让整次同步失败。旧的坏数据因此不用迁移也能同步。
- **手机端治本**（`data/Timestamps`）：写入一律用 ISO，读出历史坏值时顺手规范化，
  下一次落库自动修正。

## 流量与电量控制

- 触发时机：保存记账后自动一次（手机端记账页/小组件、电脑端记账页），以及手动点「立即同步」。
  **没有**网络状态监听，也**没有**后台定时同步。
- 同步在后台线程，不阻塞 UI。
- 单次分页 `pull-page-size=500`。
- 写入本地成功后即返回，网络失败不阻塞用户操作。

## 冲突边界

仅对「同一 `client_id` 的同一实体」做 LWW。不同实体之间不竞争。若两个端同时新增同名分类，会生成不同 `client_id`，均被保留（符合需求：不接受自动合并语义）。
