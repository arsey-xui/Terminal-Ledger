package com.personal.accounting.android.update;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.personal.accounting.android.sync.ApiClient;

import java.util.List;

/**
 * 检查更新：**直接查 GitHub Releases API**，不需要后端。
 *
 * <p>仓库名来自 {@link UpdateConfig#repo(Context)}；未鉴权的 GitHub API 限制 60 次/小时，
 * 因此结果会缓存（默认 6 小时），同一设备不会频繁打接口。</p>
 */
public final class UpdateClient {

    private static final String TAG = "UpdateClient";
    private static final Gson GSON = new Gson();

    public static final String ENGINE_GITHUB = "github";

    /** 版本信息（已按当前平台挑好下载地址；没有对应平台资产时 downloadUrl 为 null）。 */
    public static final class Release {
        public final String latestVersion;
        public final String downloadUrl;
        public final String notes;
        public final long releasedAt;

        Release(String latestVersion, String downloadUrl, String notes, long releasedAt) {
            this.latestVersion = latestVersion;
            this.downloadUrl = downloadUrl;
            this.notes = notes;
            this.releasedAt = releasedAt;
        }
    }

    /** 结果：release 非空表示拿到了最新版本信息；message 用于失败提示。 */
    public static final class Result {
        public final Release release;
        public final String message;

        Result(Release release, String message) {
            this.release = release;
            this.message = message;
        }
    }

    private UpdateClient() {
    }

    /** GitHub 上最新 release 是否比当前版本新。 */
    public static boolean hasUpdate(Context context, Release release, String currentVersion) {
        return release != null && release.latestVersion != null
                && VersionComparator.isNewer(release.latestVersion, currentVersion);
    }

    /** 查最新版本（带缓存）。 */
    public static Result latest(Context context) {
        String cached = UpdateConfig.cachedRelease(context);
        long at = UpdateConfig.cachedAt(context);
        if (cached != null && System.currentTimeMillis() - at < UpdateConfig.CACHE_MILLIS) {
            Release release = parseCached(cached);
            if (release != null) return new Result(release, null);
        }
        return fetchAndCache(context);
    }

    /** 强制刷新（用户点了「检查更新」，忽略缓存）。 */
    public static Result refresh(Context context) {
        UpdateConfig.clearCache(context);
        return fetchAndCache(context);
    }

    private static Result fetchAndCache(Context context) {
        String repo = UpdateConfig.repo(context);
        if (repo == null || !repo.contains("/")) {
            return new Result(null, "更新源未配置：请在「检查更新」里填写 GitHub 仓库（owner/repo）");
        }
        String url = "https://api.github.com/repos/" + repo.trim() + "/releases/latest";
        String staleCache = UpdateConfig.cachedRelease(context);
        try {
            VersionPayloads.GithubRelease gh =
                    new ApiClient().getAbsolute(url, VersionPayloads.GithubRelease.class);
            if (gh == null || gh.tag_name == null || gh.tag_name.trim().isEmpty()) {
                return new Result(null, "GitHub 没有返回版本信息（检查仓库名与是否已创建 Release）");
            }
            Release release = fromGithub(gh);
            UpdateConfig.saveCache(context, GSON.toJson(gh));
            return new Result(release, null);
        } catch (Exception e) {
            Log.w(TAG, "GitHub 查询失败", e);
            // 网络失败时退回过期缓存（有总比没有好）
            if (staleCache != null) {
                Release release = parseCached(staleCache);
                if (release != null) return new Result(release, null);
            }
            return new Result(null, "检查更新失败：" + e.getMessage());
        }
    }

    private static Release parseCached(String json) {
        try {
            VersionPayloads.GithubRelease gh =
                    GSON.fromJson(json, VersionPayloads.GithubRelease.class);
            return gh == null ? null : fromGithub(gh);
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 GitHub release 里取版本号/说明，并按资产名挑出当前平台可用的下载地址。 */
    private static Release fromGithub(VersionPayloads.GithubRelease gh) {
        String version = normalizeTag(gh.tag_name);
        String apk = null;
        String other = null;
        List<VersionPayloads.GithubAsset> assets = gh.assets;
        if (assets != null) {
            for (VersionPayloads.GithubAsset asset : assets) {
                if (asset == null || asset.name == null || asset.browser_download_url == null) continue;
                String lower = asset.name.toLowerCase();
                if (lower.endsWith(".apk") && (apk == null || lower.contains("arm64-v8a"))) {
                    apk = asset.browser_download_url;
                } else if (other == null) {
                    other = asset.browser_download_url;
                }
            }
        }
        long releasedAt = 0L;
        try {
            if (gh.published_at != null) {
                releasedAt = java.time.Instant.parse(gh.published_at).toEpochMilli();
            }
        } catch (Exception ignore) {
            // 时间解析失败不影响版本检查
        }
        // 手机端优先用 apk；没有 apk 资产时退回第一个资产（例如 zip）
        return new Release(version, apk != null ? apk : other, gh.body, releasedAt);
    }

    /** tag 可能是 v1.0.2 / release-1.0.10，统一取出数字版本号。 */
    public static String normalizeTag(String tag) {
        if (tag == null) return null;
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)*)").matcher(tag.trim());
        return m.find() ? m.group(1) : null;
    }
}
