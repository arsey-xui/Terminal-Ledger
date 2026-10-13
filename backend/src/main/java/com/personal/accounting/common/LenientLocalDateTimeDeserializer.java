package com.personal.accounting.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import org.springframework.boot.jackson.JsonComponent;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * 宽松的 {@link LocalDateTime} 反序列化：同时接受 ISO-8601 文本与 Unix 毫秒。
 *
 * <p>为什么需要它：手机端写入默认分类/账户时，把 {@code created_at} 存成了毫秒时间戳字符串
 * （{@code "1790943328512"}），同步推送时后端按 {@code LocalDateTime} 解析直接 500
 * （{@code Text '1790943328512' could not be parsed at index 0}）。默认分类与默认账户是每台设备
 * 都有的数据，所以这个值必然出现在每一次推送里 —— 一旦有设备存过这种值，那台设备就完全无法同步。</p>
 *
 * <p>兼容三种形态：</p>
 * <ul>
 *   <li>{@code "2026-09-05T12:00:49.311145"}：正常 ISO 文本（桌面端与数据库里既有数据的形态）；</li>
 *   <li>{@code 1790943328512}：JSON 数字形式的毫秒；</li>
 *   <li>{@code "1790943328512"}：**毫秒数字的字符串**，也就是手机端那种坏值。</li>
 * </ul>
 *
 * <p>空串与无法识别的值一律按 {@code null} 处理：这两个字段只用于展示，不该为了它们
 * 让整次同步失败 —— 同步的主体是金额与分类，服务端宁可少一个展示字段。</p>
 */
@JsonComponent
public class LenientLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

    @Override
    public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        return parse(p.getValueAsString());
    }

    /** 供同步合并与单元测试直接调用。 */
    public static LocalDateTime parse(String text) {
        if (text == null) return null;
        String value = text.trim();
        if (value.isEmpty()) return null;

        // 纯数字（含可选负号）= Unix 毫秒
        if (isEpochMillis(value)) {
            try {
                long millis = Long.parseLong(value);
                return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
            } catch (NumberFormatException | java.time.DateTimeException e) {
                return null;
            }
        }

        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            // 再试一次「带时区的 ISO」，最后放弃（按 null 处理，不阻断同步）
            try {
                return LocalDateTime.ofInstant(Instant.parse(value), ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private static boolean isEpochMillis(String value) {
        int start = value.charAt(0) == '-' ? 1 : 0;
        if (start >= value.length()) return false;
        for (int i = start; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }
}
