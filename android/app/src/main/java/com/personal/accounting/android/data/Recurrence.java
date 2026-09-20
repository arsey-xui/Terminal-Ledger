package com.personal.accounting.android.data;

import com.personal.accounting.android.model.RecurringRule;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 周期到期日推算。三端（手机 / 电脑 / 后端口径）遵循同一规则：
 *
 * <ul>
 *   <li>DAY：锚点日 + N 天；</li>
 *   <li>WEEK：锚点日 + N 周；</li>
 *   <li>MONTH：锚点日 + N 个月，日按锚点日取，遇到当月没有该日则取月末
 *       （例如锚点 31 日：2 月取 28/29，4 月取 30）。</li>
 * </ul>
 *
 * <p>生成的待确认项 clientId 是确定值 {@code rr-<规则id前8位>-<yyyyMMdd>}，
 * 既保证同一到期日不会重复生成，也保证多设备重复确认时生成的是同一条流水。</p>
 */
public final class Recurrence {

    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA);
    private static final DateTimeFormatter COMPACT = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.CHINA);
    /** 单次生成上限，避免用户误填巨大间隔导致长时间循环。 */
    private static final int MAX_OCCURRENCES = 400;

    private Recurrence() {
    }

    public static LocalDate parse(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return LocalDate.parse(text.trim().substring(0, Math.min(10, text.trim().length())), DATE);
        } catch (Exception e) {
            return null;
        }
    }

    public static String format(LocalDate date) {
        return date == null ? null : date.format(DATE);
    }

    /** 锚点日 + 间隔（第 index 次，index 从 0 开始，0 表示锚点本身）。 */
    public static LocalDate occurrence(RecurringRule rule, int index) {
        LocalDate anchor = parse(rule.anchorDate);
        if (anchor == null) return null;
        int step = Math.max(1, rule.intervalCount);
        String unit = rule.intervalUnit == null ? "MONTH" : rule.intervalUnit.toUpperCase(Locale.ROOT);
        switch (unit) {
            case "DAY":
                return anchor.plusDays((long) step * index);
            case "WEEK":
                return anchor.plusWeeks((long) step * index);
            case "MONTH":
            default:
                return plusMonthsClamped(anchor, (long) step * index);
        }
    }

    /** 加 N 个月并按锚点日取日；当月没有该日则夹到月末。 */
    private static LocalDate plusMonthsClamped(LocalDate anchor, long months) {
        LocalDate shifted = anchor.plusMonths(months);
        int day = Math.min(anchor.getDayOfMonth(), shifted.lengthOfMonth());
        return shifted.withDayOfMonth(day);
    }

    /**
     * 需要生成待确认项的到期日：{@code lastGeneratedDate}（含）之后、到 {@code until}（含）为止。
     * 从未生成过时以锚点日为起点，所以「今天新建规则」不会补记历史。
     */
    public static List<LocalDate> dueDates(RecurringRule rule, LocalDate until) {
        List<LocalDate> out = new ArrayList<>();
        if (rule == null || !rule.enabled || until == null) return out;
        int step = Math.max(1, rule.intervalCount);
        if (step > 3650) return out;
        LocalDate after = parse(rule.lastGeneratedDate);
        if (parse(rule.anchorDate) == null) return out;
        for (int i = 0; i <= 4000; i++) {
            LocalDate date = occurrence(rule, i);
            if (date == null) break;
            if (date.isAfter(until)) break;
            if (after == null || date.isAfter(after)) out.add(date);
            if (out.size() > MAX_OCCURRENCES) break;
        }
        return out;
    }

    /** 待确认项 / 生成流水的 clientId（规则 id 前 8 位 + 日期），保证幂等且长度 <= 36。 */
    public static String occurrenceId(RecurringRule rule, LocalDate date) {
        String ruleId = rule.clientId == null ? "rule" : rule.clientId;
        String prefix = ruleId.length() > 8 ? ruleId.substring(0, 8) : ruleId;
        return "rr-" + prefix + "-" + date.format(COMPACT);
    }

    /** 规则的可读描述，如「每月 1 日」「每 30 天」。 */
    public static String describe(RecurringRule rule) {
        int step = Math.max(1, rule.intervalCount);
        String unit = rule.intervalUnit == null ? "MONTH" : rule.intervalUnit.toUpperCase(Locale.ROOT);
        LocalDate anchor = parse(rule.anchorDate);
        switch (unit) {
            case "DAY":
                return step == 1 ? "每天" : "每 " + step + " 天";
            case "WEEK":
                return step == 1 ? "每周" : "每 " + step + " 周";
            case "MONTH":
            default:
                String suffix = anchor == null ? "" : " " + anchor.getDayOfMonth() + " 日";
                return step == 1 ? "每月" + suffix : "每 " + step + " 个月" + suffix;
        }
    }
}
