package com.personal.accounting.desktop.sync;

import com.personal.accounting.desktop.data.DataManager;
import com.personal.accounting.desktop.model.Account;
import com.personal.accounting.desktop.model.Budget;
import com.personal.accounting.desktop.model.Category;
import com.personal.accounting.desktop.model.RecurringRule;
import com.personal.accounting.desktop.model.Template;
import com.personal.accounting.desktop.model.Transaction;

import java.util.List;

/** 桌面端同步器，与移动端共用 LWW 合并规则。 */
public class SyncManager {

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
        SyncPayloads.PushRequest push = new SyncPayloads.PushRequest();
        push.deviceId = deviceId;
        // 全量（含删除墓碑）：个人数据量小，逐次对账最可靠，避免水位/时钟差异漏同步
        push.transactions.addAll(db.listModifiedSince(0));
        push.accounts.addAll(db.listAccountsModifiedSince(0));
        push.categories.addAll(db.listCategoriesModifiedSince(0));
        push.budgets.addAll(db.listAllBudgetsModifiedSince(0));
        push.templates.addAll(db.listTemplatesModifiedSince(0));
        push.recurringRules.addAll(db.listRecurringRulesModifiedSince(0));

        if (!isEmpty(push)) {
            api.post("/api/sync/push", push, SyncPayloads.SyncResult.class);
        }

        SyncPayloads.PullRequest pull = new SyncPayloads.PullRequest();
        pull.deviceId = deviceId;
        pull.since = 0L; // 全量拉取（近3个月窗口）
        int merged = 0;
        while (true) {
            SyncPayloads.PullResponse resp = api.post("/api/sync/pull", pull, SyncPayloads.PullResponse.class);
            if (resp == null) return "服务端返回为空";
            for (Transaction t : resp.transactions) if (lww(t)) merged++;
            for (Category c : resp.categories) if (lww(c)) merged++;
            for (Account a : resp.accounts) if (lww(a)) merged++;
            for (Budget b : resp.budgets) if (lww(b)) merged++;
            for (Template t : resp.templates) if (lww(t)) merged++;
            for (RecurringRule r : resp.recurringRules) if (lww(r)) merged++;
            if (!resp.hasMore || resp.nextCursor == null) break;
            pull.cursor = resp.nextCursor;
        }

        return "同步完成，合并 " + merged + " 条变更";
    }

    private boolean isEmpty(SyncPayloads.PushRequest p) {
        return p.transactions.isEmpty() && p.accounts.isEmpty() && p.categories.isEmpty()
                && p.budgets.isEmpty() && p.templates.isEmpty() && p.recurringRules.isEmpty();
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
