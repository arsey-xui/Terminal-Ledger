package com.personal.accounting.desktop.service;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/** AI 消费分析配置：是否使用大模型 + 用户自填的 API Key（保存在用户数据目录）。 */
public final class AiConfig {

    private static final Path DATA_DIR = Paths.get(System.getProperty("user.home"), "accounting");
    private static final Path FILE = DATA_DIR.resolve("ai.properties");

    private static boolean useLlm = false;
    private static String apiKey = "";
    private static String baseUrl = "https://api.openai.com/v1";
    private static String model = "gpt-4o-mini";

    private AiConfig() {
    }

    public static void load() {
        if (!Files.exists(FILE)) return;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(FILE, java.nio.charset.StandardCharsets.UTF_8)) {
            p.load(r);
            useLlm = Boolean.parseBoolean(p.getProperty("useLlm", "false"));
            apiKey = p.getProperty("apiKey", "");
            if (p.getProperty("baseUrl") != null) baseUrl = p.getProperty("baseUrl");
            if (p.getProperty("model") != null) model = p.getProperty("model");
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

    /** 是否已开启大模型且填写了 Key。 */
    public static boolean canUseLlm() {
        return useLlm && !apiKey.trim().isEmpty();
    }

    public static void save(boolean useLlm, String apiKey, String baseUrl, String model) throws IOException {
        AiConfig.useLlm = useLlm;
        AiConfig.apiKey = apiKey == null ? "" : apiKey.trim();
        if (baseUrl != null && !baseUrl.trim().isEmpty()) AiConfig.baseUrl = baseUrl.trim();
        if (model != null && !model.trim().isEmpty()) AiConfig.model = model.trim();
        Files.createDirectories(DATA_DIR);
        Properties p = new Properties();
        p.setProperty("useLlm", String.valueOf(AiConfig.useLlm));
        p.setProperty("apiKey", AiConfig.apiKey);
        p.setProperty("baseUrl", AiConfig.baseUrl);
        p.setProperty("model", AiConfig.model);
        try (Writer w = Files.newBufferedWriter(FILE, java.nio.charset.StandardCharsets.UTF_8)) {
            p.store(w, "AI 消费分析配置");
        }
    }
}
