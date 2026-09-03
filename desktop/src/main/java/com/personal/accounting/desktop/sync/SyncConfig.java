package com.personal.accounting.desktop.sync;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.UUID;

/** 同步配置：后端地址 + 设备唯一 ID（持久化到本地数据目录）。 */
public final class SyncConfig {

    // 默认留空：首次使用需在「数据管理 → 设置后端地址」填写自己的后端地址（如 http://localhost:8080 或局域网 IP），填了自动保存。
    public static String BASE_URL = "";

    private static final Path DATA_DIR = Paths.get(System.getProperty("user.home"), "accounting");
    private static final Path FILE = DATA_DIR.resolve("sync.properties");

    private SyncConfig() {
    }

    public static Path dataDir() {
        return DATA_DIR;
    }

    /** 是否已配置后端地址。 */
    public static boolean isConfigured() {
        return BASE_URL != null && !BASE_URL.trim().isEmpty();
    }

    /** 启动时从数据目录读取已保存的后端地址。 */
    public static void loadBaseUrl() {
        if (!Files.exists(FILE)) return;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            p.load(r);
            String v = p.getProperty("base_url");
            if (v != null) BASE_URL = v.trim();
        } catch (IOException e) {
            // 读取失败保持默认空
        }
    }

    /** 保存后端地址（写盘 + 更新静态值）。 */
    public static void saveBaseUrl(String url) throws IOException {
        String v = url == null ? "" : url.trim();
        BASE_URL = v;
        Files.createDirectories(DATA_DIR);
        Properties p = new Properties();
        p.setProperty("base_url", v);
        try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
            p.store(w, "同步配置");
        }
    }

    public static String deviceId() {
        try {
            Files.createDirectories(DATA_DIR);
            Path file = DATA_DIR.resolve("device.id");
            if (Files.exists(file)) {
                return Files.readString(file).trim();
            }
            String id = UUID.randomUUID().toString();
            Files.writeString(file, id);
            return id;
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }
}
