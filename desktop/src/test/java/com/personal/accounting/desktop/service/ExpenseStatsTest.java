package com.personal.accounting.desktop.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日口径的支出统计，与后端 ExpenseStats 保持同一套结论。
 *
 * <p>用已结束的月份（2020-01，31 天）做样本，整个月都参与统计，数字与「今天是几号」无关。</p>
 */
class ExpenseStatsTest {

    private static final int YEAR = 2020;
    private static final int MONTH = 1;
    private static final int DAYS = 31;

    /** 按天给金额：第 i 个元素是当月第 i+1 天的支出，null 表示当天没花钱。 */
    private static List<ExpenseStats.Entry> days(String... amountsPerDay) {
        List<ExpenseStats.Entry> list = new ArrayList<>();
        for (int i = 0; i < amountsPerDay.length; i++) {
            if (amountsPerDay[i] == null) continue;
            list.add(new ExpenseStats.Entry(LocalDate.of(YEAR, MONTH, i + 1), new BigDecimal(amountsPerDay[i])));
        }
        return list;
    }

    /** 只比数值，不比 scale（100 与 100.00 视为相等）。 */
    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "期望 " + expected + "，实际 " + actual);
    }

    @Test
    void emptyMonthHasNoStats() {
        ExpenseStats.Value v = ExpenseStats.ofDaily(YEAR, MONTH, new ArrayList<>());
        assertTrue(v.empty);
        assertEquals(DAYS, v.days);
        assertTrue(ExpenseStats.describeDaily(v, "2020年1月").contains("还没有支出记录"));
    }

    @Test
    void zeroSpendDaysParticipateInAverage() {
        // 300 / 31 天（不是 / 2 天）
        ExpenseStats.Value v = ExpenseStats.ofDaily(YEAR, MONTH, days("100", "200"));
        assertAmount("9.68", v.average);
        assertEquals(DAYS, v.days);
        assertEquals(2, v.spentDays);
    }

    @Test
    void multipleRecordsOnSameDayAreSummedIntoOneDay() {
        List<ExpenseStats.Entry> entries = Arrays.asList(
                new ExpenseStats.Entry(LocalDate.of(YEAR, MONTH, 1), new BigDecimal("10")),
                new ExpenseStats.Entry(LocalDate.of(YEAR, MONTH, 1), new BigDecimal("20")),
                new ExpenseStats.Entry(LocalDate.of(YEAR, MONTH, 1), new BigDecimal("30")),
                new ExpenseStats.Entry(LocalDate.of(YEAR, MONTH, 2), new BigDecimal("40")));
        ExpenseStats.Value v = ExpenseStats.ofDaily(YEAR, MONTH, entries);
        assertEquals(2, v.spentDays);
        assertAmount("3.23", v.average);   // 100 / 31
    }

    @Test
    void sampleStdDevMatchesBackend() {
        ExpenseStats.Value v = ExpenseStats.ofDaily(YEAR, MONTH, days("10", null, "20", null, "30", null, "60"));
        assertAmount("3.87", v.average);
        assertAmount("12.30", v.stdDev);
    }

    @Test
    void mostlyEmptyMonthHasZeroMedianAndSaysSo() {
        ExpenseStats.Value v = ExpenseStats.ofDaily(YEAR, MONTH, days("100", null, null, "200"));
        assertAmount("0", v.median);
        assertFalse(ExpenseStats.describeDaily(v, "2020年1月").contains("把平均值拉高"));
    }

    @Test
    void describeMentionsAllThreeNumbers() {
        String text = ExpenseStats.describeDaily(ExpenseStats.ofDaily(YEAR, MONTH, days("100", "200")), "2020年1月");
        assertTrue(text.contains("日平均支出 9.68 元"), text);
        assertTrue(text.contains("日支出标准差"), text);
        assertTrue(text.contains("日支出中位数"), text);
    }
}
