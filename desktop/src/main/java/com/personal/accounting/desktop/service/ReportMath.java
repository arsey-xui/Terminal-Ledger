package com.personal.accounting.desktop.service;

import com.personal.accounting.desktop.model.Transaction;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报表里的纯计算：把流水聚合成分类的支出合计，供饼图与预算进度共用。
 *
 * <p>抽出来是为了能测：控制器里那套依赖 JavaFX 与本地库，而「只算支出、按分类合并、收入不算」
 * 这条口径是纯逻辑，理应在这里被固化下来。</p>
 */
public final class ReportMath {

    private ReportMath() {
    }

    /**
     * 按分类合计支出金额。
     *
     * <p>只统计 {@code EXPENSE}；收入不计入分类占比。没有分类（{@code categoryClientId} 为空）的流水
     * 不计入 —— 饼图按分类展示，无法归类的那部分没法落到某个扇区。</p>
     *
     * @return 分类 clientId → 金额（元）
     */
    public static Map<String, BigDecimal> expenseByCategory(List<Transaction> txns) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        if (txns == null) return totals;
        for (Transaction t : txns) {
            if (t == null || !"EXPENSE".equals(t.type) || t.categoryClientId == null) continue;
            BigDecimal amount = t.amount == null ? BigDecimal.ZERO : t.amount;
            totals.merge(t.categoryClientId, amount, BigDecimal::add);
        }
        return totals;
    }

    /** 支出合计。 */
    public static BigDecimal totalOf(Map<String, BigDecimal> byCategory) {
        if (byCategory == null) return BigDecimal.ZERO;
        return byCategory.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
