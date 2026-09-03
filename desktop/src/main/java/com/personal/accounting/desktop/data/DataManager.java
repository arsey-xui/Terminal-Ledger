package com.personal.accounting.desktop.data;

import com.personal.accounting.desktop.model.Account;
import com.personal.accounting.desktop.model.Budget;
import com.personal.accounting.desktop.model.Category;
import com.personal.accounting.desktop.model.Template;
import com.personal.accounting.desktop.model.Transaction;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 桌面端本地缓存（H2 嵌入式，文件模式）。表结构与云端 MySQL 保持同构。
 * 当前使用 JDBC 直写，便于阅读与替换为 JPA。
 */
public class DataManager implements AutoCloseable {

    private final Connection conn;

    public DataManager(Path dbFile) {
        try {
            conn = DriverManager.getConnection("jdbc:h2:" + dbFile.toAbsolutePath() + ";DB_CLOSE_DELAY=-1");
            initSchema();
            seedDefaults();
        } catch (Exception e) {
            throw new RuntimeException("初始化本地数据库失败", e);
        }
    }

    private void initSchema() throws Exception {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS accounts (client_id VARCHAR(36) PRIMARY KEY, name VARCHAR(64) NOT NULL, type VARCHAR(32), icon VARCHAR(64), initial_balance DECIMAL(19,2) DEFAULT 0, sort_order INT DEFAULT 0, last_modified BIGINT NOT NULL, deleted INT DEFAULT 0, created_at VARCHAR(30), updated_at VARCHAR(30))");
            s.execute("CREATE TABLE IF NOT EXISTS category (client_id VARCHAR(36) PRIMARY KEY, name VARCHAR(64) NOT NULL, type VARCHAR(16) NOT NULL, icon VARCHAR(64), sort_order INT DEFAULT 0, last_modified BIGINT NOT NULL, deleted INT DEFAULT 0, created_at VARCHAR(30), updated_at VARCHAR(30))");
            s.execute("CREATE TABLE IF NOT EXISTS transaction_record (client_id VARCHAR(36) PRIMARY KEY, date DATE NOT NULL, amount DECIMAL(19,2) NOT NULL, type VARCHAR(16) NOT NULL, category_client_id VARCHAR(36), account_client_id VARCHAR(36), note VARCHAR(512), photo_path VARCHAR(1024), source VARCHAR(32), last_modified BIGINT NOT NULL, deleted INT DEFAULT 0, created_at VARCHAR(30), updated_at VARCHAR(30))");
            s.execute("CREATE INDEX IF NOT EXISTS idx_txn_date ON transaction_record(date)");
            s.execute("CREATE TABLE IF NOT EXISTS budget (client_id VARCHAR(36) PRIMARY KEY, category_client_id VARCHAR(36) NOT NULL, budget_year INT NOT NULL, budget_month INT NOT NULL, amount_limit DECIMAL(19,2) NOT NULL, last_modified BIGINT NOT NULL, deleted INT DEFAULT 0, created_at VARCHAR(30), updated_at VARCHAR(30), CONSTRAINT uk_budget UNIQUE(category_client_id, budget_year, budget_month))");
            s.execute("CREATE TABLE IF NOT EXISTS template (client_id VARCHAR(36) PRIMARY KEY, name VARCHAR(64) NOT NULL, amount DECIMAL(19,2), type VARCHAR(16), category_client_id VARCHAR(36), account_client_id VARCHAR(36), note VARCHAR(512), sort_order INT DEFAULT 0, last_modified BIGINT NOT NULL, deleted INT DEFAULT 0, created_at VARCHAR(30), updated_at VARCHAR(30))");
            s.execute("CREATE TABLE IF NOT EXISTS sync_state (device_id VARCHAR(36) PRIMARY KEY, last_sync BIGINT NOT NULL DEFAULT 0)");
        }
    }

    /** 首次使用写入内置默认分类/账户（与移动端相同的 clientId，保证跨端一致、不产生重复）。 */
    private void seedDefaults() throws Exception {
        if (isEmpty("category")) {
            seedCategory("cat-food", "餐饮", "🍜", "EXPENSE");
            seedCategory("cat-traffic", "交通", "🚌", "EXPENSE");
            seedCategory("cat-shopping", "购物", "🛍️", "EXPENSE");
            seedCategory("cat-housing", "住房", "🏠", "EXPENSE");
            seedCategory("cat-comms", "通讯", "📱", "EXPENSE");
            seedCategory("cat-medical", "医疗", "💊", "EXPENSE");
            seedCategory("cat-edu", "教育", "📚", "EXPENSE");
            seedCategory("cat-ent", "娱乐", "🎮", "EXPENSE");
            seedCategory("cat-gift", "人情", "🎁", "EXPENSE");
            seedCategory("cat-other", "其他", "📦", "EXPENSE");
        }
        if (!hasType("INCOME")) {
            seedCategory("cat-salary", "工资", "💼", "INCOME");
            seedCategory("cat-side", "副业", "🧾", "INCOME");
            seedCategory("cat-redpacket", "红包", "🧧", "INCOME");
            seedCategory("cat-other-income", "其他收入", "💰", "INCOME");
        }
        if (isEmpty("accounts")) {
            seedAccount("acc-cash", "现金", "CASH", "💵");
            seedAccount("acc-savings", "储蓄卡", "DEBIT_CARD", "🏦");
            seedAccount("acc-credit", "信用卡", "CREDIT_CARD", "💳");
            seedAccount("acc-wechat", "微信", "WECHAT", "💚");
            seedAccount("acc-alipay", "支付宝", "ALIPAY", "💙");
        }
    }

    private boolean isEmpty(String table) throws Exception {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1) == 0;
        }
    }

    private boolean hasType(String type) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM category WHERE type=?")) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private void seedCategory(String id, String name, String icon, String type) throws Exception {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO category (client_id,name,type,icon,sort_order,last_modified,deleted,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setString(3, type);
            ps.setString(4, icon);
            ps.setInt(5, 0);
            ps.setLong(6, now);
            ps.setInt(7, 0);
            ps.setString(8, null);
            ps.setString(9, null);
            ps.executeUpdate();
        }
    }

    private void seedAccount(String id, String name, String type, String icon) throws Exception {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO accounts (client_id,name,type,icon,initial_balance,sort_order,last_modified,deleted,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setString(3, type);
            ps.setString(4, icon);
            ps.setBigDecimal(5, BigDecimal.ZERO);
            ps.setInt(6, 0);
            ps.setLong(7, now);
            ps.setInt(8, 0);
            ps.setString(9, null);
            ps.setString(10, null);
            ps.executeUpdate();
        }
    }

    // ---------------- Account ----------------
    public void upsertAccount(Account a) {
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO accounts (client_id,name,type,icon,initial_balance,sort_order,last_modified,deleted,created_at,updated_at) KEY(client_id) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, a.clientId);
            ps.setString(2, a.name);
            ps.setString(3, a.type);
            ps.setString(4, a.icon);
            ps.setBigDecimal(5, a.initialBalance == null ? BigDecimal.ZERO : a.initialBalance);
            ps.setInt(6, a.sortOrder);
            ps.setLong(7, a.lastModified);
            ps.setInt(8, a.deleted ? 1 : 0);
            ps.setString(9, a.createdAt);
            ps.setString(10, a.updatedAt);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Account findAccount(String clientId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM accounts WHERE client_id=?")) {
            ps.setString(1, clientId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? account(rs) : null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<Account> listAccounts() {
        return query("SELECT * FROM accounts WHERE deleted=0 ORDER BY sort_order", rs -> account(rs));
    }

    /** 软删除账户：置 deleted=1 并刷新 last_modified，保证删除能被同步。 */
    public void deleteAccount(String clientId) {
        Account a = findAccount(clientId);
        if (a == null) return;
        a.deleted = true;
        a.lastModified = System.currentTimeMillis();
        upsertAccount(a);
    }

    // ---------------- Category ----------------
    public void upsertCategory(Category c) {
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO category (client_id,name,type,icon,sort_order,last_modified,deleted,created_at,updated_at) KEY(client_id) VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, c.clientId);
            ps.setString(2, c.name);
            ps.setString(3, c.type);
            ps.setString(4, c.icon);
            ps.setInt(5, c.sortOrder);
            ps.setLong(6, c.lastModified);
            ps.setInt(7, c.deleted ? 1 : 0);
            ps.setString(8, c.createdAt);
            ps.setString(9, c.updatedAt);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<Category> listCategories(String type) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM category WHERE deleted=0 AND type=? ORDER BY sort_order")) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                List<Category> list = new ArrayList<>();
                while (rs.next()) list.add(category(rs));
                return list;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Category findCategory(String clientId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM category WHERE client_id=?")) {
            ps.setString(1, clientId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? category(rs) : null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------- Transaction ----------------
    public void upsertTransaction(Transaction t) {
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO transaction_record (client_id,date,amount,type,category_client_id,account_client_id,note,photo_path,source,last_modified,deleted,created_at,updated_at) KEY(client_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, t.clientId);
            ps.setDate(2, java.sql.Date.valueOf(t.date == null ? LocalDate.now() : t.date));
            ps.setBigDecimal(3, t.amount == null ? BigDecimal.ZERO : t.amount);
            ps.setString(4, t.type);
            ps.setString(5, t.categoryClientId);
            ps.setString(6, t.accountClientId);
            ps.setString(7, t.note);
            ps.setString(8, t.photoPath);
            ps.setString(9, t.source);
            ps.setLong(10, t.lastModified);
            ps.setInt(11, t.deleted ? 1 : 0);
            ps.setString(12, t.createdAt);
            ps.setString(13, t.updatedAt);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<Transaction> listTransactions(LocalDate from, LocalDate to, String categoryClientId, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT * FROM transaction_record WHERE deleted=0");
        List<Object> args = new ArrayList<>();
        if (from != null) {
            sql.append(" AND date>=?");
            args.add(java.sql.Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" AND date<=?");
            args.add(java.sql.Date.valueOf(to));
        }
        if (categoryClientId != null && !categoryClientId.isBlank()) {
            sql.append(" AND category_client_id=?");
            args.add(categoryClientId);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (note LIKE ? OR client_id LIKE ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        sql.append(" ORDER BY date DESC");
        return query(sql.toString(), args, rs -> transaction(rs));
    }

    public List<Transaction> listModifiedSince(long since) {
        return query("SELECT * FROM transaction_record WHERE last_modified>? ORDER BY last_modified",
                List.of(since), rs -> transaction(rs));
    }

    /** 自 last_modified > since 的全部账户（含删除墓碑）。 */
    public List<Account> listAccountsModifiedSince(long since) {
        return query("SELECT * FROM accounts WHERE last_modified>? ORDER BY last_modified",
                List.of(since), rs -> account(rs));
    }

    /** 自 last_modified > since 的全部分类（含删除墓碑）。 */
    public List<Category> listCategoriesModifiedSince(long since) {
        return query("SELECT * FROM category WHERE last_modified>? ORDER BY last_modified",
                List.of(since), rs -> category(rs));
    }

    /** 自 last_modified > since 的全部预算（含删除墓碑）。 */
    public List<Budget> listAllBudgetsModifiedSince(long since) {
        return query("SELECT * FROM budget WHERE last_modified>? ORDER BY last_modified",
                List.of(since), rs -> budget(rs));
    }

    /** 自 last_modified > since 的全部模板（含删除墓碑）。 */
    public List<Template> listTemplatesModifiedSince(long since) {
        return query("SELECT * FROM template WHERE last_modified>? ORDER BY last_modified",
                List.of(since), rs -> template(rs));
    }

    /** 软删除流水：置 deleted=1 并刷新 last_modified，删除可被同步为墓碑。 */
    public void deleteTransaction(String clientId) {
        for (Transaction t : listTransactions(null, null, null, null)) {
            if (t.clientId.equals(clientId)) {
                t.deleted = true;
                t.lastModified = System.currentTimeMillis();
                upsertTransaction(t);
                return;
            }
        }
    }

    // ---------------- Budget ----------------
    public Budget findBudget(String categoryClientId, int year, int month) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM budget WHERE category_client_id=? AND budget_year=? AND budget_month=?")) {
            ps.setString(1, categoryClientId);
            ps.setInt(2, year);
            ps.setInt(3, month);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? budget(rs) : null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void upsertBudget(Budget b) {
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO budget (client_id,category_client_id,budget_year,budget_month,amount_limit,last_modified,deleted,created_at,updated_at) KEY(client_id) VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, b.clientId);
            ps.setString(2, b.categoryClientId);
            ps.setInt(3, b.year);
            ps.setInt(4, b.month);
            ps.setBigDecimal(5, b.amountLimit);
            ps.setLong(6, b.lastModified);
            ps.setInt(7, b.deleted ? 1 : 0);
            ps.setString(8, null);
            ps.setString(9, null);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<Budget> listBudgets(int year, int month) {
        return query("SELECT * FROM budget WHERE deleted=0 AND budget_year=? AND budget_month=?",
                List.of(year, month), rs -> budget(rs));
    }

    public List<Budget> listAllBudgets() {
        return query("SELECT * FROM budget WHERE deleted=0", rs -> budget(rs));
    }

    // ---------------- Template ----------------
    public void upsertTemplate(Template t) {
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO template (client_id,name,amount,type,category_client_id,account_client_id,note,sort_order,last_modified,deleted,created_at,updated_at) KEY(client_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, t.clientId);
            ps.setString(2, t.name);
            ps.setBigDecimal(3, t.amount);
            ps.setString(4, t.type);
            ps.setString(5, t.categoryClientId);
            ps.setString(6, t.accountClientId);
            ps.setString(7, t.note);
            ps.setInt(8, t.sortOrder);
            ps.setLong(9, t.lastModified);
            ps.setInt(10, t.deleted ? 1 : 0);
            ps.setString(11, null);
            ps.setString(12, null);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------- Sync state ----------------
    public long lastSync(String deviceId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT last_sync FROM sync_state WHERE device_id=?")) {
            ps.setString(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void saveLastSync(String deviceId, long ts) {
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO sync_state (device_id,last_sync) KEY(device_id) VALUES(?,?)")) {
            ps.setString(1, deviceId);
            ps.setLong(2, ts);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------- helpers ----------------
    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws Exception;
    }

    private <T> List<T> query(String sql, RowMapper<T> mapper) {
        return query(sql, List.of(), mapper);
    }

    private <T> List<T> query(String sql, List<Object> args, RowMapper<T> mapper) {
        List<T> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapper.map(rs));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return list;
    }

    private static Account account(ResultSet rs) throws Exception {
        Account a = new Account();
        a.clientId = rs.getString("client_id");
        a.name = rs.getString("name");
        a.type = rs.getString("type");
        a.icon = rs.getString("icon");
        a.initialBalance = rs.getBigDecimal("initial_balance");
        a.sortOrder = rs.getInt("sort_order");
        a.lastModified = rs.getLong("last_modified");
        a.deleted = rs.getInt("deleted") == 1;
        return a;
    }

    private static Category category(ResultSet rs) throws Exception {
        Category c = new Category();
        c.clientId = rs.getString("client_id");
        c.name = rs.getString("name");
        c.type = rs.getString("type");
        c.icon = rs.getString("icon");
        c.sortOrder = rs.getInt("sort_order");
        c.lastModified = rs.getLong("last_modified");
        c.deleted = rs.getInt("deleted") == 1;
        return c;
    }

    private static Transaction transaction(ResultSet rs) throws Exception {
        Transaction t = new Transaction();
        t.clientId = rs.getString("client_id");
        t.date = rs.getDate("date").toLocalDate();
        t.amount = rs.getBigDecimal("amount");
        t.type = rs.getString("type");
        t.categoryClientId = rs.getString("category_client_id");
        t.accountClientId = rs.getString("account_client_id");
        t.note = rs.getString("note");
        t.photoPath = rs.getString("photo_path");
        t.source = rs.getString("source");
        t.lastModified = rs.getLong("last_modified");
        t.deleted = rs.getInt("deleted") == 1;
        return t;
    }

    private static Budget budget(ResultSet rs) throws Exception {
        Budget b = new Budget();
        b.clientId = rs.getString("client_id");
        b.categoryClientId = rs.getString("category_client_id");
        b.year = rs.getInt("budget_year");
        b.month = rs.getInt("budget_month");
        b.amountLimit = rs.getBigDecimal("amount_limit");
        b.lastModified = rs.getLong("last_modified");
        b.deleted = rs.getInt("deleted") == 1;
        return b;
    }

    private static Template template(ResultSet rs) throws Exception {
        Template t = new Template();
        t.clientId = rs.getString("client_id");
        t.name = rs.getString("name");
        t.amount = rs.getBigDecimal("amount");
        t.type = rs.getString("type");
        t.categoryClientId = rs.getString("category_client_id");
        t.accountClientId = rs.getString("account_client_id");
        t.note = rs.getString("note");
        t.sortOrder = rs.getInt("sort_order");
        t.lastModified = rs.getLong("last_modified");
        t.deleted = rs.getInt("deleted") == 1;
        return t;
    }

    public void close() {
        try {
            if (conn != null) conn.close();
        } catch (Exception ignore) {
        }
    }
}
