package com.personal.accounting.android.update;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 更新源配置：GitHub 仓库与缓存时间。
 *
 * <p>默认带一个仓库常量，用户可在「设置 → 检查更新」里改成自己的仓库（fork 后自建发布用）。</p>
 */
public final class UpdateConfig {

    /** 默认更新源仓库（owner/repo）：检查更新直接读取该仓库的 Releases，无需后端。 */
    public static final String DEFAULT_REPO = "arsey-xui/Terminal-Ledger";

    private static final String PREFS = "update_prefs";
    private static final String K_REPO = "github_repo";
    private static final String K_CACHE = "github_cache";
    private static final String K_CACHE_AT = "github_cache_at";

    /** GitHub 结果缓存时长：避免每次检查都打接口（未鉴权限 60 次/小时）。 */
    public static final long CACHE_MILLIS = 6L * 60 * 60 * 1000;

    private UpdateConfig() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String repo(Context context) {
        String v = prefs(context).getString(K_REPO, "");
        return v == null || v.trim().isEmpty() ? DEFAULT_REPO : v.trim();
    }

    public static void saveRepo(Context context, String repo) {
        prefs(context).edit().putString(K_REPO, repo == null ? "" : repo.trim()).apply();
    }

    public static String cachedRelease(Context context) {
        return prefs(context).getString(K_CACHE, null);
    }

    public static long cachedAt(Context context) {
        return prefs(context).getLong(K_CACHE_AT, 0L);
    }

    public static void saveCache(Context context, String json) {
        prefs(context).edit()
                .putString(K_CACHE, json)
                .putLong(K_CACHE_AT, System.currentTimeMillis())
                .apply();
    }

    public static void clearCache(Context context) {
        prefs(context).edit().remove(K_CACHE).remove(K_CACHE_AT).apply();
    }
}
