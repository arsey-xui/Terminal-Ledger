package com.personal.accounting.android.sync;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 同步状态：记下「上次成功同步的时间」和「最近一次失败的原因」，供界面显示与重试。
 *
 * <p>写入口只有 {@link SyncManager}（成功/失败都在那里统一记录）。这一点很关键：
 * 记账页和小组件保存后触发的是后台同步、没有回调，之前它们的失败是完全看不见的。</p>
 *
 * <p>{@link #describe} 与 {@link #ago} 是纯函数（不碰 Android），便于单独验证措辞和边界。</p>
 */
public final class SyncStatus {

    private static final String PREFS = "sync_status";
    private static final String K_SUCCESS_AT = "last_success_at";
    private static final String K_ERROR = "last_error";
    private static final String K_ERROR_AT = "last_error_at";

    private SyncStatus() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void recordSuccess(Context context) {
        prefs(context).edit()
                .putLong(K_SUCCESS_AT, System.currentTimeMillis())
                .remove(K_ERROR)
                .apply();
    }

    public static void recordFailure(Context context, String message) {
        prefs(context).edit()
                .putString(K_ERROR, (message == null || message.isBlank()) ? "未知错误" : message)
                .putLong(K_ERROR_AT, System.currentTimeMillis())
                .apply();
    }

    /** 最近一次失败是否还没有被一次成功覆盖掉。 */
    public static boolean hasPendingFailure(Context context) {
        SharedPreferences p = prefs(context);
        long errorAt = p.getLong(K_ERROR_AT, 0L);
        return errorAt > 0 && errorAt >= p.getLong(K_SUCCESS_AT, 0L);
    }

    /** 界面上那行状态：优先显示还没消解的失败，否则显示上次成功的时间。 */
    public static String describe(Context context) {
        SharedPreferences p = prefs(context);
        return describe(System.currentTimeMillis(),
                p.getLong(K_SUCCESS_AT, 0L),
                p.getString(K_ERROR, ""),
                p.getLong(K_ERROR_AT, 0L));
    }

    static String describe(long now, long lastSuccessAt, String lastError, long lastErrorAt) {
        if (lastErrorAt > 0 && lastErrorAt >= lastSuccessAt) {
            String prefix = lastSuccessAt > 0 ? "上次成功同步于 " + ago(now, lastSuccessAt) + "；" : "";
            String reason = (lastError == null || lastError.isBlank()) ? "未知错误" : lastError;
            return prefix + "最近一次同步失败：" + reason;
        }
        if (lastSuccessAt <= 0) return "还没有同步过";
        return "上次同步于 " + ago(now, lastSuccessAt);
    }

    /**
     * 「刚刚 / N 分钟前 / N 小时前 / 昨天 / N 天前 / N 个月前」。
     * 设备时钟往回调时时间差会为负，这里夹到 0，免得出现「-3 分钟前」。
     */
    static String ago(long now, long then) {
        long seconds = Math.max(0, (now - then) / 1000);
        if (seconds < 60) return "刚刚";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " 分钟前";
        long hours = minutes / 60;
        if (hours < 24) return hours + " 小时前";
        long days = hours / 24;
        if (days == 1) return "昨天";
        if (days < 30) return days + " 天前";
        return (days / 30) + " 个月前";
    }
}
