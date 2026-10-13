package com.personal.accounting.android.data;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 本地库 {@code created_at} / {@code updated_at} 两列的取值口径。
 *
 * <p>这两列在 SQLite 里是 TEXT，历史上出现过两种写法：正常的是 ISO-8601
 * （{@code 2026-09-05T12:00:49.311145}），但 {@link DatabaseHelper} 写默认分类/账户时
 * 误用了毫秒时间戳字符串（{@code 1790943328512}）。后者同步到后端会直接 500
 * （后端按 {@code LocalDateTime} 解析这段文本）。</p>
 *
 * <p>所以：<b>写入一律走 ISO</b>；读出来的历史坏值顺手规范成 ISO，让下一次落库自动修正。</p>
 */
final class Timestamps {

    private Timestamps() {
    }

    /** 当前时间的 ISO-8601 文本（不带时区，与后端 {@code LocalDateTime} 对齐）。 */
    static String nowIso() {
        return LocalDateTime.now().toString();
    }

    /** 规格化成 ISO：能被识别为毫秒的纯数字按 UTC 换算，其余原样返回（null/空也原样）。 */
    static String toIso(String stored) {
        if (stored == null) return null;
        String value = stored.trim();
        if (value.isEmpty() || !isAllDigits(value)) return stored;
        try {
            long millis = Long.parseLong(value);
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC).toString();
        } catch (NumberFormatException | java.time.DateTimeException e) {
            // 超出可表示范围之类：保留原值，不去猜
            return stored;
        }
    }

    private static boolean isAllDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }
}
