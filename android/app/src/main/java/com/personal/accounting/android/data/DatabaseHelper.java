package com.personal.accounting.android.data;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

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
        // 首次使用自动写入内置默认分类/账户；仅在对应表为空时插入，避免重复。
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
     * 内置默认数据（与需求一致）：分类（餐饮、交通…其他）与账户（现金、储蓄卡…支付宝）。
     * 表为空才写入，因此对“升级已有安装”也生效，且不会覆盖用户数据。
     */
    private void seedDefaults(SQLiteDatabase db) {
        if (isEmpty(db, "category")) {
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
        if (!hasType(db, "category", "INCOME")) {
            insertCategory(db, "cat-salary",      "工资",     "💼", "INCOME");
            insertCategory(db, "cat-side",        "副业",     "🧾", "INCOME");
            insertCategory(db, "cat-redpacket",   "红包",     "🧧", "INCOME");
            insertCategory(db, "cat-other-income","其他收入", "💰", "INCOME");
        }
        if (isEmpty(db, "accounts")) {
            insertAccount(db, "acc-cash",    "现金",   "CASH",       "💵");
            insertAccount(db, "acc-savings", "储蓄卡", "DEBIT_CARD", "🏦");
            insertAccount(db, "acc-credit",  "信用卡", "CREDIT_CARD","💳");
            insertAccount(db, "acc-wechat",  "微信",   "WECHAT",     "💚");
            insertAccount(db, "acc-alipay",  "支付宝", "ALIPAY",     "💙");
        }
    }

    private void insertCategory(SQLiteDatabase db, String id, String name, String icon, String type) {
        ContentValues v = new ContentValues();
        v.put("client_id", id);
        v.put("name", name);
        v.put("type", type);
        v.put("icon", icon);
        v.put("deleted", 0);
        v.put("last_modified", System.currentTimeMillis());
        db.insert("category", null, v);
    }

    private void insertAccount(SQLiteDatabase db, String id, String name, String type, String icon) {
        ContentValues v = new ContentValues();
        v.put("client_id", id);
        v.put("name", name);
        v.put("type", type);
        v.put("icon", icon);
        v.put("deleted", 0);
        v.put("last_modified", System.currentTimeMillis());
        db.insert("accounts", null, v);
    }

    private boolean isEmpty(SQLiteDatabase db, String table) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + table, null)) {
            return c.moveToFirst() && c.getInt(0) == 0;
        }
    }

    private boolean hasType(SQLiteDatabase db, String table, String type) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + table + " WHERE type=?",
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
