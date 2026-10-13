package com.personal.accounting.android.sync;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.personal.accounting.android.data.AccountRepo;
import com.personal.accounting.android.data.BudgetRepo;
import com.personal.accounting.android.data.CategoryRepo;
import com.personal.accounting.android.data.RecurringRepo;
import com.personal.accounting.android.data.SyncStateRepo;
import com.personal.accounting.android.data.TemplateRepo;
import com.personal.accounting.android.data.TransactionRepo;
import com.personal.accounting.android.model.Account;
import com.personal.accounting.android.model.Budget;
import com.personal.accounting.android.model.Category;
import com.personal.accounting.android.model.RecurringRule;
import com.personal.accounting.android.model.Template;
import com.personal.accounting.android.model.Transaction;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 离线优先的同步器。
 *
 * <p>流程：</p>
 * <ol>
 *   <li>收集本地 {@code last_modified > 水位} 的变更（重点为流水，其余字典数据量很小也一并带上）；</li>
 *   <li>push 到云端，由云端执行 LWW 合并；</li>
 *   <li>按水位 pull 云端增量，客户端用相同的 LWW 规则本地合并；</li>
 *   <li>把水位更新为服务端时间，避免重复拉取。</li>
 * </ol>
 */
public class SyncManager {

    private static final String TAG = "SyncManager";

    private final Context context;
    private final String deviceId;
    private final TransactionRepo txRepo;
    private final CategoryRepo catRepo;
    private final AccountRepo acctRepo;
    private final BudgetRepo budgetRepo;
    private final TemplateRepo tplRepo;
    private final SyncStateRepo stateRepo;
    private final RecurringRepo recurringRepo;
    private final ApiClient api = new ApiClient();
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public SyncManager(Context context) {
        this.context = context.getApplicationContext();
        this.deviceId = SyncConfig.deviceId(context);
        this.txRepo = new TransactionRepo(this.context);
        this.catRepo = new CategoryRepo(this.context);
        this.acctRepo = new AccountRepo(this.context);
        this.budgetRepo = new BudgetRepo(this.context);
        this.tplRepo = new TemplateRepo(this.context);
        this.stateRepo = new SyncStateRepo(this.context);
        this.recurringRepo = new RecurringRepo(this.context);
    }

    public interface Callback {
        void onDone(boolean success, String message);
    }

    public void sync(Callback callback) {
        if (!SyncConfig.isConfigured()) {
            // 未填写后端地址：不联网，提示去设置里填写
            notifyUi(callback, false, "请先在「设置」里填写后端地址（BASE_URL）");
            return;
        }
        executor.execute(() -> {
            try {
                // 1) 收集本地变更
                SyncPayloads.PushRequest push = new SyncPayloads.PushRequest();
                push.deviceId = deviceId;
                // 全量（含删除墓碑）：个人数据量小，逐次对账最可靠，避免水位/时钟差异漏同步
                push.transactions.addAll(txRepo.listModifiedSince(0));
                push.categories.addAll(catRepo.listModifiedSince(0));
                push.accounts.addAll(acctRepo.listModifiedSince(0));
                push.budgets.addAll(budgetRepo.listModifiedSince(0));
                push.templates.addAll(tplRepo.listModifiedSince(0));
                push.recurringRules.addAll(recurringRepo.listModifiedSince(0));

                // 2) push
                if (!push.transactions.isEmpty() || !push.categories.isEmpty() || !push.accounts.isEmpty()
                        || !push.budgets.isEmpty() || !push.templates.isEmpty()
                        || !push.recurringRules.isEmpty()) {
                    // 周期规则也参与同步（规则跨设备共享，待确认项只在各自设备生成）
                    api.post("/api/sync/push", push, SyncPayloads.SyncResult.class);
                }

                // 3) pull 全量（近3个月窗口，分页循环）并 LWW 合并
                SyncPayloads.PullRequest pullReq = new SyncPayloads.PullRequest();
                pullReq.deviceId = deviceId;
                pullReq.since = 0L;
                boolean done = false;
                while (!done) {
                    SyncPayloads.PullResponse resp = api.post("/api/sync/pull", pullReq, SyncPayloads.PullResponse.class);
                    if (resp == null) {
                        SyncStatus.recordFailure(context, "服务端返回为空");
                        notifyUi(callback, false, "服务端返回为空");
                        return;
                    }
                    for (Transaction t : resp.transactions) txRepo.upsertLww(t);
                    for (Category c : resp.categories) catRepo.upsertLww(c);
                    for (Account a : resp.accounts) acctRepo.upsertLww(a);
                    for (Budget b : resp.budgets) budgetRepo.upsertLww(b);
                    for (Template t : resp.templates) tplRepo.upsertLww(t);
                    for (RecurringRule r : resp.recurringRules) recurringRepo.upsertLww(r);
                    if (!resp.hasMore || resp.nextCursor == null) {
                        done = true;
                    } else {
                        pullReq.cursor = resp.nextCursor;
                    }
                }
                // 结果统一记在这里：记账页/小组件触发的后台同步没有回调，也要能反映到状态位上
                SyncStatus.recordSuccess(context);
                notifyUi(callback, true, "同步完成");
                Log.d(TAG, "sync done (full)");
            } catch (Exception e) {
                Log.e(TAG, "sync failed", e);
                SyncStatus.recordFailure(context, e.getMessage());
                notifyUi(callback, false, "同步失败: " + e.getMessage());
            }
        });
    }

    private void notifyUi(Callback cb, boolean ok, String msg) {
        if (cb != null) {
            new Handler(Looper.getMainLooper()).post(() -> cb.onDone(ok, msg));
        }
    }

}
