package com.personal.accounting.android.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

public class SyncStateRepo {
    private final DatabaseHelper helper;

    public SyncStateRepo(Context context) {
        helper = new DatabaseHelper(context);
    }

    private SQLiteDatabase rw() {
        return helper.getWritableDatabase();
    }

    /** 读取上次同步水位（默认 0）。 */
    public long lastSync(String deviceId) {
        try (Cursor c = rw().query("sync_state", new String[]{"last_sync"},
                "device_id=?", new String[]{deviceId}, null, null, null)) {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        }
    }

    public void saveLastSync(String deviceId, long ts) {
        ContentValues v = new ContentValues();
        v.put("device_id", deviceId);
        v.put("last_sync", ts);
        rw().insertWithOnConflict("sync_state", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }
}
