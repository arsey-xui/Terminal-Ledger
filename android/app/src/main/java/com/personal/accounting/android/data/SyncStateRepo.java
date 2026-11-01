package com.personal.accounting.android.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

public class SyncStateRepo {
    private final DatabaseHelper helper;

    public SyncStateRepo(Context context) {
        helper = DatabaseHelper.getInstance(context);
    }

    private SQLiteDatabase rw() {
        return helper.getWritableDatabase();
    }

    /** 读取 pull 水位（服务端时钟，默认 0 = 还没同步过）。 */
    public long lastSync(String deviceId) {
        return read(deviceId, "last_sync");
    }

    /** 读取 push 水位（本机 last_modified 时钟，默认 0 = 还没推过）。 */
    public long pushWatermark(String deviceId) {
        return read(deviceId, "push_watermark");
    }

    public void saveLastSync(String deviceId, long ts) {
        write(deviceId, "last_sync", ts);
    }

    public void savePushWatermark(String deviceId, long ts) {
        write(deviceId, "push_watermark", ts);
    }

    private long read(String deviceId, String column) {
        try (Cursor c = rw().query("sync_state", new String[]{column},
                "device_id=?", new String[]{deviceId}, null, null, null)) {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        }
    }

    /**
     * 写单列。
     *
     * <p>**不能用 {@code CONFLICT_REPLACE}**：REPLACE 是「删掉整行再插入」，只带一列的话
     * 另一列会被重置成默认值 —— 写 pull 水位会把 push 水位清零，反过来也一样，
     * 结果是每次同步都在两个水位之间互相抹掉。这里改成「先 UPDATE，没有行再 INSERT」。</p>
     */
    private void write(String deviceId, String column, long ts) {
        ContentValues v = new ContentValues();
        v.put(column, ts);
        int rows = rw().update("sync_state", v, "device_id=?", new String[]{deviceId});
        if (rows > 0) return;
        ContentValues insert = new ContentValues();
        insert.put("device_id", deviceId);
        insert.put(column, ts);
        rw().insertWithOnConflict("sync_state", null, insert, SQLiteDatabase.CONFLICT_IGNORE);
    }
}
