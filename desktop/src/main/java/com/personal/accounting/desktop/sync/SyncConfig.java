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
    public static volatile String BASE_URL = "";

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
            String t = p.getProperty("token");
            if (t != null) TOKEN = t.trim();
        } catch (IOException e) {
            // 读取失败保持默认空
        }
    }

    /** 保存后端地址（写盘 + 更新静态值）。 */
    public static void saveBaseUrl(String url) throws IOException {
        String v = url == null ? "" : url.trim();
        String previous = BASE_URL;
        BASE_URL = v;

        // 值没变就别写盘：程序在受限环境下（例如运行在低完整性级别的沙箱里）本来就写不了
        // 数据目录，而「地址已经是这个值」时再写一次毫无意义 —— 拒掉写操作反而会让用户
        // 以为保存失败，其实什么也没变。这也是用户点开设置、直接点保存时最常见的情况。
        if (v.equals(previous != null ? previous.trim() : "")) {
            return;
        }

        Files.createDirectories(DATA_DIR);
        Properties p = new Properties();
        p.setProperty("base_url", v);
        // 顺带把已存的密钥写回去，别让保存地址把 token 冲掉
        if (!token().isEmpty()) p.setProperty("token", token());
        try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
            p.store(w, "同步配置");
        }
    }

    /**
     * 共享密钥（对应后端 {@code accounting.sync.shared-secret}）。
     *
     * <p>留空表示后端没开校验，请求里就不带这个头。</p>
     */
    public static volatile String TOKEN = "";

    /** 当前密钥（永不为 null）。 */
    public static String token() {
        return TOKEN == null ? "" : TOKEN.trim();
    }

    /** 保存共享密钥（写盘 + 更新静态值）。空串表示清掉。 */
    public static void saveToken(String token) throws IOException {
        String value = token == null ? "" : token.trim();
        String previous = token();
        TOKEN = value;
        saveProperty("token", value, previous);
    }

    /** 写一个键值，保留文件里已有的其它键（base_url 与 token 互不覆盖）。 */
    private static void saveProperty(String key, String value, String previous) throws IOException {
        // 值没变就不写盘，理由同 saveBaseUrl：受限环境里写会失败，而写同样的值没有意义
        if (value.equals(previous)) return;

        Files.createDirectories(DATA_DIR);
        Properties p = new Properties();
        if (Files.exists(FILE)) {
            try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException ignore) {
                // 读不出来就当空文件，后面整体重写
            }
        }
        p.setProperty(key, value);
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
