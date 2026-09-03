# REST API

默认前缀：`http://localhost:8080/api`。所有响应统一为：

```json
{ "success": true, "message": "", "data": { } }
```

## 健康检查

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/health` | 服务状态 |

## 同步

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/sync/push` | 客户端推送本地变更 |
| POST | `/api/sync/pull` | 增量拉取（`since` 水位） |
| POST | `/api/sync/pull-window` | 历史窗口回补 |

`pull` 请求体：
```json
{ "deviceId": "uuid", "since": 1750000000000, "cursor": "base64" }
```

## 记账

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/transactions` | 组合筛选（日期/分类/账户/金额/关键词/分页） |
| GET | `/api/transactions/{clientId}` | 单条 |
| POST | `/api/transactions` | 新增 |
| PUT | `/api/transactions/{clientId}` | 更新 |
| DELETE | `/api/transactions/{clientId}` | 软删除 |

筛选示例：
```
/api/transactions?dateFrom=2026-08-01&dateTo=2026-08-31&categoryClientId=cat-food&amountMin=10&keyword=早餐&page=0&size=20
```

## 分类 / 账户

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/POST | `/api/categories` | 列表 / 新增 |
| PUT/DELETE | `/api/categories/{clientId}` | 更新 / 软删除 |
| GET/POST | `/api/accounts` | 列表 / 新增 |
| PUT/DELETE | `/api/accounts/{clientId}` | 更新 / 软删除 |

## 预算

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/budgets` | upsert（唯一键 category+year+month） |
| DELETE | `/api/budgets/{clientId}` | 软删除 |

## 模板

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/POST | `/api/templates` | 列表 / 新增 |
| PUT/DELETE | `/api/templates/{clientId}` | 更新 / 软删除 |

## 报表

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/report/month?year=&month=` | 本月收入/支出/结余/环比 |
| GET | `/api/report/category-breakdown?year=&month=&type=EXPENSE` | 分类占比 |
| GET | `/api/report/trend?days=30` | 近30天趋势 |
| GET | `/api/report/budget-progress?year=&month=` | 预算进度（含预警级别） |
| GET | `/api/report/heatmap?year=` | 年度热力图 |
| GET | `/api/report/transactions?year=&month=` | 某月流水 |
| GET | `/api/report/category-transactions?year=&month=&categoryClientId=` | 分类下钻 |
| POST | `/api/report/ai` | AI 消费分析（规则/LLM） |

## 照片

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/photos` (multipart `file`) | 上传账单照片 |
