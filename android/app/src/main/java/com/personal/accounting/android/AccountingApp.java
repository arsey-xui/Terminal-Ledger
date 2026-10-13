package com.personal.accounting.android;

import android.app.Application;
import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * 应用入口：显式声明本应用**自己处理深浅模式**。
 *
 * <p>为什么需要这一步：本应用自带完整的深浅两套配色（{@code values-night}），
 * 但部分 ROM（ColorOS、MIUI 等）的启发式「强制深色」会在浅色主题的应用上自作主张地反色 ——
 * 它只反文字不反背景，结果是白字压浅灰底、完全看不清。
 * 主题里已经写了 {@code android:forceDarkAllowed=false}，这里再显式把模式定下来，
 * 让框架直接取对应的一套资源，而不是让系统去猜。</p>
 *
 * <p>默认「跟随系统」。设置页可以强制浅色/深色，用于区分
 * 「配色本身好不好看」与「系统跟随是否正常」这两件事。</p>
 */
public class AccountingApp extends Application {

    private static final String PREFS = "app_prefs";
    private static final String KEY_THEME = "theme_mode";

    /** 跟随系统（默认）。 */
    public static final int MODE_SYSTEM = 0;
    /** 强制浅色。 */
    public static final int MODE_LIGHT = 1;
    /** 强制深色。 */
    public static final int MODE_DARK = 2;

    /** 进程内唯一的实例，供设置页读取当前模式（Application#onCreate 一定早于任何界面）。 */
    private static AccountingApp instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        useStoredMode();
    }

    /**
     * 应用级 Context，供只拿到 {@code MenuItem} 之类没有 Context 的对象加载资源。
     *
     * <p>{@code onCreate} 一定早于任何界面，所以界面调用时它必然非 null。</p>
     */
    public static Context appContext() {
        return instance;
    }

    /** 读出用户保存的模式并应用；未设置过或值不合法时按「跟随系统」。 */
    public static void useStoredMode() {
        AppCompatDelegate.setDefaultNightMode(toNightMode(storedMode()));
    }

    /** 保存并立即应用（会触发界面重建，配色随之切换）。 */
    public static void setThemeMode(int mode) {
        if (instance != null) {
            instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_THEME, mode)
                    .apply();
        }
        AppCompatDelegate.setDefaultNightMode(toNightMode(mode));
    }

    /** 当前保存的模式（默认跟随系统）。 */
    public static int storedMode() {
        if (instance == null) return MODE_SYSTEM;
        return instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_THEME, MODE_SYSTEM);
    }

    private static int toNightMode(int mode) {
        switch (mode) {
            case MODE_LIGHT:
                return AppCompatDelegate.MODE_NIGHT_NO;
            case MODE_DARK:
                return AppCompatDelegate.MODE_NIGHT_YES;
            default:
                return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
    }
}
