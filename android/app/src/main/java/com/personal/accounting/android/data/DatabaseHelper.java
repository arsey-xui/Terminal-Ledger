package com.personal.accounting.android.data;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 本地全量缓存数据库（SQLite）。
 *
 * <p>表结构与云端 MySQL 保持同构（字段名 camelCase 转 snake_case，语义一致），
 * 由 {@link Repository} 层负责读写与 LWW 合并。</p>
 */
public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "accounting.db";
    private static final int DB_VERSION = 1;

    public DatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        // 首次使用自动写入内置默认分类/账户。
        seedDefaults(db);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE accounts (" +
                "client_id TEXT PRIMARY KEY, name TEXT NOT NULL, type TEXT, icon TEXT," +
                "initial_balance REAL DEFAULT 0, sort_order INTEGER DEFAULT 0," +
                "last_modified INTEGER NOT NULL, deleted INTEGER DEFAULT 0, created_at TEXT, updated_at TEXT)");

        db.execSQL("CREATE TABLE category (" +
                "client_id TEXT PRIMARY KEY, name TEXT NOT NULL, type TEXT NOT NULL, icon TEXT," +
                "sort_order INTEGER DEFAULT 0, last_modified INTEGER NOT NULL," +
                "deleted INTEGER DEFAULT 0, created_at TEXT, updated_at TEXT)");

        db.execSQL("CREATE TABLE transaction_record (" +
                "client_id TEXT PRIMARY KEY, date TEXT NOT NULL, amount REAL NOT NULL, type TEXT NOT NULL," +
                "category_client_id TEXT, account_client_id TEXT, note TEXT, photo_path TEXT, source TEXT," +
                "last_modified INTEGER NOT NULL, deleted INTEGER DEFAULT 0, created_at TEXT, updated_at TEXT)");
        db.execSQL("CREATE INDEX idx_txn_date ON transaction_record(date)");
        db.execSQL("CREATE INDEX idx_txn_lm ON transaction_record(last_modified)");

        db.execSQL("CREATE TABLE budget (" +
                "client_id TEXT PRIMARY KEY, category_client_id TEXT NOT NULL, budget_year INTEGER NOT NULL," +
                "budget_month INTEGER NOT NULL, amount_limit REAL NOT NULL, last_modified INTEGER NOT NULL," +
                "deleted INTEGER DEFAULT 0, created_at TEXT, updated_at TEXT, " +
                "UNIQUE(category_client_id, budget_year, budget_month))");

        db.execSQL("CREATE TABLE template (" +
                "client_id TEXT PRIMARY KEY, name TEXT NOT NULL, amount REAL, type TEXT," +
                "category_client_id TEXT, account_client_id TEXT, note TEXT, sort_order INTEGER DEFAULT 0," +
                "last_modified INTEGER NOT NULL, deleted INTEGER DEFAULT 0, created_at TEXT, updated_at TEXT)");

        db.execSQL("CREATE TABLE sync_state (" +
                "device_id TEXT PRIMARY KEY, last_sync INTEGER NOT NULL DEFAULT 0)");
    }

    /**
     * 首次使用（或旧版本重置后只剩墓碑、UI 看不到任何分类/账户）时补回内置默认数据：
     * 支出分类 餐饮/交通/购物/住房/通讯/医疗/教育/娱乐/人情/其他，
     * 收入分类 工资/副业/红包/其他收入，账户 现金/储蓄卡/信用卡/微信/支付宝。
     *
     * <p>只在「未删除的某类分类/账户一个都没有」时补，所以用户自己删掉个别默认分类后，
     * 不会被反复还原。</p>
     */
    private void seedDefaults(SQLiteDatabase db) {
        if (!hasVisibleType(db, "category", "EXPENSE")) insertDefaultExpenseCategories(db);
        if (!hasVisibleType(db, "category", "INCOME")) insertDefaultIncomeCategories(db);
        if (!hasVisible(db, "accounts")) insertDefaultAccounts(db);
    }

    /**
     * 恢复内置默认分类与账户（不重置任何流水/预算）。
     * 供「管理分类 / 管理账户」的重置按钮使用。
     *
     * <p>关键点：默认数据的 clientId 是固定的，若该行只是被**软删除**（重置按钮第一步
     * 刚做过这件事），普通 INSERT 会撞主键失败、被 SQLite 静默忽略，结果默认分类/账户
     * 一个都不回来。所以这里用 INSERT + ON CONFLICT DO UPDATE：已存在则把 deleted 置 0、
     * 名称/图标/类型修正、last_modified 刷新为当前时间（LWW 同步里恢复动作胜过其它设备
     * 上的旧删除墓碑），created_at 保持不变。用户自建分类/账户不受影响，仍是软删除状态。</p>
     */
    public void restoreDefaults() {
        SQLiteDatabase db = getWritableDatabase();
        insertDefaultExpenseCategories(db);
        insertDefaultIncomeCategories(db);
        insertDefaultAccounts(db);
    }

    private void insertDefaultExpenseCategories(SQLiteDatabase db) {
        insertCategory(db, "cat-food",       "餐饮", "🍜", "EXPENSE");
        insertCategory(db, "cat-traffic",    "交通", "🚌", "EXPENSE");
        insertCategory(db, "cat-shopping",   "购物", "🛍️", "EXPENSE");
        insertCategory(db, "cat-housing",    "住房", "🏠", "EXPENSE");
        insertCategory(db, "cat-comms",      "通讯", "📱", "EXPENSE");
        insertCategory(db, "cat-medical",    "医疗", "💊", "EXPENSE");
        insertCategory(db, "cat-edu",        "教育", "📚", "EXPENSE");
        insertCategory(db, "cat-ent",        "娱乐", "🎮", "EXPENSE");
        insertCategory(db, "cat-gift",       "人情", "🎁", "EXPENSE");
        insertCategory(db, "cat-other",      "其他", "📦", "EXPENSE");
    }

    private void insertDefaultIncomeCategories(SQLiteDatabase db) {
        insertCategory(db, "cat-salary",      "工资",     "💼", "INCOME");
        insertCategory(db, "cat-side",        "副业",     "🧾", "INCOME");
        insertCategory(db, "cat-redpacket",   "红包",     "🧧", "INCOME");
        insertCategory(db, "cat-other-income","其他收入", "💰", "INCOME");
    }

    private void insertDefaultAccounts(SQLiteDatabase db) {
        insertAccount(db, "acc-cash",    "现金",   "CASH",       "💵");
        insertAccount(db, "acc-savings", "储蓄卡", "DEBIT_CARD", "🏦");
        insertAccount(db, "acc-credit",  "信用卡", "CREDIT_CARD","💳");
        insertAccount(db, "acc-wechat",  "微信",   "WECHAT",     "💚");
        insertAccount(db, "acc-alipay",  "支付宝", "ALIPAY",     "💙");
    }

    private void insertCategory(SQLiteDatabase db, String id, String name, String icon, String type) {
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("client_id", id);
        v.put("name", name);
        v.put("icon", icon);
        v.put("type", type);
        v.put("created_at", String.valueOf(now));
        upsertDefault(db, "category", v, now);
    }

    private void insertAccount(SQLiteDatabase db, String id, String name, String type, String icon) {
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("client_id", id);
        v.put("name", name);
        v.put("icon", icon);
        v.put("type", type);
        v.put("created_at", String.valueOf(now));
        upsertDefault(db, "accounts", v, now);
    }

    /**
     * 写入一条默认数据：不存在则插入（deleted=0），已存在则**复活**该行
     * （deleted=0、刷新 last_modified、修正名称/图标/类型），created_at 保留原值。
     * 用 ON CONFLICT DO UPDATE 保证「重置」不会被主键冲突静默吞掉。
     */
    private void upsertDefault(SQLiteDatabase db, String table, ContentValues v, long now) {
        StringBuilder cols = new StringBuilder();
        List<Object> args = new ArrayList<>();
        int count = 0;
        for (Map.Entry<String, Object> e : v.valueSet()) {
            if (count++ > 0) cols.append(", ");
            cols.append(e.getKey());
            args.add(e.getValue());
        }
        cols.append(", deleted, last_modified");
        args.add(0);
        args.add(now);

        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) placeholders.append(", ");
            placeholders.append('?');
        }

        db.execSQL("INSERT INTO " + table + " (" + cols + ") VALUES (" + placeholders + ") " +
                        "ON CONFLICT(client_id) DO UPDATE SET " +
                        "name=excluded.name, icon=excluded.icon, type=excluded.type, " +
                        "deleted=0, last_modified=excluded.last_modified",
                args.toArray());
    }

    /** 该表是否还有「未被软删除」的行。 */
    private boolean hasVisible(SQLiteDatabase db, String table) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + table + " WHERE deleted=0", null)) {
            return c.moveToFirst() && c.getInt(0) > 0;
        }
    }

    /** 该表是否还有「未被软删除」的指定类型行（分类分支出/收入）。 */
    private boolean hasVisibleType(SQLiteDatabase db, String table, String type) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + table + " WHERE deleted=0 AND type=?",
                new String[]{type})) {
            return c.moveToFirst() && c.getInt(0) > 0;
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 开发期简单重建；生产建议增量迁移。
        onDrop(db);
        onCreate(db);
    }

    private void onDrop(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS accounts");
        db.execSQL("DROP TABLE IF EXISTS category");
        db.execSQL("DROP TABLE IF EXISTS transaction_record");
        db.execSQL("DROP TABLE IF EXISTS budget");
        db.execSQL("DROP TABLE IF EXISTS template");
        db.execSQL("DROP TABLE IF EXISTS sync_state");
    }
}
