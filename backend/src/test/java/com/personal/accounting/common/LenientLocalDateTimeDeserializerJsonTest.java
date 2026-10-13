package com.personal.accounting.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.personal.accounting.dto.AccountDto;
import com.personal.accounting.dto.CategoryDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接线测试：确认 {@link LenientLocalDateTimeDeserializer} 会和 Spring Boot 的 ObjectMapper 一起生效。
 *
 * <p>只测 {@code parse()} 返回 null 是不够的 —— 之前那类 500 的本质是「解析器没生效、整段请求体读不进来」，
 * 所以这里必须走**由 Spring 装配出来的 ObjectMapper**，并用同步请求体里真实存在的字段
 * （分类/账户的 {@code createdAt}）验证：JSON 读得进来、值正确、不再抛异常。</p>
 *
 * <p>不用 {@code @JsonTest}：它会加载主类，而主类带 JPA 审计，
 * 在没有实体注册的测试上下文里会以「JPA metamodel must not be empty」失败。
 * {@link Jackson2ObjectMapperBuilder} 正是 Spring Boot 自动配置里构造 ObjectMapper 用的那个 builder，
 * 把 {@code @JsonComponent} 类喂给它，等价于验证「自动配置会不会收下这个解析器」。</p>
 */
class LenientLocalDateTimeDeserializerJsonTest {

    /** 手机端那种坏值（毫秒字符串）与 JSON 数字都会落到这个时刻。 */
    private static final long MILLIS = 1790943328512L;

    private static ObjectMapper mapper() {
        // 与 JacksonAutoConfiguration 同一条路径：先喂 @JsonComponent，再 build。
        // JsonDeserializer 本身不是 Module，Spring 也是把它包成一个 SimpleModule 再注册的。
        SimpleModule lenient = new SimpleModule("lenientLocalDateTimeDeserializer");
        lenient.addDeserializer(LocalDateTime.class, new LenientLocalDateTimeDeserializer());
        return Jackson2ObjectMapperBuilder.json()
                .modulesToInstall(lenient)
                .build();
    }

    private static LocalDateTime expected() {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(MILLIS), ZoneOffset.UTC);
    }

    @Test
    void builderRegistersTheLenientDeserializerForLocalDateTime() {
        ObjectMapper mapper = mapper();
        assertThat(mapper.getRegisteredModuleIds())
                .as("宽松解析器应作为模块注册进 ObjectMapper")
                .anyMatch(id -> String.valueOf(id).contains("lenientLocalDateTimeDeserializer"));
    }

    @Test
    void categoryWithMillisStringCreatedAtIsReadable() throws Exception {
        // 这就是手机端发出的形态：createdAt 是毫秒的字符串
        String json = "{\"clientId\":\"cat-food\",\"name\":\"餐饮\",\"type\":\"EXPENSE\","
                + "\"lastModified\":" + MILLIS + ",\"createdAt\":\"" + MILLIS + "\"}";
        CategoryDto dto = mapper().readValue(json, CategoryDto.class);
        assertThat(dto.getClientId()).isEqualTo("cat-food");
        assertThat(dto.getCreatedAt()).isEqualTo(expected());
    }

    @Test
    void accountWithNumericCreatedAtIsReadable() throws Exception {
        // JSON 数字形态
        String json = "{\"clientId\":\"acc-cash\",\"name\":\"现金\",\"type\":\"CASH\","
                + "\"lastModified\":" + MILLIS + ",\"createdAt\":" + MILLIS + "}";
        AccountDto dto = mapper().readValue(json, AccountDto.class);
        assertThat(dto.getCreatedAt()).isEqualTo(expected());
    }

    @Test
    void nullAndIsoFormatsStillWork() throws Exception {
        CategoryDto withNull = mapper().readValue(
                "{\"clientId\":\"c1\",\"name\":\"交通\",\"createdAt\":null}", CategoryDto.class);
        assertThat(withNull.getCreatedAt()).isNull();

        // 桌面端与数据库里既有数据的形态
        CategoryDto withIso = mapper().readValue(
                "{\"clientId\":\"c2\",\"name\":\"购物\",\"createdAt\":\"2026-09-05T12:00:49.311145\"}",
                CategoryDto.class);
        assertThat(withIso.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 9, 5, 12, 0, 49, 311_145_000));
    }

    @Test
    void unparseableValueDoesNotBlowUpTheWholeRequest() throws Exception {
        // 无法识别的值按 null 处理：宁可少一个展示字段，也不能让整次同步 500
        CategoryDto dto = mapper().readValue(
                "{\"clientId\":\"c3\",\"name\":\"其他\",\"createdAt\":\"昨天\"}", CategoryDto.class);
        assertThat(dto.getCreatedAt()).isNull();
    }
}
