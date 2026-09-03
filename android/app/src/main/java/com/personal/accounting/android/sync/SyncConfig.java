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

    private SyncConfig() {
    }

    /** 是否已配置后端地址（BASE_URL）。 */
    public static boolean isConfigured() {
        return BASE_URL != null && !BASE_URL.trim().isEmpty();
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
