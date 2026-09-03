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
        helper = new DatabaseHelper(context);
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
        x.clientId = c.getString(0);
        x.name = c.getString(1);
        x.type = c.getString(2);
        x.icon = c.getString(3);
        x.initialBalance = c.getDouble(4);
        x.sortOrder = c.getInt(5);
        x.lastModified = c.getLong(6);
        x.deleted = c.getInt(7) == 1;
        x.createdAt = c.getString(8);
        x.updatedAt = c.getString(9);
        return x;
    }
}
