package com.personal.accounting.desktop.data;

import com.personal.accounting.desktop.model.Account;
import com.personal.accounting.desktop.model.Budget;
import com.personal.accounting.desktop.model.Category;
import com.personal.accounting.desktop.model.RecurringPending;
import com.personal.accounting.desktop.model.RecurringRule;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

            // 周期记账（自动续费）：规则参与同步；待确认项只存本机
            s.execute("CREATE TABLE IF NOT EXISTS recurring_rule ("
                    + "client_id VARCHAR(36) PRIMARY KEY, name VARCHAR(64) NOT NULL,"
                    + " amount DECIMAL(19,2) NOT NULL, txn_type VARCHAR(16) NOT NULL,"
                    + " category_client_id VARCHAR(36), account_client_id VARCHAR(36), note VARCHAR(512),"
                    + " interval_unit VARCHAR(8) NOT NULL DEFAULT 'MONTH', interval_count INT NOT NULL DEFAULT 1,"
                    + " anchor_date DATE NOT NULL, last_generated_date DATE, enabled INT DEFAULT 1,"
                    + " sort_order INT DEFAULT 0, last_modified BIGINT NOT NULL, deleted INT DEFAULT 0,"
                    + " created_at VARCHAR(30), updated_at VARCHAR(30))");
            s.execute("CREATE TABLE IF NOT EXISTS recurring_pending ("
                    + "client_id VARCHAR(36) PRIMARY KEY, rule_client_id VARCHAR(36) NOT NULL,"
                    + " date DATE NOT NULL, amount DECIMAL(19,2) NOT NULL, txn_type VARCHAR(16) NOT NULL,"
                    + " category_client_id VARCHAR(36), account_client_id VARCHAR(36), note VARCHAR(512),"
                    + " kind VARCHAR(16) NOT NULL DEFAULT 'PENDING', last_modified BIGINT NOT NULL)");
        }
    }

    /** 首次使用写入内置默认分类/账户（与移动端相同的 clientId，保证跨端一致、不产生重复）。 */
    private void seedDefaults() throws Exception {
        if (isEmpty("category")) {
            insertDefaultExpenseCategories();
        }
        if (!hasType("INCOME")) {
            insertDefaultIncomeCategories();
        }
        if (isEmpty("accounts")) {
            insertDefaultAccounts();
        }
    }

    /** 内置默认支出分类（与手机端 clientId 一致）。 */
    private void insertDefaultExpenseCategories() throws Exception {
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

    /** 内置默认收入分类。 */
    private void insertDefaultIncomeCategories() throws Exception {
        seedCategory("cat-salary", "工资", "💼", "INCOME");
        seedCategory("cat-side", "副业", "🧾", "INCOME");
        seedCategory("cat-redpacket", "红包", "🧧", "INCOME");
        seedCategory("cat-other-income", "其他收入", "💰", "INCOME");
    }

    /** 内置默认账户。 */
    private void insertDefaultAccounts() throws Exception {
        seedAccount("acc-cash", "现金", "CASH", "💵");
        seedAccount("acc-savings", "储蓄卡", "DEBIT_CARD", "🏦");
        seedAccount("acc-credit", "信用卡", "CREDIT_CARD", "💳");
        seedAccount("acc-wechat", "微信", "WECHAT", "💚");
        seedAccount("acc-alipay", "支付宝", "ALIPAY", "💙");
    }

    /**
     * 重置分类为初次安装状态：软删除全部分类（含自建），再恢复内置默认分类。
     * 只影响分类表，流水记录保持不动（历史流水会显示为"未分类"）。
     */
    public synchronized void resetCategories() throws Exception {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE category SET deleted=1, last_modified=? WHERE deleted=0")) {
            ps.setLong(1, now);
            ps.executeUpdate();
        }
        insertDefaultExpenseCategories();
        insertDefaultIncomeCategories();
    }

    /** 重置账户为初次安装状态：软删除全部账户（含自建），再恢复内置默认账户。流水不动。 */
    public synchronized void resetAccounts() throws Exception {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE accounts SET deleted=1, last_modified=? WHERE deleted=0")) {
            ps.setLong(1, now);
            ps.executeUpdate();
        }
        insertDefaultAccounts();
    }

    /** 清空全部预算上限，回到"所有分类都未设置上限"的状态。 */
    public synchronized void resetBudgets() throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE budget SET deleted=1, last_modified=? WHERE deleted=0")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /**
     * 该表是否没有任何「未删除」的行。软删除是墓碑（deleted=1），所以只数 deleted=0：
     * 用户把分类/账户删完后重启不应被当成"首次安装"重新灌一遍默认数据。
     */
    private boolean isEmpty(String table) throws Exception {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE deleted=0")) {
            rs.next();
            return rs.getInt(1) == 0;
        }
    }

    private boolean hasType(String type) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM category WHERE deleted=0 AND type=?")) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private void seedCategory(String id, String name, String icon, String type) throws Exception {
        long now = System.currentTimeMillis();
        // 用 MERGE：重置时这些 clientId 可能已存在（被软删除的墓碑），INSERT 会主键冲突；
        // last_modified 刷新为当前时间，删除动作才能在 LWW 同步里被覆盖。
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO category (client_id,name,type,icon,sort_order,last_modified,deleted,created_at,updated_at) KEY(client_id) VALUES(?,?,?,?,?,?,?,?,?)")) {
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
        // 同 seedCategory：重置时可能已存在墓碑行，用 MERGE 覆盖并把 deleted 置 0。
        // 有意不带 initial_balance：命中已有行时该列不进 UPDATE 列表，
        // 从而「重置账户」保留用户原来设置的初始余额；新插入的行由列默认值 0 补齐。
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO accounts (client_id,name,type,icon,sort_order,last_modified,deleted,created_at,updated_at) "
                        + "KEY(client_id) VALUES(?,?,?,?,?,?,?,?,?)")) {
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

    // ---------------- Account ----------------
    public synchronized void upsertAccount(Account a) {
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

    public synchronized Account findAccount(String clientId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM accounts WHERE client_id=?")) {
            ps.setString(1, clientId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? account(rs) : null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized List<Account> listAccounts() {
        return query("SELECT * FROM accounts WHERE deleted=0 ORDER BY sort_order", rs -> account(rs));
    }

    /** 软删除账户：置 deleted=1 并刷新 last_modified，保证删除能被同步。 */
    public synchronized void deleteAccount(String clientId) {
        Account a = findAccount(clientId);
        if (a == null) return;
        a.deleted = true;
        a.lastModified = System.currentTimeMillis();
        upsertAccount(a);
    }

    // ---------------- Category ----------------
    public synchronized void upsertCategory(Category c) {
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

    public synchronized List<Category> listCategories(String type) {
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

    public synchronized Category findCategory(String clientId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM category WHERE client_id=?")) {
            ps.setString(1, clientId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? category(rs) : null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 软删除分类：置 deleted=1 并刷新 last_modified，保证删除能被同步。 */
    public synchronized void deleteCategory(String clientId) {
        Category c = findCategory(clientId);
        if (c == null) return;
        c.deleted = true;
        c.lastModified = System.currentTimeMillis();
        upsertCategory(c);
    }

    public synchronized String categoryName(String clientId) {
        if (clientId == null) return "未分类";
        Category c = findCategory(clientId);
        return c == null ? "未分类" : c.name;
    }

    public synchronized String accountName(String clientId) {
        if (clientId == null) return "";
        Account a = findAccount(clientId);
        return a == null ? "" : a.name;
    }

    /** 该分类下未删除的流水条数，用于删除前提示影响范围。 */
    public synchronized int countTransactionsByCategory(String categoryClientId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM transaction_record WHERE deleted=0 AND category_client_id=?")) {
            ps.setString(1, categoryClientId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
      * 未删除的流水总条数。
      *
      * <p>给「一条流水都还没有」这类判断用：不依赖「首次使用」标记，
      * 用户记完第一笔就自然不再是这个状态。</p>
      */
    public synchronized int countTransactions() {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM transaction_record WHERE deleted=0");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------- Transaction ----------------
    public synchronized void upsertTransaction(Transaction t) {
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

    public synchronized List<Transaction> listTransactions(LocalDate from, LocalDate to, String categoryClientId, String keyword) {
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

    public synchronized Transaction findTransactionByClientId(String clientId) {
        return query("SELECT * FROM transaction_record WHERE client_id=?",
                List.of(clientId), DataManager::transaction).stream().findFirst().orElse(null);
    }

    /**
     * 按「年-月」聚合的收入/支出，用于跨月趋势。
     *
     * <p>一次查询拿完整个区间（不是逐月调 listTransactions 再求和）。返回的 key 形如
     * {@code 2026-09}，value 是 {@code [收入, 支出]}，与手机端
     * {@code TransactionRepo.monthlyTotals} 同口径。</p>
     *
     * @param categoryClientId 传 null 表示不限分类
     */
    public synchronized Map<String, BigDecimal[]> monthlyTotals(LocalDate from, LocalDate to, String categoryClientId) {
        StringBuilder sql = new StringBuilder(
                "SELECT FORMATDATETIME(date, 'yyyy-MM') AS ym,"
                        + " COALESCE(SUM(CASE WHEN type='INCOME' THEN amount ELSE 0 END), 0) AS income,"
                        + " COALESCE(SUM(CASE WHEN type='EXPENSE' THEN amount ELSE 0 END), 0) AS expense"
                        + " FROM transaction_record WHERE deleted=0 AND date>=? AND date<=?");
        List<Object> args = new ArrayList<>();
        args.add(java.sql.Date.valueOf(from));
        args.add(java.sql.Date.valueOf(to));
        if (categoryClientId != null && !categoryClientId.isBlank()) {
            sql.append(" AND category_client_id=?");
            args.add(categoryClientId);
        }
        sql.append(" GROUP BY ym");

        List<Object[]> rows = query(sql.toString(), args, rs ->
                new Object[]{rs.getString("ym"), rs.getBigDecimal("income"), rs.getBigDecimal("expense")});
        Map<String, BigDecimal[]> totals = new LinkedHashMap<>();
        for (Object[] row : rows) {
            BigDecimal income = (BigDecimal) row[1];
            BigDecimal expense = (BigDecimal) row[2];
            totals.put((String) row[0], new BigDecimal[]{
                    income == null ? BigDecimal.ZERO : income,
                    expense == null ? BigDecimal.ZERO : expense});
        }
        return totals;
    }

    public synchronized List<Transaction> listModifiedSince(long since) {
        return query("SELECT * FROM transaction_record WHERE last_modified>? ORDER BY last_modified",
                List.of(since), rs -> transaction(rs));
    }

    /** 自 last_modified > since 的全部账户（含删除墓碑）。 */
    public synchronized List<Account> listAccountsModifiedSince(long since) {
        return query("SELECT * FROM accounts WHERE last_modified>? ORDER BY last_modified",
                List.of(since), rs -> account(rs));
    }

    /** 自 last_modified > since 的全部分类（含删除墓碑）。 */
    public synchronized List<Category> listCategoriesModifiedSince(long since) {
        return query("SELECT * FROM category WHERE last_modified>? ORDER BY last_modified",
                List.of(since), rs -> category(rs));
    }

    /** 自 last_modified > since 的全部预算（含删除墓碑）。 */
    public synchronized List<Budget> listAllBudgetsModifiedSince(long since) {
        return query("SELECT * FROM budget WHERE last_modified>? ORDER BY last_modified",
                List.of(since), rs -> budget(rs));
    }

    /** 自 last_modified > since 的全部模板（含删除墓碑）。 */
    public synchronized List<Template> listTemplatesModifiedSince(long since) {
        return query("SELECT * FROM template WHERE last_modified>? ORDER BY last_modified",
                List.of(since), rs -> template(rs));
    }

    /** 软删除流水：置 deleted=1 并刷新 last_modified，删除可被同步为墓碑。 */
    public synchronized void deleteTransaction(String clientId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE transaction_record SET deleted=1, last_modified=? WHERE client_id=?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, clientId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------- Budget ----------------
    public synchronized Budget findBudget(String categoryClientId, int year, int month) {
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

    public synchronized void upsertBudget(Budget b) {
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

    public synchronized List<Budget> listBudgets(int year, int month) {
        return query("SELECT * FROM budget WHERE deleted=0 AND budget_year=? AND budget_month=?",
                List.of(year, month), rs -> budget(rs));
    }

    public synchronized List<Budget> listAllBudgets() {
        return query("SELECT * FROM budget WHERE deleted=0", rs -> budget(rs));
    }

    // ---------------- Template ----------------
    public synchronized void upsertTemplate(Template t) {
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

    // ---------------- 周期记账（自动续费） ----------------

    /** 未删除的周期规则。 */
    public synchronized List<RecurringRule> listRecurringRules() {
        return query("SELECT * FROM recurring_rule WHERE deleted=0 ORDER BY sort_order ASC, client_id ASC",
                DataManager::recurringRule);
    }

    public synchronized RecurringRule findRecurringRule(String clientId) {
        if (clientId == null) return null;
        List<RecurringRule> list = query("SELECT * FROM recurring_rule WHERE client_id=?",
                List.of(clientId), DataManager::recurringRule);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 含删除墓碑的规则（同步用）。 */
    public synchronized List<RecurringRule> listRecurringRulesModifiedSince(long since) {
        return query("SELECT * FROM recurring_rule WHERE last_modified > ?",
                List.of(since), DataManager::recurringRule);
    }

    /** 保存规则；last_modified 刷新为当前时间（用户操作）。 */
    public synchronized void saveRecurringRule(RecurringRule r) {
        if (r.clientId == null || r.clientId.isBlank()) {
            r.clientId = java.util.UUID.randomUUID().toString();
        }
        r.lastModified = System.currentTimeMillis();
        upsertRecurringRule(r);
    }

    /** MERGE 写入（含同步下行的数据），保留 createdAt/updatedAt 为 null。 */
    public synchronized void upsertRecurringRule(RecurringRule r) {
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO recurring_rule (client_id,name,amount,txn_type,category_client_id,"
                        + "account_client_id,note,interval_unit,interval_count,anchor_date,"
                        + "last_generated_date,enabled,sort_order,last_modified,deleted,created_at,updated_at) "
                        + "KEY(client_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, r.clientId);
            ps.setString(2, r.name);
            ps.setBigDecimal(3, r.amount == null ? BigDecimal.ZERO : r.amount);
            ps.setString(4, r.type);
            ps.setString(5, r.categoryClientId);
            ps.setString(6, r.accountClientId);
            ps.setString(7, r.note);
            ps.setString(8, r.intervalUnit == null ? "MONTH" : r.intervalUnit);
            ps.setInt(9, Math.max(1, r.intervalCount));
            ps.setDate(10, java.sql.Date.valueOf(r.anchorDate == null ? LocalDate.now() : r.anchorDate));
            ps.setDate(11, r.lastGeneratedDate == null ? null : java.sql.Date.valueOf(r.lastGeneratedDate));
            ps.setInt(12, r.enabled ? 1 : 0);
            ps.setInt(13, r.sortOrder);
            ps.setLong(14, r.lastModified);
            ps.setInt(15, r.deleted ? 1 : 0);
            ps.setString(16, null);
            ps.setString(17, null);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 软删除规则，并清掉它尚未确认的待确认项。 */
    public synchronized void deleteRecurringRule(String clientId) {
        RecurringRule r = findRecurringRule(clientId);
        if (r == null) return;
        r.deleted = true;
        r.lastModified = System.currentTimeMillis();
        upsertRecurringRule(r);
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM recurring_pending WHERE rule_client_id=? AND kind='PENDING'")) {
            ps.setString(1, clientId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized List<RecurringPending> listRecurringPending() {
        return query("SELECT * FROM recurring_pending WHERE kind='PENDING' ORDER BY date ASC, client_id ASC",
                DataManager::recurringPending);
    }

    public synchronized RecurringPending findRecurringPending(String clientId) {
        List<RecurringPending> list = query("SELECT * FROM recurring_pending WHERE client_id=?",
                List.of(clientId), DataManager::recurringPending);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 按规则推算到今天为止需要生成的待确认项并落库（幂等：同 id 的项不会重复生成）。
     *
     * @return 本次新生成的条数
     */
    public synchronized int materializeRecurring(LocalDate today) {
        int created = 0;
        for (RecurringRule rule : listRecurringRules()) {
            if (!rule.enabled) continue;
            List<LocalDate> dates = com.personal.accounting.desktop.service.Recurrence.dueDates(rule, today);
            for (LocalDate date : dates) {
                String id = com.personal.accounting.desktop.service.Recurrence.occurrenceId(rule, date);
                if (findRecurringPending(id) != null) {
                    rule.lastGeneratedDate = date;
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
                upsertRecurringPending(p);
                rule.lastGeneratedDate = date;
                created++;
            }
            if (!dates.isEmpty()) {
                // 只推进生成水位，不改动 last_modified（不算用户修改，避免与同步打架）
                upsertRecurringRule(rule);
            }
        }
        return created;
    }

    public synchronized void upsertRecurringPending(RecurringPending p) {
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO recurring_pending (client_id,rule_client_id,date,amount,txn_type,"
                        + "category_client_id,account_client_id,note,kind,last_modified) "
                        + "KEY(client_id) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, p.clientId);
            ps.setString(2, p.ruleClientId);
            ps.setDate(3, java.sql.Date.valueOf(p.date));
            ps.setBigDecimal(4, p.amount == null ? BigDecimal.ZERO : p.amount);
            ps.setString(5, p.type);
            ps.setString(6, p.categoryClientId);
            ps.setString(7, p.accountClientId);
            ps.setString(8, p.note);
            ps.setString(9, p.kind == null ? "PENDING" : p.kind);
            ps.setLong(10, p.lastModified);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 确认待确认项：写入一条流水并把该项标记为 ACCEPTED。
     * 流水 clientId 就是待确认项 id（{@code rr-<规则id前8位>-<yyyyMMdd>}），
     * 因此两台设备各自确认同一期也不会产生重复流水。
     */
    public synchronized String confirmRecurring(String pendingClientId, BigDecimal amountOverride) {
        RecurringPending p = findRecurringPending(pendingClientId);
        if (p == null) return null;
        Transaction t = new Transaction();
        t.clientId = p.clientId;
        t.date = p.date;
        t.amount = amountOverride == null ? p.amount : amountOverride;
        t.type = p.type;
        t.categoryClientId = p.categoryClientId;
        t.accountClientId = p.accountClientId;
        t.note = (p.note == null || p.note.isBlank()) ? "周期记账" : p.note;
        t.source = "RECURRING";
        t.lastModified = System.currentTimeMillis();
        t.deleted = false;
        upsertTransaction(t);
        markRecurringPending(p.clientId, "ACCEPTED");
        return p.clientId;
    }

    public synchronized void skipRecurring(String pendingClientId) {
        markRecurringPending(pendingClientId, "SKIPPED");
    }

    private void markRecurringPending(String clientId, String kind) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE recurring_pending SET kind=?, last_modified=? WHERE client_id=?")) {
            ps.setString(1, kind);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, clientId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static RecurringRule recurringRule(ResultSet rs) throws Exception {
        RecurringRule r = new RecurringRule();
        r.clientId = rs.getString("client_id");
        r.name = rs.getString("name");
        r.amount = rs.getBigDecimal("amount");
        r.type = rs.getString("txn_type");
        r.categoryClientId = rs.getString("category_client_id");
        r.accountClientId = rs.getString("account_client_id");
        r.note = rs.getString("note");
        r.intervalUnit = rs.getString("interval_unit");
        r.intervalCount = rs.getInt("interval_count");
        java.sql.Date anchor = rs.getDate("anchor_date");
        r.anchorDate = anchor == null ? LocalDate.now() : anchor.toLocalDate();
        java.sql.Date last = rs.getDate("last_generated_date");
        r.lastGeneratedDate = last == null ? null : last.toLocalDate();
        r.enabled = rs.getInt("enabled") == 1;
        r.sortOrder = rs.getInt("sort_order");
        r.lastModified = rs.getLong("last_modified");
        r.deleted = rs.getInt("deleted") == 1;
        return r;
    }

    private static RecurringPending recurringPending(ResultSet rs) throws Exception {
        RecurringPending p = new RecurringPending();
        p.clientId = rs.getString("client_id");
        p.ruleClientId = rs.getString("rule_client_id");
        java.sql.Date date = rs.getDate("date");
        p.date = date == null ? null : date.toLocalDate();
        p.amount = rs.getBigDecimal("amount");
        p.type = rs.getString("txn_type");
        p.categoryClientId = rs.getString("category_client_id");
        p.accountClientId = rs.getString("account_client_id");
        p.note = rs.getString("note");
        p.kind = rs.getString("kind");
        p.lastModified = rs.getLong("last_modified");
        return p;
    }

    // ---------------- Sync state ----------------
    public synchronized long lastSync(String deviceId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT last_sync FROM sync_state WHERE device_id=?")) {
            ps.setString(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized void saveLastSync(String deviceId, long ts) {
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

    public synchronized void close() {
        try {
            if (conn != null) conn.close();
        } catch (Exception ignore) {
        }
    }
}
