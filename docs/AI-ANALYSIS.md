# AI 消费分析接口设计

> 范围限定：**仅报表层**生成自然语言消费解读。**不**参与自动分类、**不**自动同步银行账单。

## 设计目标

- 离线时可用：内置**规则引擎**，无外部依赖。
- 配置后可用大模型增强：留出占位接口，可从 OpenAI 兼容接口切换到任意模型。

## 接口

```java
public interface AiReportService {
    AiReportResponse generate(AiReportRequest request);
}

public class AiReportRequest {
    Integer year;
    Integer month;
    String engine;   // RULE / LLM
    String prompt;   // 可选自定义提示
}

public class AiReportResponse {
    String report;
    String engine;   // RULE / LLM
    long generatedAt;
}
```

## 规则引擎（默认）

输入：本月支出/收入/结余、环比、分类 Top、预算预警。输出一句话/几句话的解读，例如：

> 本月总支出 1500.00 元，较上月上涨 15.0%，注意控制开销。支出最多的是「餐饮」。【接近预算】购物（92%）。

## 大模型占位接口

```java
public interface LlmClient {
    String complete(String system, String user);
}
```

- 配置 `AI_API_KEY`、`AI_MODEL`、`AI_BASE_URL` 后，`AiReportServiceImpl` 会把结构化报表数据拼成上下文，调用 `LlmClient`。
- 未配置或调用失败时，自动回退到规则引擎。

## 安全

- 只上传**汇总后的报表数据**（金额、分类聚合、预算比例），不上传全量流水明细。
- API Key 通过环境变量注入，`application.yml` 中为占位符。
