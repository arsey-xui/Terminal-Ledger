package com.personal.accounting.android.sync;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/** 同步配置：后端地址 + 设备唯一 ID。 */
public final class SyncConfig {

    // 默认留空：首次使用时需在「设置 → 云端地址」填写自己的后端地址（BASE_URL），填了会自动保存。
    // 例：Android 模拟器访问本机后端用 http://10.0.2.2:8080；真机用电脑局域网 IP，如 http://192.168.1.100:8080。
    public static String BASE_URL = "";

    private static final String PREFS = "sync_prefs";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_TOKEN = "sync_token";

    private SyncConfig() {
    }

    /** 是否已配置后端地址（BASE_URL）。 */
    public static boolean isConfigured() {
        return BASE_URL != null && !BASE_URL.trim().isEmpty();
    }

    /**
     * 共享密钥（对应后端 {@code accounting.sync.shared-secret}）。
     *
     * <p>留空表示「后端没开校验」，请求里就不带这个头 —— 后端没配密钥时也不看它。</p>
     */
    public static String TOKEN = "";

    /** 从 SharedPreferences 读回密钥（启动时调用）。 */
    public static void loadToken(Context context) {
        String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TOKEN, "");
        TOKEN = saved == null ? "" : saved;
    }

    /** 保存密钥（写盘 + 更新静态值）。 */
    public static void saveToken(Context context, String token) {
        String value = token == null ? "" : token.trim();
        TOKEN = value;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_TOKEN, value).apply();
    }

    /** 当前密钥（永不为 null）。没配就是空串，请求里带上空头也不影响未开启校验的后端。 */
    public static String token() {
        return TOKEN == null ? "" : TOKEN.trim();
    }

    public static String deviceId(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = sp.getString(KEY_DEVICE_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            sp.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }
}
