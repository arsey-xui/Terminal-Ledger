package com.personal.accounting.desktop.service;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Properties;

/** AI 消费分析配置：是否使用大模型 + 用户自填的 API Key / 自定义提示词（保存在用户数据目录）。 */
public final class AiConfig {

    /** 默认系统提示词。「恢复默认」按钮与未填提示词时的实际调用都用这一份。 */
    public static final String DEFAULT_PROMPT = AiReportGenerator.DEFAULT_PROMPT;

    private static final Path DATA_DIR = Paths.get(System.getProperty("user.home"), "accounting");
    private static final Path FILE = DATA_DIR.resolve("ai.properties");

    /** 提示词在 properties 里以 Base64(UTF-8) 存放：properties 会把换行当续行、把冒号当分隔符。 */
    private static final String KEY_PROMPT = "promptB64";

    private static boolean useLlm = false;
    private static String apiKey = "";
    private static String baseUrl = "https://api.openai.com/v1";
    private static String model = "gpt-4o-mini";
    private static String prompt = "";

    private AiConfig() {
    }

    public static void load() {
        if (!Files.exists(FILE)) return;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            p.load(r);
            useLlm = Boolean.parseBoolean(p.getProperty("useLlm", "false"));
            apiKey = p.getProperty("apiKey", "");
            if (p.getProperty("baseUrl") != null) baseUrl = p.getProperty("baseUrl");
            if (p.getProperty("model") != null) model = p.getProperty("model");
            prompt = decodePrompt(p.getProperty(KEY_PROMPT));
        } catch (IOException e) {
            // 读取失败保持默认
        }
    }

    /** 是否已做过 AI 设置（首次设置向导的依据）。 */
    public static boolean isSet() {
        return Files.exists(FILE);
    }

    public static boolean useLlm() {
        return useLlm;
    }

    public static String apiKey() {
        return apiKey;
    }

    public static String baseUrl() {
        return baseUrl;
    }

    public static String model() {
        return model;
    }

    /** 用户自定义的提示词；空串表示用 {@link #DEFAULT_PROMPT}。 */
    public static String prompt() {
        return prompt;
    }

    /** 实际生效的提示词：自定义为空时回退默认。 */
    public static String effectivePrompt() {
        return prompt.trim().isEmpty() ? DEFAULT_PROMPT : prompt.trim();
    }

    /** 是否已自定义提示词（设置页用来显示当前状态）。 */
    public static boolean hasCustomPrompt() {
        return !prompt.trim().isEmpty();
    }

    /** 是否已开启大模型且填写了 Key。 */
    public static boolean canUseLlm() {
        return useLlm && !apiKey.trim().isEmpty();
    }

    public static void save(boolean useLlm, String apiKey, String baseUrl, String model) throws IOException {
        save(useLlm, apiKey, baseUrl, model, prompt);
    }

    public static void save(boolean useLlm, String apiKey, String baseUrl, String model, String prompt)
            throws IOException {
        AiConfig.useLlm = useLlm;
        AiConfig.apiKey = apiKey == null ? "" : apiKey.trim();
        if (baseUrl != null && !baseUrl.trim().isEmpty()) AiConfig.baseUrl = baseUrl.trim();
        if (model != null && !model.trim().isEmpty()) AiConfig.model = model.trim();
        AiConfig.prompt = prompt == null ? "" : prompt.trim();
        Files.createDirectories(DATA_DIR);
        Properties p = new Properties();
        p.setProperty("useLlm", String.valueOf(AiConfig.useLlm));
        p.setProperty("apiKey", AiConfig.apiKey);
        p.setProperty("baseUrl", AiConfig.baseUrl);
        p.setProperty("model", AiConfig.model);
        p.setProperty(KEY_PROMPT, encodePrompt(AiConfig.prompt));
        try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
            p.store(w, "AI 消费分析配置（promptB64 是自定义提示词的 Base64(UTF-8)，留空表示用内置默认提示词）");
        }
    }

    /** 提示词 → Base64(UTF-8)；空提示词存空串。 */
    static String encodePrompt(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        return Base64.getEncoder().encodeToString(value.trim().getBytes(StandardCharsets.UTF_8));
    }

    /** Base64(UTF-8) → 提示词；解不开（值被手改坏）时按空处理，不抛异常。 */
    static String decodePrompt(String stored) {
        if (stored == null || stored.trim().isEmpty()) return "";
        try {
            return new String(Base64.getDecoder().decode(stored.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
