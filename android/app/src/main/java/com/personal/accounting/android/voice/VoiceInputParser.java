package com.personal.accounting.android.voice;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语音记账解析器。输入格式固定为「账户 + 分类 + 金额」，三段顺序随意，例如：
 *
 * <ul>
 *   <li>{@code 支付宝午餐27元} → 账户「支付宝」+ 分类「餐饮」（由关键词「午餐」推出）+ 金额 27，
 *       备注留「午餐」</li>
 *   <li>{@code 微信打车15块} → 账户「微信」+ 分类「交通」+ 金额 15，备注留「打车」</li>
 * </ul>
 *
 * <p>账户名和分类名都不是写死的：调用方把本机已有的名字传进来，只有真正存在的那一个才会被选中，
 * 所以用户自建的账户（如「招行卡」）和分类（如「宠物」）同样能识别。</p>
 *
 * <p>分类的判定顺序：先在本机分类名里找（说的是分类本身），再按 {@link #KEYWORDS} 关键词表
 * 推出分类名（说的是「午餐」这类口语词），都没有就回退「其他」。</p>
 *
 * <p>备注是「摘掉账户名和金额之后剩下的部分」，所以「支付宝午餐27元」的备注就是「午餐」，
 * 不会把账户和金额再重复写一遍。剩下的只有标点/空白时备注为空串。</p>
 *
 * <p>金额的识别顺序见 {@link #findAmount}：带单位或货币符号的写法最稳妥且位置随意
 * （{@code 27元} / {@code 27块} / {@code ¥27}）；漏说单位时兜底认整句末尾的裸数字
 * （{@code 支付宝午餐27}），但中间的裸数字不算，避免把「3人聚餐」的 3 当成金额。</p>
 */
public final class VoiceInputParser {

    /** 金额：货币符号在前（¥27），或数字在前带单位（27元 / 27块 / 27块钱）。 */
    private static final Pattern AMOUNT = Pattern.compile(
            "[¥￥]\\s*(\\d+(?:\\.\\d{1,2})?)"
                    + "|(\\d+(?:\\.\\d{1,2})?)\\s*(?:元|块|块钱|¥|￥)");

    /** 兜底：整句末尾的裸数字（漏说「元」时用），后面允许跟标点或空白。 */
    private static final Pattern TRAILING_AMOUNT = Pattern.compile(
            "(\\d+(?:\\.\\d{1,2})?)\\s*[。.，,、!！?？~～;；:：\\s]*$");

    /** 摘掉账户和金额后残留在首尾的连接符与语气符号，如「，」「·」「-」「!」。 */
    private static final Pattern EDGE_PUNCTUATION = Pattern.compile(
            "^[\\s,，、.。·\\-—:：;；!！?？~～]+|[\\s,，、.。·\\-—:：;；!！?？~～]+$");

    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** 单个字的名字（如用户把分类改叫「吃」）不参与直接匹配，避免误命中。 */
    private static final int MIN_NAME_LENGTH = 2;

    private static final Map<String, String[]> KEYWORDS = new LinkedHashMap<>();
    static {
        KEYWORDS.put("餐饮", new String[]{"早","午","晚","餐","饭","吃","火锅","烧烤","奶茶","咖啡","外卖","夜宵","早餐","午餐","晚餐"});
        KEYWORDS.put("交通", new String[]{"地铁","公交","出租","打车","滴滴","高铁","火车","飞机","加油","停车","共享"});
        KEYWORDS.put("购物", new String[]{"买","购","淘宝","京东","拼多多","超市","衣服","鞋","家电","超市"});
        KEYWORDS.put("住房", new String[]{"房租","房贷","水电","燃气","物业","维修"});
        KEYWORDS.put("通讯", new String[]{"话费","流量","宽带"});
        KEYWORDS.put("医疗", new String[]{"药","医院","挂号","体检","看病"});
        KEYWORDS.put("教育", new String[]{"课","书","培训","学费","网课"});
        KEYWORDS.put("娱乐", new String[]{"电影","游戏","KTV","演出","门票","旅游","游乐"});
        KEYWORDS.put("人情", new String[]{"红包","随礼","请客","礼物","请"});
    }

    private VoiceInputParser() {
    }

    /** 只解析金额、分类与备注，不做账户识别（本机账户名未知时用）。 */
    public static Parsed parse(String text) {
        return parse(text, null, null);
    }

    /**
     * @param accountNames  本机已有的账户名，用于识别「账户」段；传 null 表示不识别账户
     * @param categoryNames 本机已有的分类名，用于识别「分类」段；传 null 表示只用关键词表
     */
    public static Parsed parse(String text, Collection<String> accountNames,
                               Collection<String> categoryNames) {
        if (text == null || text.trim().isEmpty()) {
            return new Parsed(0, "其他", null, "");
        }
        String normalized = text.trim();

        // 先摘金额：剩下的文本继续用来摘账户名，最后由剩下的部分得到备注
        double amount = 0;
        String rest = normalized;
        AmountMatch match = findAmount(normalized);
        if (match != null) {
            amount = match.value;
            rest = normalized.substring(0, match.start) + normalized.substring(match.end);
        }

        String account = firstContained(normalized, accountNames);
        String note = cleanNote(removeFirst(rest, account));
        return new Parsed(amount, guessCategory(normalized, categoryNames), account, note);
    }

    /**
     * 找金额。先认带单位/货币符号的写法（位置随意）；都没命中时再兜底认整句末尾的裸数字
     * —— 用户常漏说「元」。兜底只认末尾，所以「3人聚餐」「买了2件」里的数字不会被当成金额。
     *
     * @return 命中的金额及其在原句中的区间；没命中返回 null
     */
    private static AmountMatch findAmount(String text) {
        Matcher withUnit = AMOUNT.matcher(text);
        if (withUnit.find()) {
            String digits = withUnit.group(1) != null ? withUnit.group(1) : withUnit.group(2);
            return new AmountMatch(toDouble(digits), withUnit.start(), withUnit.end());
        }
        Matcher bare = TRAILING_AMOUNT.matcher(text);
        if (bare.find()) {
            // 只把数字本身算作金额，末尾的标点留给 cleanNote 收尾
            return new AmountMatch(toDouble(bare.group(1)), bare.start(1), bare.end(1));
        }
        return null;
    }

    /** 命中的金额：数值 + 它在原句里的区间（用来从备注里摘掉这一段）。 */
    private static final class AmountMatch {
        final double value;
        final int start;
        final int end;

        AmountMatch(double value, int start, int end) {
            this.value = value;
            this.start = start;
            this.end = end;
        }
    }

    private static double toDouble(String digits) {
        try {
            return Double.parseDouble(digits);
        } catch (NumberFormatException ignore) {
            return 0;
        }
    }

    private static String guessCategory(String text, Collection<String> categoryNames) {
        String byName = firstContained(text, categoryNames);
        if (byName != null) return byName;
        for (Map.Entry<String, String[]> e : KEYWORDS.entrySet()) {
            for (String kw : e.getValue()) {
                if (text.contains(kw)) return e.getKey();
            }
        }
        return "其他";
    }

    /** 摘掉第一处出现的账户名。 */
    private static String removeFirst(String text, String token) {
        if (token == null) return text;
        int at = text.indexOf(token);
        if (at < 0) return text;
        return text.substring(0, at) + text.substring(at + token.length());
    }

    /** 收尾：压掉多余空白、去掉摘完两段后留在首尾的连接符。 */
    private static String cleanNote(String text) {
        String s = SPACES.matcher(text).replaceAll(" ").trim();
        s = EDGE_PUNCTUATION.matcher(s).replaceAll("");
        return s.trim();
    }

    /**
     * 在文本里找出现的名字。按名字从长到短匹配，避免短名字抢先命中
     * —— 同时有「微信」和「微信零钱」时，应该选中更具体的那个。
     */
    private static String firstContained(String text, Collection<String> names) {
        if (names == null || names.isEmpty()) return null;
        List<String> candidates = new ArrayList<>();
        for (String name : names) {
            if (name == null) continue;
            String trimmed = name.trim();
            if (trimmed.length() >= MIN_NAME_LENGTH) candidates.add(trimmed);
        }
        candidates.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String candidate : candidates) {
            if (text.contains(candidate)) return candidate;
        }
        return null;
    }

    /** 解析结果：金额 + 分类名 + 账户名 + 备注。 */
    public static class Parsed {
        /** 识别到的金额；0 表示没识别到。 */
        public final double amount;
        /** 识别到的分类名；识别不到时是「其他」，不会是 null。 */
        public final String categoryName;
        /** 识别到的账户名；没提到账户时为 null（调用方应保持当前所选账户不变）。 */
        public final String accountName;
        /** 摘掉账户名和金额后剩下的部分，作为备注；可能为空串，不会是 null。 */
        public final String note;

        public Parsed(double amount, String categoryName, String accountName, String note) {
            this.amount = amount;
            this.categoryName = categoryName;
            this.accountName = accountName;
            this.note = note;
        }
    }
}
