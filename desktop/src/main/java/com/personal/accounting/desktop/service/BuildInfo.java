package com.personal.accounting.desktop.service;

import java.io.InputStream;
import java.util.jar.Manifest;

/**
 * 当前构建的标识：版本、构建时间、提交号。
 *
 * <p>这些信息在打包时写进 fat jar 的清单（见 {@code desktop/pom.xml} 的 {@code manifestEntries}
 * 与 {@code scripts/build-desktop-installer.ps1}）。同一个版本号下出了多个包时，靠构建时间和
 * 提交号区分——否则出问题时只能报一句「v1.3.0」，对不上是哪一个。</p>
 */
public final class BuildInfo {

    private static String builtAt;
    private static String commit;
    private static boolean loaded;

    private BuildInfo() {
    }

    /** 版本号：与 {@link UpdateCheck#currentVersion()} 同源（jpackage 注入值 → jar 清单 → 兜底）。 */
    public static String version() {
        return UpdateCheck.currentVersion();
    }

    /** 构建时间（打包时写入）；读不到返回空串。 */
    public static String builtAt() {
        load();
        return builtAt;
    }

    /** 提交号（打包时写入）；读不到返回空串。 */
    public static String commit() {
        load();
        return commit;
    }

    /** 一行摘要，例如 {@code v1.3.0 · 2026-10-17 19:40 · a1a56bf}。 */
    public static String summary() {
        StringBuilder sb = new StringBuilder("v").append(version());
        String at = builtAt();
        if (!at.isEmpty()) sb.append(" · ").append(at);
        String hash = commit();
        if (!hash.isEmpty()) sb.append(" · ").append(hash);
        return sb.toString();
    }

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        builtAt = "";
        commit = "";
        // 注意用 /META-INF/MANIFEST.MF 这个绝对路径：拿到的就是本 jar 的清单。
        try (InputStream in = BuildInfo.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            if (in == null) return;
            Manifest mf = new Manifest(in);
            String at = mf.getMainAttributes().getValue("Built-At");
            if (at != null) builtAt = at.trim();
            String hash = mf.getMainAttributes().getValue("Git-Commit");
            if (hash != null) commit = hash.trim();
        } catch (Exception ignore) {
            // 读不到就不显示这部分，不影响启动（例如在 IDE 里从 target/classes 直接跑）
        }
    }
}
