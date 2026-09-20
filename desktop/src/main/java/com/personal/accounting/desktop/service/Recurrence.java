package com.personal.accounting.desktop.service;

import com.personal.accounting.desktop.model.RecurringRule;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 周期到期日推算（与手机端 {@code Recurrence} 完全同口径）：
 * DAY 加 N 天、WEEK 加 N 周、MONTH 加 N 个月且日按锚点日取（当月没有该日则取月末）。
 * 待确认项 / 生成流水的 id 固定为 {@code rr-<规则id前8位>-<yyyyMMdd>}。
 */
public final class Recurrence {

    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA);
    private static final DateTimeFormatter COMPACT = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.CHINA);
    private static final int MAX_OCCURRENCES = 400;

    private Recurrence() {
    }

    public static LocalDate occurrence(RecurringRule rule, int index) {
        if (rule == null || rule.anchorDate == null) return null;
        int step = Math.max(1, rule.intervalCount);
        String unit = rule.intervalUnit == null ? "MONTH" : rule.intervalUnit.toUpperCase(Locale.ROOT);
        switch (unit) {
            case "DAY":
                return rule.anchorDate.plusDays((long) step * index);
            case "WEEK":
                return rule.anchorDate.plusWeeks((long) step * index);
            case "MONTH":
            default:
                return plusMonthsClamped(rule.anchorDate, (long) step * index);
        }
    }

    private static LocalDate plusMonthsClamped(LocalDate anchor, long months) {
        LocalDate shifted = anchor.plusMonths(months);
        return shifted.withDayOfMonth(Math.min(anchor.getDayOfMonth(), shifted.lengthOfMonth()));
    }

    /** 需要生成的到期日：lastGeneratedDate（含）之后、到 until（含）为止。 */
    public static List<LocalDate> dueDates(RecurringRule rule, LocalDate until) {
        List<LocalDate> out = new ArrayList<>();
        if (rule == null || !rule.enabled || until == null || rule.anchorDate == null) return out;
        if (Math.max(1, rule.intervalCount) > 3650) return out;
        for (int i = 0; i <= 4000; i++) {
            LocalDate date = occurrence(rule, i);
            if (date == null || date.isAfter(until)) break;
            if (rule.lastGeneratedDate == null || date.isAfter(rule.lastGeneratedDate)) out.add(date);
            if (out.size() > MAX_OCCURRENCES) break;
        }
        return out;
    }

    public static String occurrenceId(RecurringRule rule, LocalDate date) {
        String ruleId = rule.clientId == null ? "rule" : rule.clientId;
        String prefix = ruleId.length() > 8 ? ruleId.substring(0, 8) : ruleId;
        return "rr-" + prefix + "-" + date.format(COMPACT);
    }

    public static String describe(RecurringRule rule) {
        int step = Math.max(1, rule.intervalCount);
        String unit = rule.intervalUnit == null ? "MONTH" : rule.intervalUnit.toUpperCase(Locale.ROOT);
        switch (unit) {
            case "DAY":
                return step == 1 ? "每天" : "每 " + step + " 天";
            case "WEEK":
                return step == 1 ? "每周" : "每 " + step + " 周";
            case "MONTH":
            default:
                String suffix = rule.anchorDate == null ? "" : " " + rule.anchorDate.getDayOfMonth() + " 日";
                return step == 1 ? "每月" + suffix : "每 " + step + " 个月" + suffix;
        }
    }
}
