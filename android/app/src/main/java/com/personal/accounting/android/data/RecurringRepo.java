package com.personal.accounting.android.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.personal.accounting.android.model.RecurringPending;
import com.personal.accounting.android.model.RecurringRule;
import com.personal.accounting.android.model.Transaction;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 周期记账仓储：规则的增删改（软删除、参与同步）+ 到期待确认项的生成与确认。
 *
 * <p>确认流程：待确认项本身带确定 clientId（{@code rr-<规则id前8位>-<yyyyMMdd>}），
 * 点确认时就用这个 id 写入一条流水 —— 即使两台设备都为同一期点了确认，
 * 同步时也是同一条流水（LWW 合并），不会重复入账。</p>
 */
public class RecurringRepo {

    private final DatabaseHelper helper;
    private final Context context;

    public RecurringRepo(Context context) {
        this.context = context.getApplicationContext();
        helper = DatabaseHelper.getInstance(context);
    }

    private SQLiteDatabase rw() {
        return helper.getWritableDatabase();
    }

    // ---------------- 规则 ----------------

    /** 所有启用中（未删除）的规则。 */
    public List<RecurringRule> list() {
        List<RecurringRule> list = new ArrayList<>();
        try (Cursor c = rw().query("recurring_rule", null, "deleted=0", null, null, null,
                "sort_order ASC, rowid ASC")) {
            while (c.moveToNext()) list.add(fromRule(c));
        }
        return list;
    }

    public RecurringRule find(String clientId) {
        if (clientId == null) return null;
        try (Cursor c = rw().query("recurring_rule", null, "client_id=?",
                new String[]{clientId}, null, null, null)) {
            return c.moveToFirst() ? fromRule(c) : null;
        }
    }

