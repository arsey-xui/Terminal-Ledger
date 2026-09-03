package com.personal.accounting.desktop.ui;

import com.personal.accounting.desktop.data.DataManager;
import com.personal.accounting.desktop.sync.SyncConfig;

/** 共享的全局状态：单例 DataManager。 */
public final class AppState {

    private static DataManager db;

    private AppState() {
    }

    public static synchronized DataManager db() {
        if (db == null) {
            db = new DataManager(SyncConfig.dataDir().resolve("accounting"));
        }
        return db;
    }
}
