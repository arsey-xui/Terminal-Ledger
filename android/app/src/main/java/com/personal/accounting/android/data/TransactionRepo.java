package com.personal.accounting.android.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.personal.accounting.android.model.Transaction;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TransactionRepo {

    private final DatabaseHelper helper;

    public TransactionRepo(Context context) {
        this.helper = DatabaseHelper.getInstance(context);
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
        if (clientId == null) return null;
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

    /**
     * 未删除的流水总条数。
     *
     * <p>给「一条流水都还没有」这类判断用：不依赖任何「首次使用」标记，
     * 用户记完第一笔就自然不再是这个状态，卸载重装、清数据也都能正确复位。</p>
     */
    public int countAll() {
        try (Cursor c = rw().rawQuery("SELECT COUNT(*) FROM transaction_record WHERE deleted=0", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
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

    /**
     * 按「年-月」聚合的收入/支出，用于跨月趋势。
     *
     * <p>一次 SQL 拿完整个区间（而不是逐月调用汇总），返回的 key 形如 {@code 2026-09}，
     * value 是 {@code [收入, 支出]}。</p>
     *
     * @param categoryClientId 传 null 表示不限分类
     */
    public Map<String, double[]> monthlyTotals(LocalDate from, LocalDate to, String categoryClientId) {
        StringBuilder sql = new StringBuilder(
                "SELECT substr(date,1,7) AS ym, type, COALESCE(SUM(amount),0)"
                        + " FROM transaction_record WHERE deleted=0 AND date>=? AND date<=?");
        List<String> args = new ArrayList<>();
        args.add(from.toString());
        args.add(to.toString());
        if (categoryClientId != null) {
            sql.append(" AND category_client_id=?");
            args.add(categoryClientId);
        }
        sql.append(" GROUP BY ym, type");

        Map<String, double[]> totals = new LinkedHashMap<>();
        try (Cursor c = rw().rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (c.moveToNext()) {
                String ym = c.getString(0);
                double sum = c.getDouble(2);
                double[] pair = totals.get(ym);
                if (pair == null) pair = new double[2];
                if ("INCOME".equals(c.getString(1))) pair[0] += sum;
                else pair[1] += sum;
                totals.put(ym, pair);
            }
        }
        return totals;
    }

    /** 某分类当月支出合计。 */
    public double categoryExpense(String categoryClientId, int year, int month) {
        // 未分类的流水不归属任何分类，直接返回 0（空值也不能进 selectionArgs）。
        if (categoryClientId == null) return 0;
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

    /** Daily expense totals for a date range — used by 30-day trend chart */
    public Map<String, Double> dailyExpenseBetween(String from, String to) {
        Map<String, Double> map = new LinkedHashMap<>();
        String sql = "SELECT date, SUM(amount) FROM transaction_record "
                   + "WHERE deleted=0 AND type='EXPENSE' AND date>=? AND date<=? GROUP BY date ORDER BY date";
        try (Cursor c = helper.getReadableDatabase().rawQuery(sql, new String[]{from, to})) {
            while (c.moveToNext()) {
                map.put(c.getString(0), c.getDouble(1));
            }
        }
        return map;
    }

    /** Daily expense totals for a whole year — used by heatmap */
    public Map<String, Double> dailyExpenseForYear(int year) {
        Map<String, Double> map = new LinkedHashMap<>();
        String y = String.valueOf(year);
        String sql = "SELECT date, SUM(amount) FROM transaction_record "
                   + "WHERE deleted=0 AND type='EXPENSE' AND substr(date,1,4)=? GROUP BY date ORDER BY date";
        try (Cursor c = helper.getReadableDatabase().rawQuery(sql, new String[]{y})) {
            while (c.moveToNext()) {
                map.put(c.getString(0), c.getDouble(1));
            }
        }
        return map;
    }

    /** Monthly expense totals grouped by category — single query replaces N+1 loop */
    public Map<String, Double> categoryExpenseMap(int year, int month) {
        Map<String, Double> map = new HashMap<>();
        String from = String.format("%04d-%02d-01", year, month);
        String to = String.format("%04d-%02d-31", year, month);
        String sql = "SELECT category_client_id, SUM(amount) FROM transaction_record "
                   + "WHERE deleted=0 AND type='EXPENSE' AND category_client_id IS NOT NULL "
                   + "AND date>=? AND date<=? GROUP BY category_client_id";
        try (Cursor c = helper.getReadableDatabase().rawQuery(sql, new String[]{from, to})) {
            while (c.moveToNext()) {
                map.put(c.getString(0), c.getDouble(1));
            }
        }
        return map;
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
        t.createdAt = Timestamps.toIso(c.getString(c.getColumnIndexOrThrow("created_at")));
        t.updatedAt = Timestamps.toIso(c.getString(c.getColumnIndexOrThrow("updated_at")));
        return t;
    }
}
