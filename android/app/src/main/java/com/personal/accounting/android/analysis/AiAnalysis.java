package com.personal.accounting.android.analysis;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.personal.accounting.android.data.BudgetRepo;
import com.personal.accounting.android.data.CategoryRepo;
import com.personal.accounting.android.data.TransactionRepo;
import com.personal.accounting.android.model.Budget;
import com.personal.accounting.android.model.Category;
import com.personal.accounting.android.model.Transaction;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 手机端消费分析：默认用内置规则引擎（纯本地、离线可用、与电脑端 {@code AiReportGenerator} 口径一致）；
 * 用户自填 API Key 时改用 OpenAI 兼容大模型增强，调用失败自动回退规则引擎。
 */
public final class AiAnalysis {

    public static final String ENGINE_LOCAL = "LOCAL";
    public static final String ENGINE_LLM = "LLM";

    private static final String PREFS = "ai_prefs";
    private static final String K_DONE = "ai_done";
    private static final String K_USE_LLM = "ai_use_llm";
    private static final String K_KEY = "ai_api_key";
    private static final String K_BASE = "ai_base_url";
    private static final String K_MODEL = "ai_model";
    private static final String DEFAULT_BASE = "https://api.openai.com/v1";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private AiAnalysis() {
    }

    /** 分析结果：text 是要展示的正文，engine 说明实际生效的引擎，note 记录降级原因（可为空）。 */
    public static final class Result {
        public final String text;
        public final String engine;
        public final String note;

