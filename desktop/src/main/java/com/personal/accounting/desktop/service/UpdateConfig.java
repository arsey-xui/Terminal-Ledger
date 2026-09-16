package com.personal.accounting.desktop.service;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * 更新源配置：GitHub 仓库与缓存（保存在用户数据目录，与手机端同一套约定）。
 *
 * <p>检查更新直接读取 GitHub Releases，**不需要后端在线**。</p>
 */
public final class UpdateConfig {

    /** 默认更新源仓库（owner/repo）。 */
    public static final String DEFAULT_REPO = "arsey-xui/Terminal-Ledger";

    private static final Path DATA_DIR = Paths.get(System.getProperty("user.home"), "accounting");
    private static final Path FILE = DATA_DIR.resolve("update.properties");

    /** GitHub 结果缓存时长：避免频繁打接口（未鉴权限 60 次/小时）。 */
    public static final long CACHE_MILLIS = 6L * 60 * 60 * 1000;

    private UpdateConfig() {
    }

    public static String repo() {
        Properties p = load();
        String v = p.getProperty("github_repo", "");
        return v == null || v.trim().isEmpty() ? DEFAULT_REPO : v.trim();
    }

    public static void saveRepo(String repo) throws IOException {
        Properties p = load();
        p.setProperty("github_repo", repo == null ? "" : repo.trim());
        p.remove("cache");
        p.remove("cache_at");
        store(p);
    }

    public static String cachedRelease() {
        return load().getProperty("cache");
    }

    public static long cachedAt() {
        try {
            return Long.parseLong(load().getProperty("cache_at", "0"));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public static void saveCache(String json) throws IOException {
        Properties p = load();
        p.setProperty("cache", json);
        p.setProperty("cache_at", String.valueOf(System.currentTimeMillis()));
        store(p);
    }

    public static void clearCache() throws IOException {
        Properties p = load();
        p.remove("cache");
        p.remove("cache_at");
        store(p);
    }

    private static Properties load() {
        Properties p = new Properties();
        if (!Files.exists(FILE)) return p;
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            // 读取失败按空配置处理
        }
        return p;
    }

    private static void store(Properties p) throws IOException {
        Files.createDirectories(DATA_DIR);
        try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
            p.store(w, "更新源配置（GitHub 仓库）");
        }
    }
}