    /** 保存（新建或修改）规则，last_modified 自动刷新。 */
    public void save(RecurringRule rule) {
        if (rule.clientId == null || rule.clientId.isBlank()) {
            rule.clientId = UUID.randomUUID().toString();
        }
        rule.lastModified = System.currentTimeMillis();
        rw().insertWithOnConflict("recurring_rule", null, toValues(rule), SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** LWW 合并（同步用）。 */
    public boolean upsertLww(RecurringRule incoming) {
        RecurringRule existing = find(incoming.clientId);
        if (existing != null && existing.lastModified > incoming.lastModified) return false;
        rw().insertWithOnConflict("recurring_rule", null, toValues(incoming), SQLiteDatabase.CONFLICT_REPLACE);
        return true;
    }

    /** 软删除规则，并清掉它尚未确认的待确认项。 */
    public void delete(String clientId) {
        RecurringRule rule = find(clientId);
        if (rule == null) return;
        rule.deleted = true;
        rule.lastModified = System.currentTimeMillis();
        rw().insertWithOnConflict("recurring_rule", null, toValues(rule), SQLiteDatabase.CONFLICT_REPLACE);
        rw().delete("recurring_pending", "rule_client_id=? AND kind='PENDING'", new String[]{clientId});
    }

    /** 含删除墓碑的规则（同步用）。 */
    public List<RecurringRule> listModifiedSince(long since) {
        List<RecurringRule> list = new ArrayList<>();
        try (Cursor c = rw().query("recurring_rule", null, "last_modified>?",
                new String[]{String.valueOf(since)}, null, null, "last_modified ASC")) {
            while (c.moveToNext()) list.add(fromRule(c));
        }
        return list;
    }

    // ---------------- 待确认项 ----------------

    /**
     * 按规则推算到今天为止需要生成的待确认项并落库（幂等：已存在同 id 的项不重复生成）。
     *
     * @return 本次新生成的条数
     */
    public int materialize(LocalDate today) {
        int created = 0;
        for (RecurringRule rule : list()) {
            if (!rule.enabled) continue;
            List<LocalDate> dates = Recurrence.dueDates(rule, today);
            for (LocalDate date : dates) {
                String id = Recurrence.occurrenceId(rule, date);
                RecurringPending existing = findPending(id);
                if (existing != null) {
                    // 已生成（待确认或已确认）：只推进规则的生成水位
                    rule.lastGeneratedDate = Recurrence.format(date);
                    continue;
                }
                RecurringPending p = new RecurringPending();
                p.clientId = id;
                p.ruleClientId = rule.clientId;
                p.date = date;
                p.amount = rule.amount;
                p.type = rule.type;
                p.categoryClientId = rule.categoryClientId;
                p.accountClientId = rule.accountClientId;
                p.note = rule.note;
                p.kind = "PENDING";
                p.lastModified = System.currentTimeMillis();
                rw().insertWithOnConflict("recurring_pending", null, toValues(p),
                        SQLiteDatabase.CONFLICT_REPLACE);
                rule.lastGeneratedDate = Recurrence.format(p.date);
                created++;
            }
            // 推进水位后回写规则（沿用原 last_modified，生成水位变化不算用户修改）
            if (dates.size() > 0) {
                rw().insertWithOnConflict("recurring_rule", null, toValues(rule),
                        SQLiteDatabase.CONFLICT_REPLACE);
            }
        }
        return created;
    }

    public List<RecurringPending> listPending() {
        List<RecurringPending> list = new ArrayList<>();
        try (Cursor c = rw().query("recurring_pending", null, "kind='PENDING'", null, null, null,
                "date ASC, rowid ASC")) {
            while (c.moveToNext()) list.add(fromPending(c));
        }
        return list;
    }

    public int pendingCount() {
        try (Cursor c = rw().rawQuery("SELECT COUNT(*) FROM recurring_pending WHERE kind='PENDING'", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public RecurringPending findPending(String clientId) {
        if (clientId == null) return null;
        try (Cursor c = rw().query("recurring_pending", null, "client_id=?",
                new String[]{clientId}, null, null, null)) {
            return c.moveToFirst() ? fromPending(c) : null;
        }
    }

    /**
     * 确认待确认项：写入一条流水并把该项标记为 ACCEPTED。
     *
     * @param amount 可在确认时修改金额（续费涨价时用），传 null 用原金额
     * @return 生成的流水 clientId
     */
    public String confirm(String pendingClientId, Double amount) {
        RecurringPending p = findPending(pendingClientId);
        if (p == null) return null;
        long now = System.currentTimeMillis();
        Transaction t = new Transaction();
        t.clientId = p.clientId;
        t.date = p.date;
        t.amount = amount == null ? p.amount : amount;
        t.type = p.type;
        t.categoryClientId = p.categoryClientId;
        t.accountClientId = p.accountClientId;
        t.note = p.note == null || p.note.isBlank() ? "周期记账" : p.note;
        t.source = "RECURRING";
        t.lastModified = now;
        t.deleted = false;
        new TransactionRepo(context).upsertLww(t);
        markPending(p.clientId, "ACCEPTED", now);
        return p.clientId;
    }

    public void skip(String pendingClientId) {
        markPending(pendingClientId, "SKIPPED", System.currentTimeMillis());
    }

    private void markPending(String clientId, String kind, long now) {
        ContentValues v = new ContentValues();
        v.put("kind", kind);
        v.put("last_modified", now);
        rw().update("recurring_pending", v, "client_id=?", new String[]{clientId});
    }

    // ---------------- 映射 ----------------

    private ContentValues toValues(RecurringRule r) {
        ContentValues v = new ContentValues();
        v.put("client_id", r.clientId);
        v.put("name", r.name);
        v.put("amount", r.amount);
        v.put("txn_type", r.type);
        v.put("category_client_id", r.categoryClientId);
        v.put("account_client_id", r.accountClientId);
        v.put("note", r.note);
        v.put("interval_unit", r.intervalUnit == null ? "MONTH" : r.intervalUnit);
        v.put("interval_count", Math.max(1, r.intervalCount));
        v.put("anchor_date", r.anchorDate);
        v.put("last_generated_date", r.lastGeneratedDate);
        v.put("enabled", r.enabled ? 1 : 0);
        v.put("sort_order", r.sortOrder);
        v.put("last_modified", r.lastModified);
        v.put("deleted", r.deleted ? 1 : 0);
        return v;
    }

    private RecurringRule fromRule(Cursor c) {
        RecurringRule r = new RecurringRule();
        r.clientId = c.getString(c.getColumnIndexOrThrow("client_id"));
        r.name = c.getString(c.getColumnIndexOrThrow("name"));
        r.amount = c.getDouble(c.getColumnIndexOrThrow("amount"));
        r.type = c.getString(c.getColumnIndexOrThrow("txn_type"));
        r.categoryClientId = c.getString(c.getColumnIndexOrThrow("category_client_id"));
        r.accountClientId = c.getString(c.getColumnIndexOrThrow("account_client_id"));
        r.note = c.getString(c.getColumnIndexOrThrow("note"));
        r.intervalUnit = c.getString(c.getColumnIndexOrThrow("interval_unit"));
        r.intervalCount = c.getInt(c.getColumnIndexOrThrow("interval_count"));
        r.anchorDate = c.getString(c.getColumnIndexOrThrow("anchor_date"));
        r.lastGeneratedDate = c.getString(c.getColumnIndexOrThrow("last_generated_date"));
        r.enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) == 1;
        r.sortOrder = c.getInt(c.getColumnIndexOrThrow("sort_order"));
        r.lastModified = c.getLong(c.getColumnIndexOrThrow("last_modified"));
        r.deleted = c.getInt(c.getColumnIndexOrThrow("deleted")) == 1;
        return r;
    }

    private ContentValues toValues(RecurringPending p) {
        ContentValues v = new ContentValues();
        v.put("client_id", p.clientId);
        v.put("rule_client_id", p.ruleClientId);
        v.put("date", Recurrence.format(p.date));
        v.put("amount", p.amount);
        v.put("txn_type", p.type);
        v.put("category_client_id", p.categoryClientId);
        v.put("account_client_id", p.accountClientId);
        v.put("note", p.note);
        v.put("kind", p.kind);
        v.put("last_modified", p.lastModified);
        return v;
    }

    private RecurringPending fromPending(Cursor c) {
        RecurringPending p = new RecurringPending();
        p.clientId = c.getString(c.getColumnIndexOrThrow("client_id"));
        p.ruleClientId = c.getString(c.getColumnIndexOrThrow("rule_client_id"));
        p.date = Recurrence.parse(c.getString(c.getColumnIndexOrThrow("date")));
        p.amount = c.getDouble(c.getColumnIndexOrThrow("amount"));
        p.type = c.getString(c.getColumnIndexOrThrow("txn_type"));
        p.categoryClientId = c.getString(c.getColumnIndexOrThrow("category_client_id"));
        p.accountClientId = c.getString(c.getColumnIndexOrThrow("account_client_id"));
        p.note = c.getString(c.getColumnIndexOrThrow("note"));
        p.kind = c.getString(c.getColumnIndexOrThrow("kind"));
        p.lastModified = c.getLong(c.getColumnIndexOrThrow("last_modified"));
        return p;
    }
}
