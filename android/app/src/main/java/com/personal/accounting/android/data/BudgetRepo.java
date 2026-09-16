package com.personal.accounting.android.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.personal.accounting.android.model.Budget;

import java.util.ArrayList;
import java.util.List;

public class BudgetRepo {
    private final DatabaseHelper helper;

    public BudgetRepo(Context context) {
        helper = new DatabaseHelper(context);
    }

    private SQLiteDatabase rw() {
        return helper.getWritableDatabase();
    }

    public List<Budget> listForMonth(int year, int month) {
        List<Budget> list = new ArrayList<>();
        try (Cursor c = rw().query("budget", null, "deleted=0 AND budget_year=? AND budget_month=?",
                new String[]{String.valueOf(year), String.valueOf(month)}, null, null, null)) {
            while (c.moveToNext()) list.add(fromCursor(c));
        }
        return list;
    }

    public List<Budget> all() {
        List<Budget> list = new ArrayList<>();
        try (Cursor c = rw().query("budget", null, "deleted=0", null, null, null, null)) {
            while (c.moveToNext()) list.add(fromCursor(c));
        }
        return list;
    }

    public boolean upsertLww(Budget incoming) {
        Budget existing = findByKey(incoming.categoryClientId, incoming.year, incoming.month);
        if (existing != null && existing.lastModified > incoming.lastModified) return false;
        rw().insertWithOnConflict("budget", null, toValues(incoming), SQLiteDatabase.CONFLICT_REPLACE);
        return true;
    }

    public Budget findByKey(String categoryClientId, int year, int month) {
        try (Cursor c = rw().query("budget", null,
                "category_client_id=? AND budget_year=? AND budget_month=?",
                new String[]{categoryClientId, String.valueOf(year), String.valueOf(month)}, null, null, null)) {
            return c.moveToFirst() ? fromCursor(c) : null;
        }
    }

    /**
     * 仅返回「未删除」的预算（deleted=0）。
     * UI 在判断某分类是否设置了预算上限时使用，避免软删除墓碑仍被当成“已设置”。
     * LWW 合并 {@link #upsertLww} 仍用 {@link #findByKey}（需要能看见墓碑以比较 lastModified）。
     */
    public Budget findActiveByKey(String categoryClientId, int year, int month) {
        try (Cursor c = rw().query("budget", null,
                "category_client_id=? AND budget_year=? AND budget_month=? AND deleted=0",
                new String[]{categoryClientId, String.valueOf(year), String.valueOf(month)}, null, null, null)) {
            return c.moveToFirst() ? fromCursor(c) : null;
        }
    }

    /** 自 last_modified > since 的全部预算（含已删除墓碑）。 */
    public List<Budget> listModifiedSince(long since) {
        List<Budget> list = new ArrayList<>();
        try (Cursor c = rw().query("budget", null, "last_modified>?",
                new String[]{String.valueOf(since)}, null, null, "last_modified ASC")) {
            while (c.moveToNext()) list.add(fromCursor(c));
        }
        return list;
    }

    /** 清空全部预算上限（软删除），回到初次安装时「所有分类都未设置上限」的状态。 */
    public void resetToDefaults() {
        rw().execSQL("UPDATE budget SET deleted=1, last_modified=? WHERE deleted=0",
                new Object[]{System.currentTimeMillis()});
    }

    private ContentValues toValues(Budget x) {
        ContentValues v = new ContentValues();
        v.put("client_id", x.clientId);
        v.put("category_client_id", x.categoryClientId);
        v.put("budget_year", x.year);
        v.put("budget_month", x.month);
        v.put("amount_limit", x.amountLimit);
        v.put("last_modified", x.lastModified);
        v.put("deleted", x.deleted ? 1 : 0);
        return v;
    }

    private Budget fromCursor(Cursor c) {
        Budget x = new Budget();
        x.clientId = c.getString(0);
        x.categoryClientId = c.getString(1);
        x.year = c.getInt(2);
        x.month = c.getInt(3);
        x.amountLimit = c.getDouble(4);
        x.lastModified = c.getLong(5);
        x.deleted = c.getInt(6) == 1;
        return x;
    }
}
