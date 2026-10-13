package com.personal.accounting.desktop.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本月<b>按天</b>的三个描述性统计：日平均支出、日支出标准差、日支出中位数。
 *
 * <p>先把逐笔流水按日期汇总成「每天花了多少」，再对每日金额算统计量 —— 所以这里的
 * 「平均」是日平均（总支出 ÷ 统计天数），不是每笔的平均。两者差别很大：
 * 一天买三次菜在按笔口径里是三个样本，在按天口径里只是当天的一笔汇总。</p>
 *
 * <p>分母口径：从 1 号到「今天」的每一天都算一天，<b>没花钱的日子按 0 参与</b>。
 * 这是记账里的常规读法（「这个月平均每天花多少」），也让月末和月初可比；
 * 若只统计有支出的日子，日平均会因为漏掉了没花钱的日子而系统性偏高。
 * 查看已结束的月份时，「今天」取当月末，即整个月都参与。</p>
 *
 * <p>标准差用 <b>n-1</b>（样本标准差），衡量「每天的花费波动多大」；只有一天时无定义，
 * 返回 0，由调用方决定是否展示。</p>
 *
 * <p>与后端 {@code com.personal.accounting.service.ai.ExpenseStats}、手机端同名类口径一致，
 * 格式化文案也统一由 {@link #describeDaily} 产出，保证同一个月的数字在哪一端看都一样。</p>
 */
public final class ExpenseStats {

    private ExpenseStats() {
    }

    /** 统计结果；empty 为 true 时表示统计区间内没有任何支出。 */
    public static final class Value {
        /** 统计天数（从 1 号到今天，含没花钱的日子） */
        public final int days;
        /** 日平均支出 = 总支出 / 统计天数 */
        public final BigDecimal average;
        /** 区间内支出总额 */
        public final BigDecimal total;
        /** 日支出样本标准差（n-1）；天数 < 2 时为 0 */
        public final BigDecimal stdDev;
        /** 日支出中位数；偶数天取中间两天的平均 */
        public final BigDecimal median;
        /** 有支出的天数（用于说明「其中 N 天没有支出」） */
        public final int spentDays;
        /** 是否整段区间都没有支出 */
        public final boolean empty;

        Value(int days, BigDecimal average, BigDecimal total, BigDecimal stdDev, BigDecimal median,
              int spentDays, boolean empty) {
            this.days = days;
            this.average = average;
            this.total = total;
            this.stdDev = stdDev;
            this.median = median;
            this.spentDays = spentDays;
            this.empty = empty;
        }
    }

    /**
     * 按天计算统计值。
     *
     * @param year    年
     * @param month   月（1-12）
     * @param entries 逐笔支出：日期 → 金额（元）。同一天会有多条；null 日期或非正金额会被忽略
     */
    public static Value ofDaily(int year, int month, List<Entry> entries) {
        LocalDate today = LocalDate.now();
        LocalDate first = LocalDate.of(year, month, 1);
        // 已结束的月份按整月算；当月按「到今天为止」算
        LocalDate last = first.withDayOfMonth(first.lengthOfMonth());
        LocalDate end = last.isAfter(today) ? today : last;
        if (end.isBefore(first)) {
            end = first;   // 未来月份：至少一天，避免除零
        }

        Map<LocalDate, BigDecimal> perDay = new LinkedHashMap<>();
        if (entries != null) {
            for (Entry e : entries) {
                if (e == null || e.date == null || e.amount == null) continue;
                if (e.amount.compareTo(BigDecimal.ZERO) <= 0) continue;
                if (e.date.isBefore(first) || e.date.isAfter(end)) continue;
                perDay.merge(e.date, e.amount, BigDecimal::add);
            }
        }

        int days = (int) (end.toEpochDay() - first.toEpochDay()) + 1;
        List<BigDecimal> daily = new ArrayList<>(days);
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < days; i++) {
            BigDecimal amount = perDay.get(first.plusDays(i));
            BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
            daily.add(value);
            total = total.add(value);
        }
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return new Value(days, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, true);
        }

        BigDecimal average = total.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
        List<BigDecimal> sorted = new ArrayList<>(daily);
        sorted.sort(BigDecimal::compareTo);
        BigDecimal median = median(sorted);
        BigDecimal stdDev = days < 2 ? BigDecimal.ZERO : sampleStdDev(daily, average);
        int spentDays = perDay.size();

        return new Value(days, average, total, stdDev, median, spentDays, false);
    }

    /** 一笔支出（只用到日期与金额）。 */
    public static final class Entry {
        public final LocalDate date;
        public final BigDecimal amount;

        public Entry(LocalDate date, BigDecimal amount) {
            this.date = date;
            this.amount = amount;
        }
    }

    /** 中位数：已排序列表取中间；偶数天取中间两天的均值。 */
    private static BigDecimal median(List<BigDecimal> sorted) {
        int n = sorted.size();
        int mid = n / 2;
        if (n % 2 == 1) {
            return sorted.get(mid);
        }
        return sorted.get(mid - 1).add(sorted.get(mid))
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    /** 样本标准差（n-1），保留 2 位。 */
    private static BigDecimal sampleStdDev(List<BigDecimal> daily, BigDecimal average) {
        BigDecimal sumSq = BigDecimal.ZERO;
        for (BigDecimal amount : daily) {
            BigDecimal diff = amount.subtract(average);
            sumSq = sumSq.add(diff.multiply(diff));
        }
        BigDecimal variance = sumSq.divide(BigDecimal.valueOf(daily.size() - 1L), MathContext.DECIMAL64);
        return sqrt(variance).setScale(2, RoundingMode.HALF_UP);
    }

    /** 牛顿迭代求平方根（BigDecimal 没有内置 sqrt，避免为此引入第三方数学库）。 */
    private static BigDecimal sqrt(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal x = BigDecimal.valueOf(Math.sqrt(value.doubleValue()));
        if (x.compareTo(BigDecimal.ZERO) == 0) {
            x = BigDecimal.ONE;
        }
        for (int i = 0; i < 6; i++) {
            x = x.add(value.divide(x, MathContext.DECIMAL64)).divide(BigDecimal.valueOf(2), MathContext.DECIMAL64);
        }
        return x;
    }

    /**
     * 统一文案：规则引擎写进正文、大模型分析作为上下文，都用这一份。
     *
     * @param v      {@link #ofDaily} 的结果
     * @param period 区间说法，如「本月」「2026年9月」；已结束的月份不要把当月说成「本月」
     */
    public static String describeDaily(Value v, String period) {
        if (v == null || v.empty) {
            return period + "还没有支出记录，暂无日平均支出、日支出标准差与中位数可算。";
        }
        String base = String.format("%s %d 天共支出 %.2f 元，日平均支出 %.2f 元，日支出标准差 %.2f 元，日支出中位数 %.2f 元。",
                period, v.days, v.total, v.average, v.stdDev, v.median);
        if (v.days < 2) {
            return base + "（只统计了 1 天，标准差暂按 0 计）";
        }
        return base + interpretation(v);
    }

    /**
     * 用中位数与日均的差距说明是否存在明显偏移；纯算术结论，不做价值判断。
     *
     * <p>阈值取 1.5 倍而不是小容差：日均与中位数天然就会有些许差异，
     * 把正常差距说成「被大额拉高」等于给用户一个错误的结论。</p>
     *
     * <p>只有中位数为 0 时才走「多数日子没支出」那一支 —— 只要有一半以上的日子
     * 没花钱，中位数必然是 0，这时日均完全由少数几天决定。</p>
     */
    private static String interpretation(Value v) {
        if (v.median.compareTo(BigDecimal.ZERO) == 0) {
            return String.format("半数以上的日子没有支出，日均主要由其中 %d 天的花费拉起来。", v.spentDays);
        }
        BigDecimal high = v.median.multiply(BigDecimal.valueOf(1.5));
        BigDecimal low = v.median.multiply(BigDecimal.valueOf(0.5));
        if (v.average.compareTo(high) > 0) {
            return "日均明显高于中位数，说明少数几天花得多，把平均值拉高了。";
        }
        if (v.average.compareTo(low) < 0) {
            return "日均明显低于中位数，说明有一半以上的日子花费都在中位数以上，另有若干天几乎没花。";
        }
        return "日均与中位数接近，每天的支出比较均匀。";
    }
}
