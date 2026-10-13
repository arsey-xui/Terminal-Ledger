package com.personal.accounting.android.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.text.InputType;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.personal.accounting.android.AccountingApp;
import com.personal.accounting.android.R;
import com.personal.accounting.android.data.AccountRepo;
import com.personal.accounting.android.data.BackupService;
import com.personal.accounting.android.data.BudgetRepo;
import com.personal.accounting.android.data.CategoryRepo;
import com.personal.accounting.android.data.TransactionRepo;
import com.personal.accounting.android.data.Recurrence;
import com.personal.accounting.android.data.RecurringRepo;
import com.personal.accounting.android.model.Account;
import com.personal.accounting.android.model.Category;
import com.personal.accounting.android.model.RecurringPending;
import com.personal.accounting.android.model.RecurringRule;
import com.personal.accounting.android.sync.SyncConfig;
import com.personal.accounting.android.sync.SyncManager;
import com.personal.accounting.android.sync.SyncStatus;
import com.personal.accounting.android.update.ApkUpdater;
import com.personal.accounting.android.update.UpdateClient;
import com.personal.accounting.android.update.UpdateConfig;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsFragment extends Fragment {

    private static final String PREFS = "sync_prefs";

    /** 导出目标文件的用途：写 CSV 还是写加密备份。 */
    private enum ExportKind { CSV, BACKUP }

    private ExportKind pendingExport;

    /** 「深浅模式」按钮：文字要反映当前模式，切回来时由 onCreateView 重设。 */
    private Button themeButton;
    /** 导出备份时用户设的口令，等选完保存位置再写文件。 */
    private char[] pendingPassword;

    /** 选「保存到哪里」→ 回来写文件（Csv / 备份包共用）。 */
    private final ActivityResultLauncher<Intent> createFileLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                ExportKind kind = pendingExport;
                char[] password = pendingPassword;
                pendingExport = null;
                pendingPassword = null;
                if (result.getResultCode() != Activity.RESULT_OK
                        || result.getData() == null || result.getData().getData() == null || kind == null) {
                    return;
                }
                writeToFile(result.getData().getData(), kind, password);
            });

    /** 选一个备份文件 → 问口令 → 导入。 */
    private final ActivityResultLauncher<Intent> openFileLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK
                        || result.getData() == null || result.getData().getData() == null) {
                    return;
                }
                askPasswordThenRestore(result.getData().getData());
            });

    private EditText urlInput;
    private TextView statusView;
    private TextView versionView;
    private CategoryRepo catRepo;
    private AccountRepo accountRepo;
    private RecurringRepo recurringRepo;
    private LinearLayout recurringContainer;
    private Button syncRetryButton;

    /** 每 30 秒刷一次状态行，让「X 分钟前」自己往前走。 */
    private static final long SYNC_STATUS_TICK_MS = 30_000L;
    private final Runnable syncStatusTick = new Runnable() {
        @Override
        public void run() {
            refreshSyncStatus();
            mainHandler.postDelayed(this, SYNC_STATUS_TICK_MS);
        }
    };

    /** 版本检查走后台线程，避免网络请求卡 UI。 */
    private final ExecutorService updateExec = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_settings, container, false);
        urlInput = v.findViewById(R.id.url_input);
        statusView = v.findViewById(R.id.sync_status);
        versionView = v.findViewById(R.id.version_view);
        versionView.setText("当前版本：" + currentVersion());
        catRepo = new CategoryRepo(requireContext());
        accountRepo = new AccountRepo(requireContext());
        recurringRepo = new RecurringRepo(requireContext());
        recurringContainer = v.findViewById(R.id.recurring_container);

        SharedPreferences sp = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        urlInput.setText(SyncConfig.BASE_URL);

        v.findViewById(R.id.btn_save_url).setOnClickListener(x -> {
            String url = urlInput.getText().toString().trim();
            if (url.isEmpty()) {
                Toast.makeText(requireContext(), "请先填写后端地址，例如 http://192.168.1.100:8080", Toast.LENGTH_LONG).show();
                return;
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://" + url;
            }
            SyncConfig.BASE_URL = url;
            sp.edit().putString("base_url", url).apply();
            Toast.makeText(requireContext(), "已保存后端地址", Toast.LENGTH_SHORT).show();
        });

        syncRetryButton = v.findViewById(R.id.btn_sync_retry);
        v.findViewById(R.id.btn_sync_now).setOnClickListener(x -> startSync(true));
        syncRetryButton.setOnClickListener(x -> startSync(true));
        refreshSyncStatus();

        v.findViewById(R.id.btn_export_csv).setOnClickListener(x -> exportCsv());
        v.findViewById(R.id.btn_backup_export).setOnClickListener(x -> exportBackup());
        v.findViewById(R.id.btn_backup_import).setOnClickListener(x -> importBackup());
        v.findViewById(R.id.btn_manage_category).setOnClickListener(x -> manageCategories());
        v.findViewById(R.id.btn_manage_account).setOnClickListener(x -> manageAccounts());
        v.findViewById(R.id.btn_reset_category).setOnClickListener(x -> resetCategories());
        v.findViewById(R.id.btn_reset_account).setOnClickListener(x -> resetAccounts());
        v.findViewById(R.id.btn_reset_budget).setOnClickListener(x -> resetBudgets());
        v.findViewById(R.id.btn_check_update).setOnClickListener(x -> checkUpdate());
        v.findViewById(R.id.btn_update_source).setOnClickListener(x -> configureUpdateSource());
        v.findViewById(R.id.btn_add_recurring).setOnClickListener(x -> showRecurringDialog(null));
        themeButton = v.findViewById(R.id.btn_theme_mode);
        themeButton.setOnClickListener(x -> pickThemeMode());
        updateThemeButtonLabel();
        recurringRepo.materialize(java.time.LocalDate.now());
        renderRecurring();
        return v;
    }

    // ---------- 外观：深浅模式 ----------

    /**
     * 选择深浅模式。默认「跟随系统」；强制浅色/深色是为了绕开部分 ROM
     * （ColorOS、MIUI 等）的「强制深色」——它会在浅色界面上自作主张地反色，
     * 只反文字不反背景，导致白字压浅底看不清。强制指定模式后即可看到应用自己的配色。
     */
    private void pickThemeMode() {
        final String[] labels = {"跟随系统", "强制浅色", "强制深色"};
        int current = AccountingApp.storedMode();
        new AlertDialog.Builder(requireContext())
                .setTitle("深浅模式")
                .setSingleChoiceItems(labels, current, (dialog, which) -> {
                    AccountingApp.setThemeMode(which);
                    dialog.dismiss();
                    // 切换会重建 Activity，界面与按钮文字都会随之刷新
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateThemeButtonLabel() {
        if (themeButton == null) return;
        String label;
        switch (AccountingApp.storedMode()) {
            case AccountingApp.MODE_LIGHT:
                label = "强制浅色";
                break;
            case AccountingApp.MODE_DARK:
                label = "强制深色";
                break;
            default:
                label = "跟随系统";
                break;
        }
        themeButton.setText("深浅模式：" + label);
    }

    // ---------- 同步状态 ----------

    @Override
    public void onResume() {
        super.onResume();
        refreshSyncStatus();
        // 先清掉可能残留的回调，避免重复 post 叠起来
        mainHandler.removeCallbacks(syncStatusTick);
        mainHandler.postDelayed(syncStatusTick, SYNC_STATUS_TICK_MS);
    }

    @Override
    public void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(syncStatusTick);
    }

    /** 触发一次同步；手动点才弹提示，其它情况只更新状态行。 */
    private void startSync(boolean showToast) {
        statusView.setText("正在同步…");
        new SyncManager(requireContext()).sync((ok, msg) -> {
            if (!isAdded() || getActivity() == null || getActivity().isFinishing()) return;
            refreshSyncStatus();
            if (showToast) Toast.makeText(requireContext().getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
        });
    }

    /** 状态行 + 重试按钮：只有失败还没被一次成功覆盖掉时才露出重试。 */
    private void refreshSyncStatus() {
        if (statusView == null) return;
        statusView.setText(SyncStatus.describe(requireContext()));
        if (syncRetryButton != null) {
            syncRetryButton.setVisibility(SyncStatus.hasPendingFailure(requireContext())
                    ? View.VISIBLE : View.GONE);
        }
    }

    // ---------- 检查更新 ----------
    private String currentVersion() {
        try {
            return requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "未知";
        }
    }

    private void checkUpdate() {
        Toast.makeText(requireContext(), "正在检查更新…", Toast.LENGTH_SHORT).show();
        updateExec.execute(() -> {
            // 直接查 GitHub Releases（不需要后端在线）
            UpdateClient.Result result = UpdateClient.refresh(requireContext().getApplicationContext());
            final String message;
            if (result.release == null) {
                message = result.message;
            } else if (!UpdateClient.hasUpdate(requireContext(), result.release, currentVersion())) {
                message = "已是最新版本（" + currentVersion() + "）";
            } else {
                message = null;
            }
            if (message != null) {
                final String text = message;
                mainHandler.post(() -> Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show());
                return;
            }
            final UpdateClient.Release release = result.release;
            mainHandler.post(() -> showUpdateDialog(release));
        });
    }

    private void showUpdateDialog(UpdateClient.Release release) {
        boolean hasUrl = release.downloadUrl != null && !release.downloadUrl.trim().isEmpty();
        StringBuilder text = new StringBuilder();
        text.append("当前版本：").append(currentVersion()).append('\n');
        text.append("最新版本：").append(release.latestVersion).append('\n');
        if (release.notes != null && !release.notes.trim().isEmpty()) {
            text.append('\n').append(release.notes.trim());
        }
        text.append(hasUrl ? "\n\n点「立即更新」会在应用内下载并弹出安装界面。"
                : "\n\n该 Release 里没有匹配的安装包资产，请到 GitHub 发布页手动下载。");

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext())
                .setTitle("发现新版本")
                .setMessage(text.toString());
        if (hasUrl) {
            builder.setPositiveButton("立即更新", (d, w) -> downloadAndInstall(release.downloadUrl));
        }
        builder.setNegativeButton(hasUrl ? "关闭" : "好", null);
        builder.show();
    }

    /** 配置更新源（GitHub 仓库 owner/repo）——fork 后自建发布时需要改。 */
    private void configureUpdateSource() {
        EditText input = new EditText(requireContext());
        input.setHint("owner/repo，例如 zhangsan/Terminal-Ledger");
        input.setText(UpdateConfig.repo(requireContext()));
        int pad = dp(20);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        new AlertDialog.Builder(requireContext())
                .setTitle("更新源（GitHub 仓库）")
                .setMessage("检查更新直接读取 GitHub Releases，不需要后端在线。\n"
                        + "填写你的仓库（owner/repo），发版时把 apk 作为 Release 资产上传即可。")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    UpdateConfig.saveRepo(requireContext(), input.getText().toString());
                    UpdateConfig.clearCache(requireContext());
                    Toast.makeText(requireContext(),
                            "已保存更新源：" + UpdateConfig.repo(requireContext()), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 下载新版 apk（带进度提示），完成后交给系统安装界面。 */
    private void downloadAndInstall(String url) {
        final android.app.ProgressDialog progress = new android.app.ProgressDialog(requireContext());
        progress.setTitle("正在下载更新");
        progress.setMessage("准备中…");
        progress.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setCancelable(false);
        progress.show();

        updateExec.execute(() -> {
            File apk = null;
            String failure = null;
            try {
                apk = ApkUpdater.download(requireContext().getApplicationContext(), url, percent ->
                        mainHandler.post(() -> progress.setProgress(percent)));
            } catch (Exception e) {
                failure = "下载失败：" + e.getMessage();
            }
            final File downloaded = apk;
            final String error = failure;
            mainHandler.post(() -> {
                if (progress.isShowing()) progress.dismiss();
                if (error != null) {
                    Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(requireContext(), "下载完成，正在打开安装界面…", Toast.LENGTH_SHORT).show();
                ApkUpdater.install(requireActivity(), downloaded);
            });
        });
    }

    // ---------- 重置为初次安装状态 ----------
    private void resetCategories() {
        confirmReset("分类", "会删除你自建的全部分类，并恢复内置默认分类（支出：餐饮、交通、购物、住房、通讯、"
                        + "医疗、教育、娱乐、人情、其他；收入：工资、副业、红包、其他收入）；流水记录不会被删除。",
                () -> {
                    catRepo.resetToDefaults();
                    Toast.makeText(requireContext(), "已恢复 10 个支出分类、4 个收入分类", Toast.LENGTH_SHORT).show();
                });
    }

    private void resetAccounts() {
        confirmReset("账户", "会删除你自建的全部账户，并恢复内置默认账户（现金、储蓄卡、信用卡、微信、支付宝）；"
                        + "流水记录不会被删除。",
                () -> {
                    accountRepo.resetToDefaults();
                    Toast.makeText(requireContext(), "已恢复 5 个默认账户", Toast.LENGTH_SHORT).show();
                });
    }

    private void resetBudgets() {
        confirmReset("预算", "会清空所有分类的预算上限，回到「未设置上限」的状态；流水记录不会被删除。",
                () -> {
                    new BudgetRepo(requireContext()).resetToDefaults();
                    Toast.makeText(requireContext(), "预算已清空", Toast.LENGTH_SHORT).show();
                });
    }

    private void confirmReset(String what, String message, Runnable action) {
        new AlertDialog.Builder(requireContext())
                .setTitle("重置" + what + "？")
                .setMessage(message + "\n\n此操作影响本地数据，并会在下次同步时同步到云端。")
                .setPositiveButton("重置", (d, w) -> action.run())
                .setNegativeButton("取消", null)
                .show();
    }

    private void manageCategories() {
        // 同时管理支出与收入分类；支出整体排在收入之前
        List<Category> cats = catRepo.allExpenseFirst();
        if (cats.isEmpty()) {
            Toast.makeText(requireContext(), "暂无分类", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[cats.size()];
        for (int i = 0; i < cats.size(); i++) {
            Category c = cats.get(i);
            names[i] = "INCOME".equals(c.type) ? c.name + "（收入）" : c.name + "（支出）";
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("管理分类")
                .setItems(names, (d, which) -> {
                    Category c = cats.get(which);
                    confirmDelete(c.name, () -> {
                        catRepo.delete(c.clientId);
                        Toast.makeText(requireContext(), "已删除分类：" + c.name, Toast.LENGTH_SHORT).show();
                    });
                })
                .show();
    }

    private void manageAccounts() {
        List<Account> accs = accountRepo.list();
        if (accs.isEmpty()) {
            Toast.makeText(requireContext(), "暂无账户", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[accs.size()];
        for (int i = 0; i < accs.size(); i++) names[i] = accs.get(i).name;
        new AlertDialog.Builder(requireContext())
                .setTitle("管理账户")
                .setItems(names, (d, which) -> {
                    Account a = accs.get(which);
                    confirmDelete(a.name, () -> {
                        accountRepo.delete(a.clientId);
                        Toast.makeText(requireContext(), "已删除账户：" + a.name, Toast.LENGTH_SHORT).show();
                    });
                })
                .show();
    }

    private void confirmDelete(String name, Runnable action) {
        new AlertDialog.Builder(requireContext())
                .setTitle("删除确认")
                .setMessage("删除「" + name + "」？")
                .setPositiveButton("删除", (d, w) -> action.run())
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- 周期记账（自动续费） ----------

    /** 渲染「待确认清单 + 规则列表」。 */
    private void renderRecurring() {
        if (recurringContainer == null) return;
        recurringContainer.removeAllViews();

        List<RecurringPending> pending = recurringRepo.listPending();
        TextView pendingTitle = sectionTitle("待确认（" + pending.size() + "）");
        recurringContainer.addView(pendingTitle);
        if (pending.isEmpty()) {
            recurringContainer.addView(hint("暂无到期的周期记账"));
        } else {
            for (RecurringPending p : pending) recurringContainer.addView(pendingRow(p));
        }

        List<RecurringRule> rules = recurringRepo.list();
        recurringContainer.addView(sectionTitle("规则（" + rules.size() + "）"));
        if (rules.isEmpty()) {
            recurringContainer.addView(hint("还没有周期规则，点下面的「新建周期规则」添加，"
                    + "例如每月 1 日房租、每 30 天某个会员续费"));
        } else {
            for (RecurringRule r : rules) recurringContainer.addView(ruleRow(r));
        }
    }

    private TextView sectionTitle(String text) {
        TextView tv = new TextView(requireContext());
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setPadding(0, dp(10), 0, dp(2));
        tv.setTextColor(getResources().getColor(R.color.text_primary));
        return tv;
    }

    private TextView hint(String text) {
        TextView tv = new TextView(requireContext());
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(getResources().getColor(R.color.text_secondary));
        return tv;
    }

    private View pendingRow(RecurringPending p) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView info = new TextView(requireContext());
        String cat = catRepo.findActive(p.categoryClientId) == null ? "未分类"
                : catRepo.findActive(p.categoryClientId).name;
        String sign = "INCOME".equals(p.type) ? "收入" : "支出";
        info.setText(p.date + "  " + sign + " " + String.format("%.2f", p.amount)
                + "  " + cat + (p.note == null || p.note.isBlank() ? "" : "  · " + p.note));
        info.setTextColor(getResources().getColor(R.color.text_primary));
        row.addView(info);

        LinearLayout actions = new LinearLayout(requireContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button confirm = new Button(requireContext());
        confirm.setText("确认入账");
        confirm.setOnClickListener(x -> confirmPending(p));
        Button edit = new Button(requireContext());
        edit.setText("改金额后确认");
        edit.setOnClickListener(x -> editAndConfirmPending(p));
        Button skip = new Button(requireContext());
        skip.setText("忽略本期");
        skip.setOnClickListener(x -> {
            recurringRepo.skip(p.clientId);
            renderRecurring();
        });
        actions.addView(confirm);
        actions.addView(edit);
        actions.addView(skip);
        row.addView(actions);
        return row;
    }

    private View ruleRow(RecurringRule r) {
        // 竖排：说明文字独占整行宽度，按钮另起一行，和上面的 pendingRow 一致。
        // 原来把文字放在横向 LinearLayout 里用 0dp + weight=1 与两个按钮并排：
        // 横向布局首轮测量会跳过这种加权子视图，行高实际按按钮高度定，
        // 文字换到第 3、4 行时末尾会被裁掉，且滚动也看不到（外层是竖排 ScrollView）。
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView info = new TextView(requireContext());
        String cat = catRepo.findActive(r.categoryClientId) == null ? "未分类"
                : catRepo.findActive(r.categoryClientId).name;
        String acct = accountRepo.find(r.accountClientId) == null ? "默认账户"
                : accountRepo.find(r.accountClientId).name;
        info.setText(r.name + "  " + String.format("%.2f", r.amount) + " 元\n"
                + Recurrence.describe(r) + " · " + cat + " · " + acct
                + (r.enabled ? "" : "（已停用）"));
        info.setTextColor(getResources().getColor(R.color.text_primary));
        info.setTextSize(13f);
        row.addView(info);

        LinearLayout actions = new LinearLayout(requireContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button edit = new Button(requireContext());
        edit.setText("编辑");
        edit.setOnClickListener(x -> showRecurringDialog(r));
        actions.addView(edit);

        Button del = new Button(requireContext());
        del.setText("删除");
        del.setOnClickListener(x -> confirmDelete(r.name, () -> {
            recurringRepo.delete(r.clientId);
            Toast.makeText(requireContext(), "已删除规则：" + r.name, Toast.LENGTH_SHORT).show();
            renderRecurring();
        }));
        actions.addView(del);

        row.addView(actions);
        return row;
    }

    private void confirmPending(RecurringPending p) {
        recurringRepo.confirm(p.clientId, null);
        Toast.makeText(requireContext(), "已入账：" + p.date + " " + String.format("%.2f", p.amount) + " 元",
                Toast.LENGTH_SHORT).show();
        renderRecurring();
    }

    private void editAndConfirmPending(RecurringPending p) {
        EditText input = new EditText(requireContext());
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(String.format("%.2f", p.amount));
        new AlertDialog.Builder(requireContext())
                .setTitle("确认金额（" + p.date + "）")
                .setView(input)
                .setPositiveButton("确认入账", (d, w) -> {
                    double amount = p.amount;
                    try {
                        amount = Double.parseDouble(input.getText().toString().trim());
                    } catch (Exception ignore) {
                        // 解析失败就沿用原金额
                    }
                    recurringRepo.confirm(p.clientId, amount);
                    Toast.makeText(requireContext(), "已入账：" + String.format("%.2f", amount) + " 元",
                            Toast.LENGTH_SHORT).show();
                    renderRecurring();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 弹窗里「类型 / 分类 / 账户」这类"点一下就能选"的行：
     * 等宽 + 内边距 + 边框 + 浅底 + 左侧强调色竖条，让它们看起来像按钮而不是普通文字。
     */
    private TextView pickerRow() {
        TextView tv = new TextView(requireContext());
        tv.setTextSize(15f);
        tv.setTextColor(getResources().getColor(R.color.text_primary));
        tv.setPadding(dp(14), dp(12), dp(14), dp(12));
        tv.setBackground(pickerRowBackground());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** 行背景：浅灰底 + 深灰描边 + 左侧 4dp 强调色竖条（用 LayerDrawable 拼出来）。 */
    private android.graphics.drawable.Drawable pickerRowBackground() {
        android.graphics.drawable.GradientDrawable body = new android.graphics.drawable.GradientDrawable();
        body.setColor(getResources().getColor(R.color.card));
        body.setStroke(dp(1), getResources().getColor(R.color.hairline));
        body.setCornerRadius(dp(4));

        android.graphics.drawable.GradientDrawable accentBar = new android.graphics.drawable.GradientDrawable();
        accentBar.setColor(getResources().getColor(R.color.accent));
        accentBar.setCornerRadius(dp(2));
        accentBar.setSize(dp(4), ViewGroup.LayoutParams.MATCH_PARENT);

        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{body, accentBar});
        layers.setLayerInset(1, dp(1), dp(1), -1, dp(1));
        layers.setLayerGravity(1, android.view.Gravity.START | android.view.Gravity.FILL_VERTICAL);
        return layers;
    }

    /**
     * 新建 / 编辑规则。规则字段较多，用程序化布局而不是 XML，避免再造一个 layout 文件。
     */
    private void showRecurringDialog(@Nullable RecurringRule editing) {
        boolean isNew = editing == null;
        RecurringRule rule = isNew ? new RecurringRule() : editing;

        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));

        EditText nameInput = new EditText(requireContext());
        nameInput.setHint("名称，如「房租」「视频会员」");
        if (!isNew) nameInput.setText(rule.name);
        box.addView(nameInput);

        EditText amountInput = new EditText(requireContext());
        amountInput.setHint("金额");
        amountInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (!isNew) amountInput.setText(String.format("%.2f", rule.amount));
        box.addView(amountInput);

        EditText countInput = new EditText(requireContext());
        countInput.setHint("每隔几个单位（如 30）");
        countInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        countInput.setText(String.valueOf(isNew ? 1 : Math.max(1, rule.intervalCount)));
        box.addView(countInput);

        android.widget.Spinner unitSpinner = new android.widget.Spinner(requireContext());
        String[] units = {"月", "周", "天"};
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, units);
        unitSpinner.setAdapter(adapter);
        if (!isNew) {
            if ("WEEK".equals(rule.intervalUnit)) unitSpinner.setSelection(1);
            else if ("DAY".equals(rule.intervalUnit)) unitSpinner.setSelection(2);
        }
        box.addView(unitSpinner);

        EditText anchorInput = new EditText(requireContext());
        anchorInput.setHint("首次应记日期 yyyy-MM-dd（也可理解为周期起点）");
        anchorInput.setText(isNew ? Recurrence.format(java.time.LocalDate.now()) : rule.anchorDate);
        box.addView(anchorInput);

        final String[] chosenCategory = {rule.categoryClientId};
        final String[] chosenAccount = {rule.accountClientId};
        final boolean[] expense = {isNew || !"INCOME".equals(rule.type)};

        java.util.List<Category> cats = expense[0] ? catRepo.listExpense() : catRepo.listIncome();
        java.util.List<Account> accounts = accountRepo.list();
        if (isNew) {
            if (!cats.isEmpty()) chosenCategory[0] = cats.get(0).clientId;
            if (!accounts.isEmpty()) chosenAccount[0] = accounts.get(0).clientId;
            rule.type = "EXPENSE";
        }

        // 类型 / 分类 / 账户这三行是弹窗里最需要点击的地方，做成带框的卡片式按钮，别再是一行灰字
        TextView typeRow = pickerRow();
        final Runnable refreshType = () -> typeRow.setText("类型　" + (expense[0] ? "支出" : "收入") + "　（点击切换）");
        refreshType.run();
        box.addView(typeRow);

        TextView categoryRow = pickerRow();
        final Runnable refreshCategory = () -> {
            Category c = catRepo.findActive(chosenCategory[0]);
            categoryRow.setText("分类　" + (c == null ? "请选择" : c.name) + "　（点击选择）");
        };
        refreshCategory.run();
        box.addView(categoryRow);

        TextView accountRow = pickerRow();
        final Runnable refreshAccount = () -> {
            Account a = accountRepo.find(chosenAccount[0]);
            accountRow.setText("账户　" + (a == null ? "请选择" : a.name) + "　（点击选择）");
        };
        refreshAccount.run();
        box.addView(accountRow);

        EditText noteInput = new EditText(requireContext());
        noteInput.setHint("备注（可空）");
        if (!isNew) noteInput.setText(rule.note);
        box.addView(noteInput);

        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(isNew ? "新建周期规则" : "编辑周期规则")
                .setView(scroll)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();

        typeRow.setOnClickListener(x -> {
            expense[0] = !expense[0];
            refreshType.run();
            java.util.List<Category> list = expense[0] ? catRepo.listExpense() : catRepo.listIncome();
            chosenCategory[0] = list.isEmpty() ? null : list.get(0).clientId;
            refreshCategory.run();
        });
        categoryRow.setOnClickListener(x -> {
            java.util.List<Category> list = expense[0] ? catRepo.listExpense() : catRepo.listIncome();
            if (list.isEmpty()) {
                Toast.makeText(requireContext(), "暂无分类，请先在「管理分类」里添加", Toast.LENGTH_SHORT).show();
                return;
            }
            String[] names = new String[list.size()];
            for (int i = 0; i < list.size(); i++) names[i] = list.get(i).name;
            new AlertDialog.Builder(requireContext())
                    .setTitle("选择分类")
                    .setItems(names, (d, which) -> {
                        chosenCategory[0] = list.get(which).clientId;
                        refreshCategory.run();
                    })
                    .show();
        });
        accountRow.setOnClickListener(x -> {
            if (accounts.isEmpty()) {
                Toast.makeText(requireContext(), "暂无账户，请先在「管理账户」里添加", Toast.LENGTH_SHORT).show();
                return;
            }
            String[] names = new String[accounts.size()];
            for (int i = 0; i < accounts.size(); i++) names[i] = accounts.get(i).name;
            new AlertDialog.Builder(requireContext())
                    .setTitle("选择账户")
                    .setItems(names, (d, which) -> {
                        chosenAccount[0] = accounts.get(which).clientId;
                        refreshAccount.run();
                    })
                    .show();
        });

        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(y -> {
            String name = nameInput.getText().toString().trim();
            double amount;
            int count;
            java.time.LocalDate anchor = Recurrence.parse(anchorInput.getText().toString().trim());
            try {
                amount = Double.parseDouble(amountInput.getText().toString().trim());
            } catch (Exception e) {
                Toast.makeText(requireContext(), "请填写正确的金额", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                count = Math.max(1, Integer.parseInt(countInput.getText().toString().trim()));
            } catch (Exception e) {
                Toast.makeText(requireContext(), "请填写正确的间隔数量，如 1、30", Toast.LENGTH_SHORT).show();
                return;
            }
            if (name.isEmpty()) {
                Toast.makeText(requireContext(), "请填写名称", Toast.LENGTH_SHORT).show();
                return;
            }
            if (amount <= 0) {
                Toast.makeText(requireContext(), "金额需要大于 0", Toast.LENGTH_SHORT).show();
                return;
            }
            if (anchor == null) {
                Toast.makeText(requireContext(), "日期格式应为 yyyy-MM-dd", Toast.LENGTH_SHORT).show();
                return;
            }
            rule.name = name;
            rule.amount = amount;
            rule.type = expense[0] ? "EXPENSE" : "INCOME";
            rule.categoryClientId = chosenCategory[0];
            rule.accountClientId = chosenAccount[0];
            rule.note = noteInput.getText().toString().trim();
            rule.intervalCount = count;
            rule.intervalUnit = unitSpinner.getSelectedItemPosition() == 1 ? "WEEK"
                    : unitSpinner.getSelectedItemPosition() == 2 ? "DAY" : "MONTH";
            rule.anchorDate = Recurrence.format(anchor);
            rule.enabled = true;
            recurringRepo.save(rule);
            recurringRepo.materialize(java.time.LocalDate.now());
            Toast.makeText(requireContext(), "已保存规则：" + rule.name, Toast.LENGTH_SHORT).show();
            renderRecurring();
            dialog.dismiss();
        }));
        dialog.show();
    }

    // ---------- 备份与导出 ----------

    /** 导出 CSV：列与电脑端一致，只含流水明细。 */
    private void exportCsv() {
        pendingExport = ExportKind.CSV;
        createFileLauncher.launch(createDocumentIntent("text/csv", "TerminalLedger-" + stamp() + ".csv"));
    }

    /** 导出加密备份：先设口令，再选保存位置。 */
    private void exportBackup() {
        EditText password = passwordField("设置口令（至少 6 位）");
        EditText confirm = passwordField("再输一次");

        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        box.setPadding(pad, 0, pad, 0);
        box.addView(password);
        box.addView(confirm);

        new AlertDialog.Builder(requireContext())
                .setTitle("导出备份")
                .setMessage("备份包含全部流水、分类、账户、预算、模板和周期规则，用口令加密（AES-256）。\n"
                        + "口令丢了这份文件就打不开了，请自己记牢；格式与电脑端一致，可以互相恢复。")
                .setView(box)
                .setPositiveButton("选保存位置", (d, w) -> {
                    String p1 = password.getText().toString();
                    String p2 = confirm.getText().toString();
                    if (p1.length() < 6) {
                        Toast.makeText(requireContext(), "口令至少 6 位", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!p1.equals(p2)) {
                        Toast.makeText(requireContext(), "两次输入的口令不一致", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    pendingPassword = p1.toCharArray();
                    pendingExport = ExportKind.BACKUP;
                    createFileLauncher.launch(createDocumentIntent("application/zip",
                            "TerminalLedger-backup-" + stamp() + ".zip"));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 导入备份：选文件 → 问口令 → 合并回本机。 */
    private void importBackup() {
        // 备份文件是加密后的字节，扩展名和真实 MIME 都不一定，所以不限类型
        openFileLauncher.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*"));
    }

    private void askPasswordThenRestore(Uri source) {
        EditText password = passwordField("备份口令");
        int pad = dp(24);
        password.setPadding(pad, pad / 2, pad, pad / 2);
        new AlertDialog.Builder(requireContext())
                .setTitle("导入备份")
                .setMessage("会把备份里的数据合并回本机：缺的记录补上，同一条按修改时间取较新的那条。\n"
                        + "不会删除你本机现有的记录。")
                .setView(password)
                .setPositiveButton("导入", (d, w) ->
                        runRestore(source, password.getText().toString().toCharArray()))
                .setNegativeButton("取消", null)
                .show();
    }

    private void runRestore(Uri source, char[] password) {
        Context app = requireContext().getApplicationContext();
        Toast.makeText(requireContext(), "正在导入…", Toast.LENGTH_SHORT).show();
        updateExec.execute(() -> {
            try (InputStream in = app.getContentResolver().openInputStream(source)) {
                if (in == null) throw new IOException("无法读取所选文件");
                BackupService.RestoreResult result = BackupService.restore(app, in, password);
                mainHandler.post(() -> {
                    if (!isAdded() || getActivity() == null || getActivity().isFinishing()) return;
                    renderRecurring();   // 周期规则可能一起回来了，刷新列表
                    Toast.makeText(requireContext().getApplicationContext(), "导入完成：" + result.summary(),
                            Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                postToast("导入失败：" + e.getMessage());
            }
        });
    }

    private void writeToFile(Uri target, ExportKind kind, char[] password) {
        Context app = requireContext().getApplicationContext();
        Toast.makeText(requireContext(), "正在导出…", Toast.LENGTH_SHORT).show();
        updateExec.execute(() -> {
            try (OutputStream out = app.getContentResolver().openOutputStream(target, "wt")) {
                if (out == null) throw new IOException("无法写入所选位置");
                if (kind == ExportKind.CSV) {
                    BackupService.exportCsv(out, new TransactionRepo(app).listModifiedSince(0));
                } else {
                    BackupService.writeBackup(app, out, password);
                }
                postToast("已导出");
            } catch (Exception e) {
                postToast("导出失败：" + e.getMessage());
            }
        });
    }

    private void postToast(String message) {
        mainHandler.post(() -> {
            if (!isAdded() || getActivity() == null || getActivity().isFinishing()) return;
            Toast.makeText(requireContext().getApplicationContext(), message, Toast.LENGTH_LONG).show();
        });
    }

    private EditText passwordField(String hint) {
        EditText input = new EditText(requireContext());
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return input;
    }

    private Intent createDocumentIntent(String mimeType, String fileName) {
        return new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mimeType)
                .putExtra(Intent.EXTRA_TITLE, fileName);
    }

    private static String stamp() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
