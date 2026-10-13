package com.personal.accounting.android.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.personal.accounting.android.model.Template;

import java.util.ArrayList;
import java.util.List;

public class TemplateRepo {
    private final DatabaseHelper helper;

    public TemplateRepo(Context context) {
        helper = DatabaseHelper.getInstance(context);
    }

    private SQLiteDatabase rw() {
        return helper.getWritableDatabase();
    }

    public List<Template> list() {
        List<Template> list = new ArrayList<>();
        try (Cursor c = rw().query("template", null, "deleted=0", null, null, null, "sort_order ASC")) {
            while (c.moveToNext()) list.add(fromCursor(c));
        }
        return list;
    }

    public boolean upsertLww(Template incoming) {
        Template existing = find(incoming.clientId);
        if (existing != null && existing.lastModified > incoming.lastModified) return false;
        rw().insertWithOnConflict("template", null, toValues(incoming), SQLiteDatabase.CONFLICT_REPLACE);
        return true;
    }

    public Template find(String clientId) {
        if (clientId == null) return null;
        try (Cursor c = rw().query("template", null, "client_id=?", new String[]{clientId}, null, null, null)) {
            return c.moveToFirst() ? fromCursor(c) : null;
        }
    }

    /** 自 last_modified > since 的全部模板（含已删除墓碑）。 */
    public List<Template> listModifiedSince(long since) {
        List<Template> list = new ArrayList<>();
        try (Cursor c = rw().query("template", null, "last_modified>?",
                new String[]{String.valueOf(since)}, null, null, "last_modified ASC")) {
            while (c.moveToNext()) list.add(fromCursor(c));
        }
        return list;
    }

    private ContentValues toValues(Template x) {
        ContentValues v = new ContentValues();
        v.put("client_id", x.clientId);
        v.put("name", x.name);
        v.put("amount", x.amount);
        v.put("type", x.type);
        v.put("category_client_id", x.categoryClientId);
        v.put("account_client_id", x.accountClientId);
        v.put("note", x.note);
        v.put("sort_order", x.sortOrder);
        v.put("last_modified", x.lastModified);
        v.put("deleted", x.deleted ? 1 : 0);
        return v;
    }

    private Template fromCursor(Cursor c) {
        Template x = new Template();
        x.clientId = c.getString(0);
        x.name = c.getString(1);
        x.amount = c.getDouble(2);
        x.type = c.getString(3);
        x.categoryClientId = c.getString(4);
        x.accountClientId = c.getString(5);
        x.note = c.getString(6);
        x.sortOrder = c.getInt(7);
        x.lastModified = c.getLong(8);
        x.deleted = c.getInt(9) == 1;
        return x;
    }
}
