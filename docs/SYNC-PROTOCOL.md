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

## 增量拉取

- `pull` 返回 `last_modified > since` 的记录，按 `(last_modified, id)` 游标分页。
- 首次同步默认只拉最近 **3 个月**（`accounting.sync.pull-window-months`），避免全量。
- 更早历史通过 `pull-window` 分段回补（客户端按 3 个月一个窗口向过去滚动）。

## 流量与电量控制

- 只在有网络时触发同步；同步后台线程，不阻塞 UI。
- push 的流水按水位增量；字典（分类/账户/预算/模板）数据量小，可整体携带。
- 单次分页 `pull-page-size=500`。
- 写入本地成功后返回，网络失败不阻塞用户操作。

## 冲突边界

仅对「同一 `client_id` 的同一实体」做 LWW。不同实体之间不竞争。若两个端同时新增同名分类，会生成不同 `client_id`，均被保留（符合需求：不接受自动合并语义）。
