package com.personal.accounting.service.ai;

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
 * 日口径的支出统计：日平均、日支出标准差、日支出中位数。
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
    void multipleRecordsOnSameDayAreSummedIntoOneDay() {
        // 同一天三笔（10+20+30）算作当天的 60，而不是三个样本
        List<ExpenseStats.Entry> entries = Arrays.asList(
                new ExpenseStats.Entry(LocalDate.of(YEAR, MONTH, 1), new BigDecimal("10")),
                new ExpenseStats.Entry(LocalDate.of(YEAR, MONTH, 1), new BigDecimal("20")),
                new ExpenseStats.Entry(LocalDate.of(YEAR, MONTH, 1), new BigDecimal("30")),
                new ExpenseStats.Entry(LocalDate.of(YEAR, MONTH, 2), new BigDecimal("40")));
        ExpenseStats.Value v = ExpenseStats.ofDaily(YEAR, MONTH, entries);
        assertEquals(2, v.spentDays);
        assertEquals(DAYS, v.days);
        assertAmount("100.00", v.total);
        // 日平均 = 100 / 31，而不是 100 / 4 或 (10+20+30+40)/4
        assertAmount("3.23", v.average);
    }

    @Test
    void zeroSpendDaysParticipateInAverage() {
        ExpenseStats.Value v = ExpenseStats.ofDaily(YEAR, MONTH, days("100", "200"));
        // 总支出 300，分母是 31 天而不是有支出的 2 天
        assertAmount("9.68", v.average);
        assertEquals(DAYS, v.days);
        assertEquals(2, v.spentDays);
    }

    @Test
    void medianOfMostlyEmptyMonthIsZero() {
        // 31 天里只有 4 天花钱 → 排序后中位那天是 0
        ExpenseStats.Value v = ExpenseStats.ofDaily(YEAR, MONTH,
                days("100", null, "200", null, "300", null, null, null, null, null,
                        "400", null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null));
        assertAmount("0", v.median);
        assertEquals(4, v.spentDays);
        // 中位数为 0 走的是「多数日子没支出」那一支，不能下「被大额拉高」的结论
        String text = ExpenseStats.describeDaily(v, "2020年1月");
        assertTrue(text.contains("半数以上的日子没有支出"), text);
        assertFalse(text.contains("把平均值拉高"), text);
    }

    @Test
    void sampleStdDevUsesNMinusOneOverAllDays() {
        ExpenseStats.Value v = ExpenseStats.ofDaily(YEAR, MONTH, days("10", null, "20", null, "30", null, "60"));
        // 总支出 120，日均 120/31 = 3.87；样本方差用 31 天（含没花钱的 0）算
        assertAmount("3.87", v.average);
        assertAmount("12.30", v.stdDev);
    }

    @Test
    void identicalEveryDayGivesZeroStdDev() {
        // 31 天每天各 10 元 → 标准差 0
        List<ExpenseStats.Entry> everyDay = new ArrayList<>();
        for (int d = 1; d <= DAYS; d++) {
            everyDay.add(new ExpenseStats.Entry(LocalDate.of(YEAR, MONTH, d), new BigDecimal("10")));
        }
        ExpenseStats.Value v = ExpenseStats.ofDaily(YEAR, MONTH, everyDay);
        assertAmount("10.00", v.average);
        assertAmount("10.00", v.median);
        assertAmount("0", v.stdDev);
        assertTrue(ExpenseStats.describeDaily(v, "2020年1月").contains("比较均匀"));
    }

    @Test
    void ignoresNonPositiveAmountsAndOutOfRangeDates() {
        List<ExpenseStats.Entry> entries = Arrays.asList(
                new ExpenseStats.Entry(LocalDate.of(YEAR, MONTH, 1), new BigDecimal("100")),
                new ExpenseStats.Entry(LocalDate.of(YEAR, MONTH, 2), BigDecimal.ZERO),
                new ExpenseStats.Entry(LocalDate.of(YEAR, MONTH, 3), new BigDecimal("-5")),
                new ExpenseStats.Entry(LocalDate.of(YEAR, MONTH, 4), null),
                new ExpenseStats.Entry(null, new BigDecimal("50")),
                // 同月之外：不该计入
                new ExpenseStats.Entry(LocalDate.of(2019, 12, 31), new BigDecimal("999")),
                new ExpenseStats.Entry(LocalDate.of(YEAR, MONTH, 31).plusDays(1), new BigDecimal("999")));
        ExpenseStats.Value v = ExpenseStats.ofDaily(YEAR, MONTH, entries);
        assertAmount("100.00", v.total);
        assertEquals(1, v.spentDays);
    }

    @Test
    void currentMonthCountsDaysUpToToday() {
        LocalDate now = LocalDate.now();
        List<ExpenseStats.Entry> entries = new ArrayList<>();
        entries.add(new ExpenseStats.Entry(now.withDayOfMonth(1), new BigDecimal("50")));
        ExpenseStats.Value v = ExpenseStats.ofDaily(now.getYear(), now.getMonthValue(), entries);
        assertEquals(now.getDayOfMonth(), v.days);
        assertTrue(ExpenseStats.describeDaily(v, "本月").startsWith("本月 " + now.getDayOfMonth() + " 天共支出"));
    }

    @Test
    void futureMonthCountsSingleDayInsteadOfZero() {
        LocalDate future = LocalDate.now().plusMonths(2);
        ExpenseStats.Value v = ExpenseStats.ofDaily(future.getYear(), future.getMonthValue(), new ArrayList<>());
        assertEquals(1, v.days);   // 不会出现除零
        assertTrue(v.empty);
    }

    @Test
    void describeNamesThePeriodSoPastMonthsAreNotCalledThisMonth() {
        ExpenseStats.Value v = ExpenseStats.ofDaily(YEAR, MONTH, days("100", "200"));
        assertTrue(ExpenseStats.describeDaily(v, "2020年1月").startsWith("2020年1月 31 天共支出"));
    }
}
