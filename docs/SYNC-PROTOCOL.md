# 同步协议与 LWW 合并

## 核心思想

每条可同步记录都带三个关键字段：

- `client_id`：全局唯一逻辑主键（UUID）。
- `last_modified`：最后修改时间（Unix epoch 毫秒）。
- `deleted`：软删除墓碑。

客户端按 `device_id` 记**两个**水位（手机端是 `sync_state` 表，桌面端是 H2 里同名的表）：

- `last_sync`：**pull** 水位，存服务端 `serverTime`（服务端时钟域）；
- `push_watermark`：**push** 水位，存本机 `last_modified` 的最大值（客户端时钟域）。

不能共用一个值：`last_modified` 由各客户端写，而 `since` 比的是服务端时间，
混在一起会在时钟偏差时漏记录。

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

## 两端如何使用水位

接口没变（`pull` 收 `since`，返回 `serverTime` / `hasMore` / `nextCursor`），两端
`SyncManager` 的实际行为是：

- **push**：只取 `last_modified > pushSince` 的记录（含软删除墓碑）。取数起点是
  `min(push_watermark, now - 1h)` —— 水位本身精确推进，但最近一小时的改动总会被重推，
  设备时钟往回跳也不会漏推。
- **pull**：`since = max(0, last_sync - 1h)`，然后用 `nextCursor` 循环到 `hasMore=false`。
  往回多看一小时是为了容忍服务端 `last_modified` 与 `serverTime` 的时钟差；LWW 合并幂等，
  多拉一点没有副作用。
- **水位落盘时机**：只在整轮（push 成功 + 分页拉完）之后写；中途失败保持旧水位。
  `last_sync` 取**第一页**的 `serverTime`（最早读到的值，最保守）。
- **首次同步**（水位为 0）：流水只拉最近 **3 个月**（`accounting.sync.pull-window-months`），
  字典类（分类 / 账户 / 模板 / 周期规则 / 预算）全量。
- **`pull-window` 仍然没有客户端调用**：更早的历史不会自动回补，换设备后只有最近 3 个月的流水。
  这是已知缺口，见 `docs/v1.4.0-plan.md` 第 6 点的说明。

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
