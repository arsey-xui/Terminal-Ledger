package com.personal.accounting.desktop.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.personal.accounting.desktop.data.DataManager;
import com.personal.accounting.desktop.model.Budget;
import com.personal.accounting.desktop.model.Transaction;
import com.personal.accounting.desktop.sync.Json;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 报表层自然语言分析：内置规则引擎，可替换为大模型占位接口。 */
public class AiReportGenerator {

    private final DataManager db;

    public AiReportGenerator(DataManager db) {
        this.db = db;
    }

    public String generate(int year, int month) {
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        LocalDate prevStart = start.minusMonths(1);
        LocalDate prevEnd = prevStart.withDayOfMonth(prevStart.lengthOfMonth());

        List<Transaction> cur = db.listTransactions(start, end, null, null);
        List<Transaction> prev = db.listTransactions(prevStart, prevEnd, null, null);
        BigDecimal curExpense = sum(cur, "EXPENSE");
        BigDecimal prevExpense = sum(prev, "EXPENSE");

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("本月总支出 %.2f 元。", curExpense));
        if (prevExpense.compareTo(BigDecimal.ZERO) > 0) {
            double pct = curExpense.subtract(prevExpense)
                    .multiply(BigDecimal.valueOf(100)).divide(prevExpense, 2, java.math.RoundingMode.HALF_UP).doubleValue();
            if (pct > 0) sb.append(String.format("较上月上涨 %.1f%%，注意控制。", pct));
            else if (pct < 0) sb.append(String.format("较上月下降 %.1f%%，节流有效。", Math.abs(pct)));
            else sb.append("与上月基本持平。");
        }

        // 分类 Top
        Map<String, BigDecimal> byCat = cur.stream()
                .filter(t -> "EXPENSE".equals(t.type) && t.categoryClientId != null)
                .collect(Collectors.groupingBy(t -> t.categoryClientId,
                        Collectors.reducing(BigDecimal.ZERO, t -> t.amount, BigDecimal::add)));
        byCat.entrySet().stream().sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .findFirst().ifPresent(e ->
                        sb.append(String.format("支出最多的是「%s」。", categoryName(e.getKey()))));

        // 预算预警
        for (Budget b : db.listBudgets(year, month)) {
            if (b.amountLimit == null || b.amountLimit.compareTo(BigDecimal.ZERO) <= 0) continue;
            BigDecimal used = byCat.getOrDefault(b.categoryClientId, BigDecimal.ZERO);
            double pct = used.multiply(BigDecimal.valueOf(100))
                    .divide(b.amountLimit, 0, java.math.RoundingMode.HALF_UP).doubleValue();
            if (pct >= 100) sb.append(String.format("【预算超支】%s（%.0f%%）。", categoryName(b.categoryClientId), pct));
            else if (pct >= 90) sb.append(String.format("【接近预算】%s（%.0f%%）。", categoryName(b.categoryClientId), pct));
        }
        String rule = sb.toString();
        if (AiConfig.canUseLlm()) {
            try {
                return llmAnalyze(rule);
            } catch (Exception e) {
                // 大模型调用失败：回退规则引擎
                return rule;
            }
        }
        return rule;
    }

    /** 用用户自填的 API Key 调用 OpenAI 兼容接口生成分析；失败时由调用方回退规则引擎。 */
    private String llmAnalyze(String rule) throws Exception {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("model", AiConfig.model());
        var msgs = root.putArray("messages");
        var userMsg = msgs.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", "你是消费分析助手。根据以下记账汇总，用中文给出简洁、有条理的消费分析结论。\n" + rule);
        Request req = new Request.Builder()
                .url(AiConfig.baseUrl() + "/chat/completions")
                .addHeader("Authorization", "Bearer " + AiConfig.apiKey())
                .post(RequestBody.create(Json.MAPPER.writeValueAsString(root),
                        MediaType.get("application/json; charset=utf-8")))
                .build();
        try (Response resp = client.newCall(req).execute()) {
            String body = resp.body().string();
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code() + ": " + body);
            JsonNode json = Json.MAPPER.readTree(body);
            String content = json.path("choices").path(0).path("message").path("content").asText("");
            return content.isEmpty() ? rule : content;
        }
    }

    private BigDecimal sum(List<Transaction> list, String type) {
        return list.stream().filter(t -> type.equals(t.type)).map(t -> t.amount == null ? BigDecimal.ZERO : t.amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String categoryName(String clientId) {
        var c = db.findCategory(clientId);
        return c == null ? "未分类" : c.name;
    }
}
