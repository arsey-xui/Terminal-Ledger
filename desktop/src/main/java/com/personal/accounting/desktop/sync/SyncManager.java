package com.personal.accounting.desktop.sync;

import com.personal.accounting.desktop.data.DataManager;
import com.personal.accounting.desktop.model.Account;
import com.personal.accounting.desktop.model.Budget;
import com.personal.accounting.desktop.model.Category;
import com.personal.accounting.desktop.model.RecurringRule;
import com.personal.accounting.desktop.model.Template;
import com.personal.accounting.desktop.model.Transaction;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** 桌面端同步器，与移动端共用 LWW 合并规则。 */
public class SyncManager {

    /**
     * pull 水位的重叠量。服务端的 {@code last_modified} 来自各客户端时钟，而 {@code since} 比的是
     * 服务端 {@code serverTime}：两个时钟域不完全一致，所以留一段重叠 —— 宁可多拉一点
     * （LWW 合并幂等），也不要因为两台设备差了几分钟就永久漏掉记录。
     */
    private static final long PULL_OVERLAP_MS = TimeUnit.HOURS.toMillis(1);

    /**
     * push 时始终重推「最近这段时间」里的改动。
     *
     * <p>水位本身精确推进（存本次推上去的最大 lastModified），但取数起点再往前压一小时：
     * 同一毫秒的边界改动、以及本机时钟往回跳之后的改动都不会被永久漏掉。
     * 代价只是最近一小时内的改动会重复推几次 —— LWW 幂等，无所谓。</p>
     */
    private static final long PUSH_RECENT_MS = TimeUnit.HOURS.toMillis(1);

    private final DataManager db;
    private final String deviceId;
    private final ApiClient api = new ApiClient();

    public SyncManager(DataManager db) {
        this.db = db;
        this.deviceId = SyncConfig.deviceId();
    }

    public String sync() throws Exception {
        if (!SyncConfig.isConfigured()) {
            return "请先在「数据管理 → 设置后端地址」填写后端地址";
        }
        // 两个水位、两个时钟域，不能共用一个值：
        // · pull 水位是服务端 serverTime；· push 水位是本机 lastModified 的最大值。
        long storedPull = db.lastSync(deviceId);
        long storedPush = db.pushWatermark(deviceId);
        long pullSince = storedPull <= 0L ? 0L : Math.max(0L, storedPull - PULL_OVERLAP_MS);
        long pushSince = storedPush <= 0L ? 0L
                : Math.min(storedPush, System.currentTimeMillis() - PUSH_RECENT_MS);

        SyncPayloads.PushRequest push = new SyncPayloads.PushRequest();
        push.deviceId = deviceId;
        // 只推 push 水位之后的变更（原先取 0，等于每次同步都把整本账上传一遍）
        push.transactions.addAll(db.listModifiedSince(pushSince));
        push.accounts.addAll(db.listAccountsModifiedSince(pushSince));
        push.categories.addAll(db.listCategoriesModifiedSince(pushSince));
        push.budgets.addAll(db.listAllBudgetsModifiedSince(pushSince));
        push.templates.addAll(db.listTemplatesModifiedSince(pushSince));
        push.recurringRules.addAll(db.listRecurringRulesModifiedSince(pushSince));

        int pushedRows = rowCount(push);
        long maxUploaded = maxLastModified(push);
        if (pushedRows > 0) {
            api.post("/api/sync/push", push, SyncPayloads.SyncResult.class);
        }

        SyncPayloads.PullRequest pull = new SyncPayloads.PullRequest();
        pull.deviceId = deviceId;
        pull.since = pullSince;   // 水位为 0 时服务端只回最近 N 个月流水 + 全量字典
        long firstServerTime = 0L;
        int merged = 0;
        while (true) {
            SyncPayloads.PullResponse resp = api.post("/api/sync/pull", pull, SyncPayloads.PullResponse.class);
            if (resp == null) return "服务端返回为空";
            // 用**第一页**的 serverTime 做水位：它是最早读到的服务端时间，比后面几页保守
            if (firstServerTime == 0L) firstServerTime = resp.serverTime;
            for (Transaction t : resp.transactions) if (lww(t)) merged++;
            for (Category c : resp.categories) if (lww(c)) merged++;
            for (Account a : resp.accounts) if (lww(a)) merged++;
            for (Budget b : resp.budgets) if (lww(b)) merged++;
            for (Template t : resp.templates) if (lww(t)) merged++;
            for (RecurringRule r : resp.recurringRules) if (lww(r)) merged++;
            if (!resp.hasMore || resp.nextCursor == null) break;
            pull.cursor = resp.nextCursor;
        }

        // 水位只在整轮成功后落盘：中途失败保持旧水位，下次重来（最多重复推几条，LWW 幂等）
        if (firstServerTime > 0L) db.saveLastSync(deviceId, firstServerTime);
        if (maxUploaded > 0L) db.savePushWatermark(deviceId, maxUploaded);

        return "同步完成：上传 " + pushedRows + " 条，合并 " + merged + " 条";
    }

    /** 本次要推的记录里最新的 lastModified，作为下次的 push 水位。 */
    private static long maxLastModified(SyncPayloads.PushRequest p) {
        long max = 0L;
        for (Transaction t : p.transactions) max = Math.max(max, t.lastModified);
        for (Category c : p.categories) max = Math.max(max, c.lastModified);
        for (Account a : p.accounts) max = Math.max(max, a.lastModified);
        for (Budget b : p.budgets) max = Math.max(max, b.lastModified);
        for (Template t : p.templates) max = Math.max(max, t.lastModified);
        for (RecurringRule r : p.recurringRules) max = Math.max(max, r.lastModified);
        return max;
    }

    private static int rowCount(SyncPayloads.PushRequest p) {
        return p.transactions.size() + p.categories.size() + p.accounts.size()
                + p.budgets.size() + p.templates.size() + p.recurringRules.size();
    }

    // ---- LWW 合并 ----
    private boolean lww(Transaction t) {
        Transaction ex = findTxn(t.clientId);
        if (ex != null && ex.lastModified > t.lastModified) return false;
        db.upsertTransaction(t);
        return true;
    }

    private Transaction findTxn(String id) {
        return db.findTransactionByClientId(id);
    }

    private boolean lww(Category c) {
        Category ex = db.findCategory(c.clientId);
        if (ex != null && ex.lastModified > c.lastModified) return false;
        db.upsertCategory(c);
        return true;
    }

    private boolean lww(Account a) {
        Account ex = db.findAccount(a.clientId);
        if (ex != null && ex.lastModified > a.lastModified) return false;
        db.upsertAccount(a);
        return true;
    }

    private boolean lww(Budget b) {
        Budget ex = db.findBudget(b.categoryClientId, b.year, b.month);
        if (ex != null && ex.lastModified > b.lastModified) return false;
        db.upsertBudget(b);
        return true;
    }

    private boolean lww(Template t) {
        Template ex = db.findTemplate(t.clientId);
        if (ex != null && ex.lastModified > t.lastModified) return false;
        db.upsertTemplate(t);
        return true;
    }

    private boolean lww(RecurringRule r) {
        RecurringRule ex = db.findRecurringRule(r.clientId);
        if (ex != null && ex.lastModified > r.lastModified) return false;
        db.upsertRecurringRule(r);
        return true;
    }
}
