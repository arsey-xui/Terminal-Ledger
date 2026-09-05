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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.personal.accounting.android.R;
import com.personal.accounting.android.data.AccountRepo;
import com.personal.accounting.android.data.CategoryRepo;
import com.personal.accounting.android.model.Account;
import com.personal.accounting.android.model.Category;
import com.personal.accounting.android.sync.AiPayloads;
import com.personal.accounting.android.sync.ApiClient;
import com.personal.accounting.android.sync.SyncConfig;
import com.personal.accounting.android.sync.SyncManager;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsFragment extends Fragment {

    private static final String PREFS = "sync_prefs";

    private EditText urlInput;
    private TextView statusView;
    private CategoryRepo catRepo;
    private AccountRepo accountRepo;

    // AI 消费分析设置（首次向导后保存，不再提示）
    private static final String AI_PREFS = "ai_prefs";
    private static final String AI_DONE = "ai_done";
    private static final String AI_LLM = "ai_use_llm";
    private static final String AI_KEY = "ai_api_key";
    private static final String AI_BASE = "ai_base_url";
    private static final String AI_MODEL = "ai_model";
    private final ExecutorService aiExec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_settings, container, false);
        urlInput = v.findViewById(R.id.url_input);
        statusView = v.findViewById(R.id.sync_status);
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
        v.findViewById(R.id.btn_message_voice).setOnClickListener(x -> onAiReport());
        return v;
    }

    private void manageCategories() {
        // 同时管理支出与收入分类
        List<Category> cats = catRepo.all();
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
    // ---------- AI 消费分析：首次向导 + 真调用 ----------
    private void onAiReport() {
        if (!SyncConfig.isConfigured()) {
            Toast.makeText(requireContext(), "请先在「云端地址」填写后端地址", Toast.LENGTH_LONG).show();
            return;
        }
        SharedPreferences ai = requireContext().getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE);
        if (!ai.getBoolean(AI_DONE, false)) {
            promptAiSetup(ai);
            return;
        }
        runAiReport(ai);
    }

    private void promptAiSetup(SharedPreferences ai) {
        new AlertDialog.Builder(requireContext())
                .setTitle("AI 消费分析")
                .setMessage("AI 分析默认使用内置规则引擎。\n是否已有自己的 API Key？")
                .setPositiveButton("有，填 Key", (d, w) -> promptAiKey(ai))
                .setNegativeButton("用规则引擎", (d, w) -> {
                    ai.edit().putBoolean(AI_DONE, true).putBoolean(AI_LLM, false).apply();
                    runAiReport(ai);
                })
                .show();
    }

    private void promptAiKey(SharedPreferences ai) {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(32), 0, dp(32), 0);
        EditText key = new EditText(requireContext());
        key.setHint("API Key（如 sk-...）");
        EditText base = new EditText(requireContext());
        base.setHint("接口地址（默认 api.openai.com）");
        base.setText("https://api.openai.com/v1");
        EditText model = new EditText(requireContext());
        model.setHint("模型（默认 gpt-4o-mini）");
        model.setText("gpt-4o-mini");
        box.addView(key);
        box.addView(base);
        box.addView(model);
        new AlertDialog.Builder(requireContext())
                .setTitle("填写 API Key")
                .setView(box)
                .setPositiveButton("确定", (d, w) -> {
                    String k = key.getText().toString().trim();
                    if (k.isEmpty()) {
                        ai.edit().putBoolean(AI_DONE, true).putBoolean(AI_LLM, false).apply();
                        Toast.makeText(requireContext(), "未填 Key，已用规则引擎", Toast.LENGTH_SHORT).show();
                    } else {
                        ai.edit().putBoolean(AI_DONE, true).putBoolean(AI_LLM, true)
                                .putString(AI_KEY, k)
                                .putString(AI_BASE, base.getText().toString().trim())
                                .putString(AI_MODEL, model.getText().toString().trim())
                                .apply();
                    }
                    runAiReport(ai);
                })
                .setNegativeButton("取消", (d, w) -> {
                    ai.edit().putBoolean(AI_DONE, true).putBoolean(AI_LLM, false).apply();
                    runAiReport(ai);
                })
                .show();
    }

    private void runAiReport(SharedPreferences ai) {
        boolean useLlm = ai.getBoolean(AI_LLM, false);
        String key = ai.getString(AI_KEY, "");
        String baseUrl = ai.getString(AI_BASE, "https://api.openai.com/v1");
        String model = ai.getString(AI_MODEL, "gpt-4o-mini");
        LocalDate now = LocalDate.now();
        AiPayloads.Request req = new AiPayloads.Request();
        req.year = now.getYear();
        req.month = now.getMonthValue();
        req.engine = useLlm ? "LLM" : "RULE";
        if (useLlm && !key.isEmpty()) {
            req.apiKey = key;
            req.baseUrl = baseUrl;
            req.model = model;
        }
        aiExec.execute(() -> {
            String result;
            String engine;
            try {
                AiPayloads.Response resp = new ApiClient().post("/api/report/ai", req, AiPayloads.Response.class);
                result = resp.report;
                engine = resp.engine;
            } catch (Exception e) {
                result = "AI 请求失败：" + e.getMessage();
                engine = "FAIL";
            }
            final String r = result;
            final String eng = engine;
            main.post(() -> {
                TextView tv = new TextView(requireContext());
                tv.setText(r);
                tv.setTextSize(14);
                tv.setPadding(dp(24), 0, dp(24), dp(16));
                new AlertDialog.Builder(requireContext())
                        .setTitle("AI 消费分析（" + eng + "）")
                        .setView(tv)
                        .setPositiveButton("好", null)
                        .show();
            });
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
