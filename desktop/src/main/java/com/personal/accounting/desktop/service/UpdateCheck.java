package com.personal.accounting.desktop.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检查更新：**直接查 GitHub Releases API**，不依赖后端服务。
 *
 * <p>仓库来自 {@link UpdateConfig#repo()}；未鉴权的 GitHub API 限制 60 次/小时，
 * 所以结果会缓存（默认 6 小时）。桌面端是免安装目录，无法自动覆盖安装，
 * 因此只负责"发现新版本 + 打开下载页"。</p>
 */
public final class UpdateCheck {

    /** 版本信息（已按平台挑好下载地址：桌面端优先 Desktop zip，其次 apk）。 */
    public static final class Info {
        public final String latestVersion;
        public final String downloadUrl;
        public final String notes;

        Info(String latestVersion, String downloadUrl, String notes) {
            this.latestVersion = latestVersion;
            this.downloadUrl = downloadUrl;
            this.notes = notes;
        }

        public boolean hasDownloadUrl() {
            return downloadUrl != null && !downloadUrl.trim().isEmpty();
        }
    }

    /** 检查结果：info 非空表示查到了版本信息，message 用于提示/报错。 */
    public static final class Result {
        public final Info info;
        public final String message;

        Result(Info info, String message) {
            this.info = info;
            this.message = message;
        }

        public boolean hasInfo() {
            return info != null;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    /** 版本号里取数字段：v1.0.2 / release-1.0.10 都能解析。 */
    private static final Pattern VERSION_IN_TAG = Pattern.compile("(\\d+(?:\\.\\d+)*)");
    /** jpackage 启动参数里带的版本号，形如 -Djpackage.app-version=1.0.1 */
    private static final Pattern CFG_VERSION = Pattern.compile("jpackage\\.app-version=([0-9][0-9.]*)");

    private UpdateCheck() {
    }

    // ---------------- 当前版本 ----------------

    private static String fallbackVersion = "0.0.0";

    public static void setFallbackVersion(String version) {
        if (version != null && !version.trim().isEmpty()) fallbackVersion = version.trim();
    }

    /**
     * 当前程序版本。优先读 jpackage 注入的 {@code jpackage.app-version}（app-image/exe 都带），
     * 其次读 jar 清单（pom 里写了 Implementation-Version），最后回退。
     */
    public static String currentVersion() {
        String jpackage = System.getProperty("jpackage.app-version");
        if (jpackage != null && !jpackage.trim().isEmpty()) return jpackage.trim();
        try {
            String manifest = UpdateCheck.class.getPackage().getImplementationVersion();
            if (manifest != null && !manifest.trim().isEmpty()) return manifest.trim();
        } catch (Exception ignore) {
            // 继续用兜底版本
        }
        return fallbackVersion;
    }

    /** 从 cfg 文本里解析 jpackage 写入的版本（便于测试）。 */
    public static String parseCfgVersion(String cfgText) {
        if (cfgText == null) return null;
        Matcher m = CFG_VERSION.matcher(cfgText);
        return m.find() ? m.group(1) : null;
    }

    /** tag 名里取数字版本号。 */
    public static String normalizeTag(String tag) {
        if (tag == null) return null;
        Matcher m = VERSION_IN_TAG.matcher(tag.trim());
        return m.find() ? m.group(1) : null;
    }

    // ---------------- 检查更新 ----------------

    /** 查询 GitHub 最新 release（带缓存）；失败时给出提示信息。 */
    public static Result latest() {
        if (System.currentTimeMillis() - UpdateConfig.cachedAt() < UpdateConfig.CACHE_MILLIS) {
            String cached = UpdateConfig.cachedRelease();
            if (cached != null) {
                Info info = fromJson(cached);
                if (info != null) return new Result(info, null);
            }
        }
        return fetch();
    }

    /** 强制刷新（忽略缓存）。 */
    public static Result refresh() {
        try {
            UpdateConfig.clearCache();
        } catch (Exception ignore) {
            // 缓存清理失败不影响查询
        }
        return fetch();
    }

    private static Result fetch() {
        String repo = UpdateConfig.repo();
        if (repo == null || !repo.contains("/")) {
            return new Result(null, "更新源未配置：请填写 GitHub 仓库（owner/repo）");
        }
        String url = "https://api.github.com/repos/" + repo.trim() + "/releases/latest";
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(request).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                return new Result(null, "GitHub 返回 HTTP " + resp.code()
                        + (resp.code() == 404 ? "（仓库名是否正确、是否已创建 Release）" : ""));
            }
            Info info = fromJson(body);
            if (info == null) return new Result(null, "GitHub 没有返回可用版本信息");
            try {
                UpdateConfig.saveCache(body);
            } catch (Exception ignore) {
                // 缓存写失败不影响本次结果
            }
            return new Result(info, null);
        } catch (Exception e) {
            // 网络失败时退回过期缓存
            String stale = UpdateConfig.cachedRelease();
            if (stale != null) {
                Info info = fromJson(stale);
                if (info != null) return new Result(info, null);
            }
            return new Result(null, "检查更新失败：" + e.getMessage());
        }
    }

    /** 解析 GitHub release JSON：取 tag 版本号、body 说明，并挑一个适合桌面端的下载地址。 */
    static Info fromJson(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JsonNode root = MAPPER.readTree(json);
            String tag = root.path("tag_name").asText(null);
            String version = normalizeTag(tag);
            if (version == null) return null;
            String notes = root.path("body").asText(null);

            String desktopZip = null;
            String apk = null;
            String anyAsset = null;
            for (JsonNode asset : root.path("assets")) {
                String name = asset.path("name").asText("");
                String url = asset.path("browser_download_url").asText(null);
                if (url == null || name.isEmpty()) continue;
                String lower = name.toLowerCase();
                if (anyAsset == null) anyAsset = url;
                if (lower.endsWith(".zip") && lower.contains("desktop") && desktopZip == null) {
                    desktopZip = url;
                } else if (lower.endsWith(".apk") && apk == null) {
                    apk = url;
                }
            }
            // 桌面端优先给 Desktop zip，其次 apk，最后随意一个资产
            String downloadUrl = desktopZip != null ? desktopZip : (apk != null ? apk : anyAsset);
            return new Info(version, downloadUrl, notes);
        } catch (Exception e) {
            return null;
        }
    }

    /** 版本比较：按数字逐段比较，"1.0.10" > "1.0.9"；后缀（如 -test）不参与。 */
    public static boolean isNewer(String candidate, String current) {
        int[] a = parts(candidate);
        int[] b = parts(current);
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parts(String version) {
        if (version == null) return new int[0];
        String cleaned = version.trim();
        if (cleaned.startsWith("v") || cleaned.startsWith("V")) cleaned = cleaned.substring(1);
        String[] segs = cleaned.split("\\.");
        int[] out = new int[segs.length];
        for (int i = 0; i < segs.length; i++) {
            Matcher m = Pattern.compile("^(\\d+)").matcher(segs[i].trim());
            out[i] = m.find() ? parseInt(m.group(1)) : 0;
        }
        return out;
    }

    private static int parseInt(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
