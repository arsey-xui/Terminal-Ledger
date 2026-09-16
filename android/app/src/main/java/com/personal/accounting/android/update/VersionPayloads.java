package com.personal.accounting.android.update;

/** 与后端 GET /api/version 对应的传输对象。 */
public final class VersionPayloads {

    private VersionPayloads() {
    }

    public static class Response {
        public String latestVersion;
        public String minSupportedVersion;
        public String downloadUrl;
        public String notes;
        public long releasedAt;
    }

    // ---------- GitHub Releases API（直接查 GitHub，不需要后端） ----------

    /** GET https://api.github.com/repos/{owner}/{repo}/releases/latest */
    public static class GithubRelease {
        /** 例如 "1.0.2" 或 "v1.0.2"。 */
        public String tag_name;
        /** 发布说明（Markdown 文本）。 */
        public String body;
        /** ISO8601 发布时间。 */
        public String published_at;
        public java.util.List<GithubAsset> assets;
    }

    public static class GithubAsset {
        public String name;
        /** 点击即下载的地址。 */
        public String browser_download_url;
    }
}
