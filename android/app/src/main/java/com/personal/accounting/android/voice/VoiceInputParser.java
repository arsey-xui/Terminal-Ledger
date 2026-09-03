package com.personal.accounting.android.voice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语音记账解析器：把「午餐27元」「打车 15"] 这类口语解析成金额 + 分类建议。
 *
 * <p>失败时分类回退为「其他」（由调用方保证存在该分类）。</p>
 */
public final class VoiceInputParser {

    private static final Pattern AMOUNT = Pattern.compile(
            "(\\d+(?:\\.\\d{1,2})?)\\s*(元|块|块钱|¥|￥)");

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

    public static Parsed parse(String text) {
        if (text == null) return new Parsed(0, "其他");
        String normalized = text.trim();
        double amount = 0;
        Matcher m = AMOUNT.matcher(normalized);
        if (m.find()) {
            try {
                amount = Double.parseDouble(m.group(1));
            } catch (NumberFormatException ignore) {
                amount = 0;
            }
        }
        String category = guessCategory(normalized);
        return new Parsed(amount, category);
    }

    private static String guessCategory(String text) {
        for (Map.Entry<String, String[]> e : KEYWORDS.entrySet()) {
            for (String kw : e.getValue()) {
                if (text.contains(kw)) return e.getKey();
            }
        }
        return "其他";
    }

    public static class Parsed {
        public final double amount;
        public final String categoryName;

        public Parsed(double amount, String categoryName) {
            this.amount = amount;
            this.categoryName = categoryName;
        }
    }
}
