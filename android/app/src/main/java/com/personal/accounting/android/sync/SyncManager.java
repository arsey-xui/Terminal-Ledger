package com.personal.accounting.android.sync;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.personal.accounting.android.AccountingApp;
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
import java.util.concurrent.TimeUnit;

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
                // 水位分两个方向、两个时钟域，不能混用同一个值：
                // · pull 水位是服务端的 serverTime（本次拿到的第一个 serverTime 减去重叠量）；
                // · push 水位是本机 last_modified 的最大值（减去重叠量）。
                long storedPull = stateRepo.lastSync(deviceId);
                long storedPush = stateRepo.pushWatermark(deviceId);
                long pullSince = storedPull <= 0L ? 0L : Math.max(0L, storedPull - PULL_OVERLAP_MS);
                long pushSince = storedPush <= 0L ? 0L
                        : Math.min(storedPush, System.currentTimeMillis() - PUSH_RECENT_MS);

                // 1) 收集本地变更：只取 push 水位之后的（原先取 0，等于每记一笔就推整本账）
                SyncPayloads.PushRequest push = new SyncPayloads.PushRequest();
                push.deviceId = deviceId;
                push.transactions.addAll(txRepo.listModifiedSince(pushSince));
                push.categories.addAll(catRepo.listModifiedSince(pushSince));
                push.accounts.addAll(acctRepo.listModifiedSince(pushSince));
                push.budgets.addAll(budgetRepo.listModifiedSince(pushSince));
                push.templates.addAll(tplRepo.listModifiedSince(pushSince));
                push.recurringRules.addAll(recurringRepo.listModifiedSince(pushSince));

                int pushedRows = rowCount(push);
                long maxUploaded = maxLastModified(push);

                // 2) push
                if (pushedRows > 0) {
                    // 周期规则也参与同步（规则跨设备共享，待确认项只在各自设备生成）
                    api.post("/api/sync/push", push, SyncPayloads.SyncResult.class);
                }

                // 3) pull 增量（水位为 0 时服务端只回最近 N 个月流水 + 全量字典）并 LWW 合并
                SyncPayloads.PullRequest pullReq = new SyncPayloads.PullRequest();
                pullReq.deviceId = deviceId;
                pullReq.since = pullSince;
                long firstServerTime = 0L;
                int merged = 0;
                boolean done = false;
                while (!done) {
                    SyncPayloads.PullResponse resp = api.post("/api/sync/pull", pullReq, SyncPayloads.PullResponse.class);
                    if (resp == null) {
                        SyncStatus.recordFailure(context, "服务端返回为空");
                        notifyUi(callback, false, "服务端返回为空");
                        return;
                    }
                    // 用**第一页**的 serverTime 做水位：它是最早读到的服务端时间，比后面几页保守
                    if (firstServerTime == 0L) firstServerTime = resp.serverTime;
                    for (Transaction t : resp.transactions) if (txRepo.upsertLww(t)) merged++;
                    for (Category c : resp.categories) if (catRepo.upsertLww(c)) merged++;
                    for (Account a : resp.accounts) if (acctRepo.upsertLww(a)) merged++;
                    for (Budget b : resp.budgets) if (budgetRepo.upsertLww(b)) merged++;
                    for (Template t : resp.templates) if (tplRepo.upsertLww(t)) merged++;
                    for (RecurringRule r : resp.recurringRules) if (recurringRepo.upsertLww(r)) merged++;
                    if (!resp.hasMore || resp.nextCursor == null) {
                        done = true;
                    } else {
                        pullReq.cursor = resp.nextCursor;
                    }
                }

                // 4) 水位只在整轮成功后落盘：中途失败保持旧水位，下次重来
                if (firstServerTime > 0L) stateRepo.saveLastSync(deviceId, firstServerTime);
                if (maxUploaded > 0L) stateRepo.savePushWatermark(deviceId, maxUploaded);

                // 结果统一记在这里：记账页/小组件触发的后台同步没有回调，也要能反映到状态位上
                SyncStatus.recordSuccess(context);
                notifyUi(callback, true, "同步完成：上传 " + pushedRows + " 条，合并 " + merged + " 条");
                Log.d(TAG, "sync done: pushed=" + pushedRows + " merged=" + merged
                        + " pullSince=" + pullSince + " pushSince=" + pushSince);
            } catch (Exception e) {
                Log.e(TAG, "sync failed", e);
                SyncStatus.recordFailure(context, e.getMessage());
                notifyUi(callback, false, "同步失败: " + e.getMessage());
            }
        });
    }

    /** 本次要推的记录里最新的 last_modified，作为下次的 push 水位。 */
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

    private void notifyUi(Callback cb, boolean ok, String msg) {
        if (cb != null) {
            new Handler(Looper.getMainLooper()).post(() -> cb.onDone(ok, msg));
        }
    }

    // ---------- 写入之后的自动同步 ----------

    /**
     * 写入之后静默同步的去抖窗口。
     *
     * <p>一次操作可能连着写多行（「重置分类」会写 14 行），逐行同步等于把同一份全量 push
     * 重复十几遍。窗口内没有新的写入才真正同步一次。</p>
     */
    private static final long WRITE_SYNC_DEBOUNCE_MS = 1200;

    /**
     * pull 水位的重叠量。
     *
     * <p>服务端的 {@code last_modified} 来自各个客户端的本地时钟，而 {@code since} 比的是
     * 服务端 {@code serverTime}。两个时钟域不完全一致，所以留一段重叠：宁可多拉一点
     * （LWW 合并是幂等的），也不要因为手机电脑差了几分钟就永久漏掉一批记录。</p>
     */
    private static final long PULL_OVERLAP_MS = TimeUnit.HOURS.toMillis(1);

    /**
     * push 时始终重推「最近这段时间」里的改动。
     *
     * <p>水位本身是精确推进的（存本次推上去的最大 last_modified），但取数起点再往前压一小时：
     * 边界上同一毫秒的改动、以及设备时钟往回跳之后的改动，都不会被永久漏掉。
     * 代价只是最近一小时内的改动会被重复推几次 —— LWW 幂等，无所谓。</p>
     */
    private static final long PUSH_RECENT_MS = TimeUnit.HOURS.toMillis(1);

    private static final Handler SCHEDULER = new Handler(Looper.getMainLooper());

    private static final Runnable SCHEDULED_SYNC = () -> {
        Context app = AccountingApp.appContext();
        if (app != null) new SyncManager(app).sync(null);
    };

    /**
     * 声明「刚写完本地数据」，安排一次去抖后的后台同步。
     *
     * <p>删除流水、改字典（分类/账户/预算/模板/周期规则）、确认周期记账这些操作原先都不触发同步，
     * 墓碑只留在本机：另一端下次拉取仍会拿到那条活记录，桌面端再编辑一次，被删的记录就复活了。</p>
     *
     * <p>没配后端地址时直接跳过 —— 离线状态下反复产生失败记录没有意义。</p>
     */
    public static void requestAfterWrite() {
        Context app = AccountingApp.appContext();
        if (app == null || !SyncConfig.isConfigured()) return;
        SCHEDULER.removeCallbacks(SCHEDULED_SYNC);
        SCHEDULER.postDelayed(SCHEDULED_SYNC, WRITE_SYNC_DEBOUNCE_MS);
    }

}