        Result(String text, String engine, String note) {
            this.text = text;
            this.engine = engine;
            this.note = note;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- 设置项 ----------

    /** 是否已完成 AI 首次设置向导。 */
    public static boolean isConfigured(Context context) {
        return prefs(context).getBoolean(K_DONE, false);
    }

    public static void setConfigured(Context context, boolean done) {
        prefs(context).edit().putBoolean(K_DONE, done).apply();
    }

    public static boolean useLlm(Context context) {
        return prefs(context).getBoolean(K_USE_LLM, false);
    }

    public static String apiKey(Context context) {
        return prefs(context).getString(K_KEY, "");
    }

    public static String baseUrl(Context context) {
        return prefs(context).getString(K_BASE, DEFAULT_BASE);
    }

    public static String model(Context context) {
        return prefs(context).getString(K_MODEL, DEFAULT_MODEL);
    }

    /** 是否具备真正调用大模型的条件（用户选了 LLM 且填了 Key）。 */
    public static boolean canUseLlm(Context context) {
        return useLlm(context) && !apiKey(context).trim().isEmpty();
    }

    /** 保存用户配置；未填 Key 时自动退回规则引擎。 */
    public static void save(Context context, String apiKey, String baseUrl, String model) {
        String key = apiKey == null ? "" : apiKey.trim();
        boolean llm = !key.isEmpty();
        prefs(context).edit()
                .putBoolean(K_DONE, true)
                .putBoolean(K_USE_LLM, llm)
                .putString(K_KEY, key)
                .putString(K_BASE, blankToDefault(baseUrl, DEFAULT_BASE))
                .putString(K_MODEL, blankToDefault(model, DEFAULT_MODEL))
                .apply();
    }

    /** 明确选择「只用规则引擎」。 */
    public static void useRulesOnly(Context context) {
        prefs(context).edit().putBoolean(K_DONE, true).putBoolean(K_USE_LLM, false).apply();
    }

    /** 切回本地规则引擎：关闭大模型并清除已保存的 API Key（接口地址与模型保留，便于以后再次启用）。 */
    public static void clearLlm(Context context) {
        prefs(context).edit()
                .putBoolean(K_DONE, true)
                .putBoolean(K_USE_LLM, false)
                .remove(K_KEY)
                .apply();
    }

    private static String blankToDefault(String value, String fallback) {
        return (value == null || value.trim().isEmpty()) ? fallback : value.trim();
    }

    // ---------- 分析 ----------

    /** 生成当月分析：能用大模型就用，不可用或失败时回退本地规则引擎。 */
    public static Result analyze(Context context, int year, int month) {
        String rules = LocalAnalyzer.generate(context, year, month);
        if (!canUseLlm(context)) {
            return new Result(rules, ENGINE_LOCAL, null);
        }
        try {
            String text = LlmClient.complete(
                    "你是一位个人理财助手。请基于下面的记账汇总数据，用中文写一段简洁、有条理的消费分析，"
                            + "指出值得关注的点并给出可执行建议，不超过 200 字。",
                    rules, apiKey(context), baseUrl(context), model(context));
            if (text == null || text.trim().isEmpty()) {
                return new Result(rules, ENGINE_LOCAL, "大模型返回为空，已改用本地规则引擎");
            }
            return new Result(text.trim(), ENGINE_LLM, null);
        } catch (Exception e) {
            return new Result(rules, ENGINE_LOCAL, "大模型调用失败（" + e.getMessage() + "），已改用本地规则引擎");
        }
    }

    /** 内置规则引擎：只读本地库，离线可用。口径与电脑端 AiReportGenerator 对齐。 */
    static final class LocalAnalyzer {

        private LocalAnalyzer() {
        }

        static String generate(Context context, int year, int month) {
            TransactionRepo txRepo = new TransactionRepo(context);
            BudgetRepo budgetRepo = new BudgetRepo(context);
            CategoryRepo catRepo = new CategoryRepo(context);

            LocalDate start = LocalDate.of(year, month, 1);
            LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
            LocalDate prevStart = start.minusMonths(1);
            LocalDate prevEnd = prevStart.withDayOfMonth(prevStart.lengthOfMonth());

            List<Transaction> cur = txRepo.search(start, end, null, null, null);
            List<Transaction> prev = txRepo.search(prevStart, prevEnd, null, null, null);

            double curExpense = sum(cur, "EXPENSE");
            double curIncome = sum(cur, "INCOME");
            double prevExpense = sum(prev, "EXPENSE");
            double balance = curIncome - curExpense;

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("【%d年%d月 消费分析】", year, month));
            sb.append(String.format("本月总支出 %.2f 元，总收入 %.2f 元，结余 %.2f 元。",
                    curExpense, curIncome, balance));

            if (prevExpense > 0) {
                double pct = (curExpense - prevExpense) / prevExpense * 100;
                if (pct > 0) {
                    sb.append(String.format("支出较上月上涨 %.1f%%，注意控制开销。", pct));
                } else if (pct < 0) {
                    sb.append(String.format("支出较上月下降 %.1f%%，节流有成效。", Math.abs(pct)));
                } else {
                    sb.append("支出与上月基本持平。");
                }
            } else if (curExpense > 0) {
                sb.append("上月无可比支出数据。");
            }

            Map<String, Double> byCategory = expenseByCategory(cur);
            if (byCategory.isEmpty()) {
                sb.append("本月还没有支出记录。");
            } else {
                Map.Entry<String, Double> top = Collections.max(
                        byCategory.entrySet(), (a, b) -> Double.compare(a.getValue(), b.getValue()));
                double share = curExpense > 0 ? top.getValue() / curExpense * 100 : 0;
                sb.append(String.format("支出最多的是「%s」%.2f 元，占本月支出 %.0f%%，可重点关注。",
                        displayName(catRepo, top.getKey()), top.getValue(), share));
            }

            boolean anyBudget = false;
            for (Budget b : budgetRepo.listForMonth(year, month)) {
                if (b.amountLimit <= 0) continue;
                anyBudget = true;
                double used = byCategory.containsKey(b.categoryClientId)
                        ? byCategory.get(b.categoryClientId) : 0;
                int pct = (int) Math.round(used / b.amountLimit * 100);
                String name = displayName(catRepo, b.categoryClientId);
                if (pct >= 100) {
                    sb.append(String.format("【预算超支】%s 已用 %d%%。", name, pct));
                } else if (pct >= 90) {
                    sb.append(String.format("【接近预算】%s 已用 %d%%。", name, pct));
                }
            }
            if (!anyBudget) {
                sb.append("尚未设置分类预算。");
            }
            return sb.toString();
        }

        private static Map<String, Double> expenseByCategory(List<Transaction> txns) {
            Map<String, Double> map = new HashMap<>();
            for (Transaction t : txns) {
                if (!"EXPENSE".equals(t.type) || t.categoryClientId == null) continue;
                Double old = map.get(t.categoryClientId);
                map.put(t.categoryClientId, (old == null ? 0 : old) + t.amount);
            }
            List<Map.Entry<String, Double>> sorted = new ArrayList<>(map.entrySet());
            sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            Map<String, Double> ordered = new LinkedHashMap<>();
            for (Map.Entry<String, Double> e : sorted) ordered.put(e.getKey(), e.getValue());
            return ordered;
        }

        private static double sum(List<Transaction> list, String type) {
            double total = 0;
            for (Transaction t : list) {
                if (type.equals(t.type)) total += t.amount;
            }
            return total;
        }

        private static String displayName(CategoryRepo catRepo, String clientId) {
            Category c = catRepo.findActive(clientId);
            if (c == null) return "未分类";
            return (c.icon == null || c.icon.isEmpty()) ? c.name : c.icon + " " + c.name;
        }
    }

    /** OpenAI 兼容的对话补全调用（用户自填 Key），失败抛出异常由调用方回退规则引擎。 */
    static final class LlmClient {

        private LlmClient() {
        }

        static String complete(String systemPrompt, String data, String apiKey,
                               String baseUrl, String model) throws Exception {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build();

            JsonObject root = new JsonObject();
            root.addProperty("model", model);
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content", systemPrompt);
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", data);
            JsonArray messages = new JsonArray();
            messages.add(system);
            messages.add(user);
            root.add("messages", messages);

            String url = trimTrailingSlash(baseUrl) + "/chat/completions";
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(root.toString(), JSON))
                    .build();
            try (Response resp = client.newCall(request).execute()) {
                String body = resp.body() == null ? "" : resp.body().string();
                if (!resp.isSuccessful()) {
                    throw new java.io.IOException("HTTP " + resp.code());
                }
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                JsonArray choices = json.getAsJsonArray("choices");
                if (choices == null || choices.size() == 0) return "";
                JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                if (message == null || !message.has("content")) return "";
                return message.get("content").getAsString();
            }
        }

        private static String trimTrailingSlash(String url) {
            String u = url == null ? DEFAULT_BASE : url.trim();
            while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            return u;
        }
    }
}
