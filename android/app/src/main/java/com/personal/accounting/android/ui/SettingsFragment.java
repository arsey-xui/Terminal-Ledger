package com.personal.accounting.android.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.personal.accounting.android.R;
import com.personal.accounting.android.data.AccountRepo;
import com.personal.accounting.android.data.BudgetRepo;
import com.personal.accounting.android.data.CategoryRepo;
import com.personal.accounting.android.model.Account;
import com.personal.accounting.android.model.Category;
import com.personal.accounting.android.sync.SyncConfig;
import com.personal.accounting.android.sync.SyncManager;
import com.personal.accounting.android.update.ApkUpdater;
import com.personal.accounting.android.update.UpdateClient;
import com.personal.accounting.android.update.UpdateConfig;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsFragment extends Fragment {

    private static final String PREFS = "sync_prefs";

    private EditText urlInput;
    private TextView statusView;
    private TextView versionView;
    private CategoryRepo catRepo;
    private AccountRepo accountRepo;

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

        v.findViewById(R.id.btn_sync_now).setOnClickListener(x ->
                new SyncManager(requireContext()).sync((ok, msg) -> {
                    statusView.setText(msg);
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
                }));

        v.findViewById(R.id.btn_export_csv).setOnClickListener(x ->
                Toast.makeText(requireContext(), "CSV 导出已实现于电脑端数据管理", Toast.LENGTH_SHORT).show());
        v.findViewById(R.id.btn_manage_category).setOnClickListener(x -> manageCategories());
        v.findViewById(R.id.btn_manage_account).setOnClickListener(x -> manageAccounts());
        v.findViewById(R.id.btn_reset_category).setOnClickListener(x -> resetCategories());
        v.findViewById(R.id.btn_reset_account).setOnClickListener(x -> resetAccounts());
        v.findViewById(R.id.btn_reset_budget).setOnClickListener(x -> resetBudgets());
        v.findViewById(R.id.btn_check_update).setOnClickListener(x -> checkUpdate());
        v.findViewById(R.id.btn_update_source).setOnClickListener(x -> configureUpdateSource());
        return v;
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

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
