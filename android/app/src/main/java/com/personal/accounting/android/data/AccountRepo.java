package com.personal.accounting.android.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.personal.accounting.android.model.Account;

import java.util.ArrayList;
import java.util.List;

public class AccountRepo {
    private final DatabaseHelper helper;

    public AccountRepo(Context context) {
        helper = DatabaseHelper.getInstance(context);
    }

    private SQLiteDatabase rw() {
        return helper.getWritableDatabase();
    }

    public List<Account> list() {
        List<Account> list = new ArrayList<>();
        try (Cursor c = rw().query("accounts", null, "deleted=0", null, null, null, "sort_order ASC")) {
            while (c.moveToNext()) list.add(fromCursor(c));
        }
        return list;
    }

    public boolean upsertLww(Account incoming) {
        Account existing = find(incoming.clientId);
        if (existing != null && existing.lastModified > incoming.lastModified) return false;
        rw().insertWithOnConflict("accounts", null, toValues(incoming), SQLiteDatabase.CONFLICT_REPLACE);
        return true;
    }

    public Account find(String clientId) {
        // 同 CategoryRepo.find：空值不能进 selectionArgs，否则 bindString 抛异常。
        if (clientId == null) return null;
        try (Cursor c = rw().query("accounts", null, "client_id=?", new String[]{clientId}, null, null, null)) {
            return c.moveToFirst() ? fromCursor(c) : null;
        }
    }

    /** 自 last_modified > since 的全部账户（含已删除墓碑）。 */
    public List<Account> listModifiedSince(long since) {
        List<Account> list = new ArrayList<>();
        try (Cursor c = rw().query("accounts", null, "last_modified>?",
                new String[]{String.valueOf(since)}, null, null, "last_modified ASC")) {
            while (c.moveToNext()) list.add(fromCursor(c));
        }
        return list;
    }

    /** 软删除账户。 */
    public void delete(String clientId) {
        Account a = find(clientId);
        if (a == null) return;
        a.deleted = true;
        a.lastModified = System.currentTimeMillis();
        upsertLww(a);
    }

    /**
     * 重置为初次安装状态：软删除全部账户（含用户自建），再恢复内置默认账户。
     * 只影响账户表，流水记录保持不动。
     */
    public void resetToDefaults() {
        long now = System.currentTimeMillis();
        rw().execSQL("UPDATE accounts SET deleted=1, last_modified=? WHERE deleted=0",
                new Object[]{now});
        helper.restoreDefaults();
    }

    private ContentValues toValues(Account x) {
        ContentValues v = new ContentValues();
        v.put("client_id", x.clientId);
        v.put("name", x.name);
        v.put("type", x.type);
        v.put("icon", x.icon);
        v.put("initial_balance", x.initialBalance);
        v.put("sort_order", x.sortOrder);
        v.put("last_modified", x.lastModified);
        v.put("deleted", x.deleted ? 1 : 0);
        v.put("created_at", x.createdAt);
        v.put("updated_at", x.updatedAt);
        return v;
    }

    private Account fromCursor(Cursor c) {
        Account x = new Account();
        x.clientId = c.getString(c.getColumnIndexOrThrow("client_id"));
        x.name = c.getString(c.getColumnIndexOrThrow("name"));
        x.type = c.getString(c.getColumnIndexOrThrow("type"));
        x.icon = c.getString(c.getColumnIndexOrThrow("icon"));
        x.initialBalance = c.getDouble(c.getColumnIndexOrThrow("initial_balance"));
        x.sortOrder = c.getInt(c.getColumnIndexOrThrow("sort_order"));
        x.lastModified = c.getLong(c.getColumnIndexOrThrow("last_modified"));
        x.deleted = c.getInt(c.getColumnIndexOrThrow("deleted")) == 1;
        x.createdAt = Timestamps.toIso(c.getString(c.getColumnIndexOrThrow("created_at")));
        x.updatedAt = Timestamps.toIso(c.getString(c.getColumnIndexOrThrow("updated_at")));
        return x;
    }
}
