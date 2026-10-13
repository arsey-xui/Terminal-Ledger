package com.personal.accounting.common;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 宽松时间解析：手机端的毫秒字符串、JSON 数字与正常 ISO 文本都要能收下。
 *
 * <p>回归背景：手机端把默认分类/账户的 {@code created_at} 存成 {@code "1790943328512"}，
 * 后端按 {@code LocalDateTime} 解析直接 500，导致那台设备完全无法同步。</p>
 */
class LenientLocalDateTimeDeserializerTest {

    /** 与实现同一口径（UTC），避免手写期望值时算错。 */
    private static LocalDateTime fromMillis(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    @Test
    void parsesIsoTextFromDesktopAndDatabase() {
        assertEquals(LocalDateTime.of(2026, 9, 5, 12, 0, 49, 311_145_000),
                LenientLocalDateTimeDeserializer.parse("2026-09-05T12:00:49.311145"));
        assertEquals(LocalDateTime.of(2026, 10, 7, 22, 36, 18),
                LenientLocalDateTimeDeserializer.parse("2026-10-07T22:36:18"));
    }

    @Test
    void parsesEpochMillisAsStringAndAsNumber() {
        // 手机端那种坏值：毫秒的字符串形态
        assertEquals(fromMillis(1790943328512L), LenientLocalDateTimeDeserializer.parse("1790943328512"));
        // JSON 数字形态走的是同一条路径（JsonParser.getValueAsString 会把数字转成字符串）
        assertEquals(fromMillis(1790943328512L), LenientLocalDateTimeDeserializer.parse("1790943328512"));
        assertEquals(fromMillis(0L), LenientLocalDateTimeDeserializer.parse("0"));
    }

    @Test
    void acceptsOffsetIsoAsFallback() {
        // 带时区的 ISO 也收，按同一瞬间换算（UTC）
        assertEquals(LocalDateTime.of(2026, 10, 7, 14, 36, 18),
                LenientLocalDateTimeDeserializer.parse("2026-10-07T22:36:18+08:00"));
    }

    @Test
    void blankAndUnparseableBecomeNullInsteadOfFailingTheSync() {
        assertNull(LenientLocalDateTimeDeserializer.parse(null));
        assertNull(LenientLocalDateTimeDeserializer.parse(""));
        assertNull(LenientLocalDateTimeDeserializer.parse("   "));
        assertNull(LenientLocalDateTimeDeserializer.parse("昨天"));
        assertNull(LenientLocalDateTimeDeserializer.parse("2026-13-45T99:99:99"));
    }
}
