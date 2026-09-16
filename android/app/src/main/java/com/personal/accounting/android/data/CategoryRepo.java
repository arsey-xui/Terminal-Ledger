package com.personal.accounting.android.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.personal.accounting.android.model.Category;

import java.util.ArrayList;
import java.util.List;

public class CategoryRepo {
    private final DatabaseHelper helper;

    public CategoryRepo(Context context) {
        helper = new DatabaseHelper(context);
    }

    private SQLiteDatabase rw() {
        return helper.getWritableDatabase();
    }

    public List<Category> listExpense() {
        return list("EXPENSE");
    }

    public List<Category> listIncome() {
        return list("INCOME");
    }

    /** 所有未删除分类（含支出与收入）。 */
    public List<Category> all() {
        List<Category> list = new ArrayList<>();
        try (Cursor c = rw().query("category", null, "deleted=0", null, null, null, "sort_order ASC")) {
            while (c.moveToNext()) list.add(fromCursor(c));
        }
        return list;
    }

    /**
     * 所有未删除分类，支出整体排在收入之前；组内沿用 sort_order，同序时按写入先后（rowid）排列。
     * 用于「管理分类」列表，保证（支出）分类都在（收入）分类上方。
     */
    public List<Category> allExpenseFirst() {
        List<Category> list = new ArrayList<>();
        try (Cursor c = rw().query("category", null, "deleted=0", null, null, null,
                "CASE type WHEN 'EXPENSE' THEN 0 ELSE 1 END ASC, sort_order ASC, rowid ASC")) {
            while (c.moveToNext()) list.add(fromCursor(c));
        }
        return list;
    }

    private List<Category> list(String type) {
        List<Category> list = new ArrayList<>();
        try (Cursor c = rw().query("category", null, "deleted=0 AND type=?", new String[]{type},
                null, null, "sort_order ASC")) {
            while (c.moveToNext()) list.add(fromCursor(c));
        }
        return list;
    }

    /** LWW 合并 */
    public boolean upsertLww(Category incoming) {
        Category existing = find(incoming.clientId);
        if (existing != null && existing.lastModified > incoming.lastModified) return false;
        rw().insertWithOnConflict("category", null, toValues(incoming), SQLiteDatabase.CONFLICT_REPLACE);
        return true;
    }

    public Category find(String clientId) {
        try (Cursor c = rw().query("category", null, "client_id=?", new String[]{clientId}, null, null, null)) {
            return c.moveToFirst() ? fromCursor(c) : null;
        }
    }

    /** 仅返回「未删除」的分类（不论支出/收入），用于 UI 解析分类名；避免查到已被软删除的墓碑。 */
    public Category findActive(String clientId) {
        try (Cursor c = rw().query("category", null, "client_id=? AND deleted=0",
                new String[]{clientId}, null, null, null)) {
            return c.moveToFirst() ? fromCursor(c) : null;
        }
    }

    /** 自 last_modified > since 的全部分类（含已删除墓碑，用于同步删除）。 */
    public List<Category> listModifiedSince(long since) {
        List<Category> list = new ArrayList<>();
        try (Cursor c = rw().query("category", null, "last_modified>?",
                new String[]{String.valueOf(since)}, null, null, "last_modified ASC")) {
            while (c.moveToNext()) list.add(fromCursor(c));
        }
        return list;
    }

    /** 软删除分类。 */
    public void delete(String clientId) {
        Category c = find(clientId);
        if (c == null) return;
        c.deleted = true;
        c.lastModified = System.currentTimeMillis();
        upsertLww(c);
    }

    /**
     * 重置为初次安装状态：软删除全部分类（含用户自建），再恢复内置默认分类。
     * 只影响分类表，流水记录保持不动。
     */
    public void resetToDefaults() {
        long now = System.currentTimeMillis();
        rw().execSQL("UPDATE category SET deleted=1, last_modified=? WHERE deleted=0",
                new Object[]{now});
        helper.restoreDefaults();
    }

    private ContentValues toValues(Category x) {
        ContentValues v = new ContentValues();
        v.put("client_id", x.clientId);
        v.put("name", x.name);
        v.put("type", x.type);
        v.put("icon", x.icon);
        v.put("sort_order", x.sortOrder);
        v.put("last_modified", x.lastModified);
        v.put("deleted", x.deleted ? 1 : 0);
        v.put("created_at", x.createdAt);
        v.put("updated_at", x.updatedAt);
        return v;
    }

    private Category fromCursor(Cursor c) {
        Category x = new Category();
        x.clientId = c.getString(0);
        x.name = c.getString(1);
        x.type = c.getString(2);
        x.icon = c.getString(3);
        x.sortOrder = c.getInt(4);
        x.lastModified = c.getLong(5);
        x.deleted = c.getInt(6) == 1;
        x.createdAt = c.getString(7);
        x.updatedAt = c.getString(8);
        return x;
    }
}
