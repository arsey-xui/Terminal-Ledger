package com.personal.accounting.android.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.personal.accounting.android.model.Transaction;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class TransactionRepo {

    private final DatabaseHelper helper;

    public TransactionRepo(Context context) {
        this.helper = new DatabaseHelper(context);
    }

    private SQLiteDatabase rw() {
        return helper.getWritableDatabase();
    }

    public long insert(Transaction t) {
        return rw().insertWithOnConflict("transaction_record", null, toValues(t),
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** LWW 合并：插入或覆盖，仅当 incoming.lastModified >= 本地值时生效。返回是否写入。 */
    public boolean upsertLww(Transaction t) {
        Transaction existing = findByClientId(t.clientId);
        if (existing != null && existing.lastModified > t.lastModified) {
            return false; // 本地更新，保留
        }
        insert(t);
        return true;
    }

    public Transaction findByClientId(String clientId) {
        try (Cursor c = rw().query("transaction_record", null, "client_id=?", new String[]{clientId},
                null, null, null)) {
            if (c.moveToFirst()) return fromCursor(c);
            return null;
        }
    }

    public List<Transaction> listRecent(int limit) {
        List<Transaction> list = new ArrayList<>();
        try (Cursor c = rw().query("transaction_record", null, "deleted=0", null, null, null,
                "date DESC, last_modified DESC", String.valueOf(limit))) {
            while (c.moveToNext()) list.add(fromCursor(c));
        }
        return list;
    }

    /** 软删除流水：置 deleted=1 并刷新 last_modified，删除可同步为墓碑。 */
    public void delete(String clientId) {
        Transaction t = findByClientId(clientId);
        if (t == null) return;
        t.deleted = true;
        t.lastModified = System.currentTimeMillis();
        insert(t);
    }

    /** 增量：last_modified > since 的记录（配合同步水位）。 */
    public List<Transaction> listModifiedSince(long since) {
        List<Transaction> list = new ArrayList<>();
        try (Cursor c = rw().query("transaction_record", null, "last_modified>?", new String[]{String.valueOf(since)},
                null, null, "last_modified ASC")) {
            while (c.moveToNext()) list.add(fromCursor(c));
        }
        return list;
    }

    /** 日期区间 + 分类/账户/关键词组合搜索。 */
    public List<Transaction> search(LocalDate from, LocalDate to, String categoryClientId,
                                    String accountClientId, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT * FROM transaction_record WHERE deleted=0");
        List<String> args = new ArrayList<>();
        if (from != null) {
            sql.append(" AND date>=?");
            args.add(from.toString());
        }
        if (to != null) {
            sql.append(" AND date<=?");
            args.add(to.toString());
        }
        if (categoryClientId != null) {
            sql.append(" AND category_client_id=?");
            args.add(categoryClientId);
        }
        if (accountClientId != null) {
            sql.append(" AND account_client_id=?");
            args.add(accountClientId);
        }
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND (note LIKE ?) ");
            args.add("%" + keyword + "%");
        }
        sql.append(" ORDER BY date DESC");
        List<Transaction> list = new ArrayList<>();
        try (Cursor c = rw().rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (c.moveToNext()) list.add(fromCursor(c));
        }
        return list;
    }

    /** 月度统计：返回该月支出/收入/结余。 */
    public double[] monthSummary(int year, int month) {
        String dateFrom = LocalDate.of(year, month, 1).toString();
        String dateTo = LocalDate.of(year, month, 1).withDayOfMonth(
                LocalDate.of(year, month, 1).lengthOfMonth()).toString();
        double income = 0, expense = 0;
        try (Cursor c = rw().rawQuery(
                "SELECT COALESCE(SUM(amount),0) FROM transaction_record WHERE deleted=0 AND type=? AND date>=? AND date<=?",
                new String[]{"INCOME", dateFrom, dateTo})) {
            if (c.moveToFirst()) income = c.getDouble(0);
        }
        try (Cursor c = rw().rawQuery(
                "SELECT COALESCE(SUM(amount),0) FROM transaction_record WHERE deleted=0 AND type=? AND date>=? AND date<=?",
                new String[]{"EXPENSE", dateFrom, dateTo})) {
            if (c.moveToFirst()) expense = c.getDouble(0);
        }
        return new double[]{income, expense, income - expense};
    }

    /** 某分类当月支出合计。 */
    public double categoryExpense(String categoryClientId, int year, int month) {
        String dateFrom = LocalDate.of(year, month, 1).toString();
        String dateTo = LocalDate.of(year, month, 1).withDayOfMonth(
                LocalDate.of(year, month, 1).lengthOfMonth()).toString();
        try (Cursor c = rw().rawQuery(
                "SELECT COALESCE(SUM(amount),0) FROM transaction_record WHERE deleted=0 AND type='EXPENSE' AND category_client_id=? AND date>=? AND date<=?",
                new String[]{categoryClientId, dateFrom, dateTo})) {
            if (c.moveToFirst()) return c.getDouble(0);
        }
        return 0;
    }

    private ContentValues toValues(Transaction t) {
        ContentValues v = new ContentValues();
        v.put("client_id", t.clientId);
        v.put("date", t.date == null ? LocalDate.now().toString() : t.date.toString());
        v.put("amount", t.amount);
        v.put("type", t.type == null ? "EXPENSE" : t.type);
        v.put("category_client_id", t.categoryClientId);
        v.put("account_client_id", t.accountClientId);
        v.put("note", t.note);
        v.put("photo_path", t.photoPath);
        v.put("source", t.source);
        v.put("last_modified", t.lastModified);
        v.put("deleted", t.deleted ? 1 : 0);
        v.put("created_at", t.createdAt);
        v.put("updated_at", t.updatedAt);
        return v;
    }

    private Transaction fromCursor(Cursor c) {
        Transaction t = new Transaction();
        t.clientId = c.getString(c.getColumnIndexOrThrow("client_id"));
        t.date = LocalDate.parse(c.getString(c.getColumnIndexOrThrow("date")));
        t.amount = c.getDouble(c.getColumnIndexOrThrow("amount"));
        t.type = c.getString(c.getColumnIndexOrThrow("type"));
        t.categoryClientId = c.getString(c.getColumnIndexOrThrow("category_client_id"));
        t.accountClientId = c.getString(c.getColumnIndexOrThrow("account_client_id"));
        t.note = c.getString(c.getColumnIndexOrThrow("note"));
        t.photoPath = c.getString(c.getColumnIndexOrThrow("photo_path"));
        t.source = c.getString(c.getColumnIndexOrThrow("source"));
        t.lastModified = c.getLong(c.getColumnIndexOrThrow("last_modified"));
        t.deleted = c.getInt(c.getColumnIndexOrThrow("deleted")) == 1;
        t.createdAt = c.getString(c.getColumnIndexOrThrow("created_at"));
        t.updatedAt = c.getString(c.getColumnIndexOrThrow("updated_at"));
        return t;
    }
}
