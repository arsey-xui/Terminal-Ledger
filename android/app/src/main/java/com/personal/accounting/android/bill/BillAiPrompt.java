package com.personal.accounting.android.bill;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Collection;

/**
 * 截图记账的多模态提示词与响应解析。
 *
 * <p>纯 Java，不依赖 Android 与网络，方便单独调提示词、也方便写测试。</p>
 *
 * <p>思路：把「账户 / 分类」两个候选列表放进用户消息，要求模型**只能从列表里原样选**，
 * 选不出就填 null。这样得到的是封闭集合里的选项，不会出现模型自己编一个分类名的情况；
 * 拿不准的字段一律 null，由界面保持原值让用户自己补。</p>
 *
 * <p>金额是最容易出错的地方（账单页上余额、优惠、立减、订单号都长得像金额），
 * 所以提示词里用「必须避免」单独一段把要排除的东西列了出来。</p>
 */
public final class BillAiPrompt {

    /** 系统提示：定义角色、输出格式、字段含义，以及要避免的误判。 */
    public static final String SYSTEM =
            "你是记账 App 的截图识别器：看图，提取一笔账的字段。\n"
            + "\n"
            + "【输出】只输出一个 JSON 对象，不要解释、不要 Markdown 代码块、不要多余文字：\n"
            + "{\"amount\":数字,\"type\":\"EXPENSE\"或\"INCOME\",\"account\":字符串或null,"
            + "\"category\":字符串或null,\"merchant\":字符串或null,\"date\":\"yyyy-MM-dd\"或null}\n"
            + "\n"
            + "【字段】\n"
            + "- amount：这笔账的实际金额，正数，不带货币符号。取「实付 / 付款金额 / 交易金额 / 订单金额 / 合计」\n"
            + "  这类明确写着消费金额的那个数。\n"
            + "- type：支出填 \"EXPENSE\"；退款、收款、别人转给你填 \"INCOME\"。\n"
            + "- account：付款方式，必须从用户给的「账户列表」里原样选一个；判断不出填 null。\n"
            + "- category：消费分类，必须从用户给的「分类列表」里原样选一个；判断不出填 null。\n"
            + "- merchant：商户名或对方名称，用于备注；没有填 null。\n"
            + "- date：交易日期；截图里没有或看不清填 null。\n"
            + "\n"
            + "【必须避免】\n"
            + "1. 不要把余额、零钱、优惠、折扣、立减、积分、手续费、额度、订单号、单号、时间、卡号尾号当成 amount。\n"
            + "2. 截图里通常有很多数字，只取「这次实际支付」的那一个；金额前带减号（-27.50）仍然填正数。\n"
            + "3. account 和 category 如果列表里没有合适的，就填 null，不要自己编一个名字。\n"
            + "4. 任何字段拿不准就填 null，不要猜。\n"
            + "\n"
            + "【示例】看到「星巴克 实付 ¥27.50」这类内容时输出：\n"
            + "{\"amount\":27.5,\"type\":\"EXPENSE\",\"account\":\"支付宝\","
            + "\"category\":\"餐饮\",\"merchant\":\"星巴克\",\"date\":null}";

    private BillAiPrompt() {
    }

    /** 用户消息里的文字部分：候选列表 + 任务说明；图片作为同一消息的另一段附在后面。 */
    public static String userText(Collection<String> accountNames, Collection<String> categoryNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("账户列表：").append(join(accountNames)).append('\n');
        sb.append("分类列表：").append(join(categoryNames)).append('\n');
        sb.append('\n');
        sb.append("请识别下面这张支付/账单截图，按系统提示的格式只输出 JSON。");
        return sb.toString();
    }

    private static String join(Collection<String> names) {
        if (names == null || names.isEmpty()) return "（用户还没有配置，account/category 请填 null）";
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            if (n == null || n.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append('、');
            sb.append(n.trim());
        }
        return sb.length() == 0 ? "（用户还没有配置，account/category 请填 null）" : sb.toString();
    }

    /** 解析结果：拿不到的字段为 null（amount 为 0 表示没识别到）。 */
    public static final class Result {
        public double amount;
        /** EXPENSE / INCOME。 */
        public String type;
        public String accountName;
        public String categoryName;
        public String merchant;
        public String date;

        public boolean hasAmount() {
            return amount > 0;
        }

        public boolean isIncome() {
            return "INCOME".equalsIgnoreCase(type);
        }
    }

    /** 解析模型返回的文本；容忍代码块包裹和前后多余文字，解析不出返回 null。 */
    public static Result parseResponse(String raw) {
        String json = firstJsonObject(raw);
        if (json == null) return null;
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            Result r = new Result();
            r.amount = readAmount(o.get("amount"));
            r.type = readString(o.get("type"));
            r.accountName = readString(o.get("account"));
            r.categoryName = readString(o.get("category"));
            r.merchant = readString(o.get("merchant"));
            r.date = readString(o.get("date"));
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 截出第一个完整的 JSON 对象。既处理 ```json 代码块，也处理模型在 JSON 前后
     * 多写了几句解释的情况（括号配对时跳过字符串里的括号与转义）。
     */
    static String firstJsonObject(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                if (inString) escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return raw.substring(start, i + 1);
            }
        }
        return null;
    }

    /** 金额可能是数字，也可能是 "¥27.50" / "27.50元" 这类字符串。 */
    private static double readAmount(JsonElement e) {
        if (e == null || e.isJsonNull()) return 0;
        String text;
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            return Math.abs(e.getAsDouble());
        }
        try {
            text = e.getAsString();
        } catch (Exception ex) {
            return 0;
        }
        if (text == null) return 0;
        String cleaned = text.replaceAll("[^0-9.\\-]", "");
        if (cleaned.isEmpty()) return 0;
        try {
            return Math.abs(Double.parseDouble(cleaned));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** 空串、"null"、"未知" 这类一律当归 null。 */
    private static String readString(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        String s;
        try {
            s = e.getAsString();
        } catch (Exception ex) {
            return null;
        }
        if (s == null) return null;
        String trimmed = s.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed) || "未知".equals(trimmed)) return null;
        return trimmed;
    }
}
