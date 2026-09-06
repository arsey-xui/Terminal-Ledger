package com.personal.accounting.android.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.personal.accounting.android.R;
import com.personal.accounting.android.data.AccountRepo;
import com.personal.accounting.android.data.BudgetRepo;
import com.personal.accounting.android.data.CategoryRepo;
import com.personal.accounting.android.data.TransactionRepo;
import com.personal.accounting.android.model.Account;
import com.personal.accounting.android.model.Budget;
import com.personal.accounting.android.model.Category;
import com.personal.accounting.android.model.Transaction;
import com.personal.accounting.android.sync.SyncManager;
import com.personal.accounting.android.voice.VoiceInputParser;
import com.personal.accounting.android.voice.VoiceRecognitionHelper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 快速记账页：数字键盘 + 分类快捷图标 + 账户选择 + 语音入口。 */
public class RecordFragment extends Fragment {

    private static final int REQ_VOICE = 1001;
    private static final int REQ_RECORD_AUDIO = 2001;

    private EditText amountInput;
    private EditText noteInput;
    private LinearLayout categoryBar;
    private TextView dateView;
    private Button accountButton;
    private Switch incomeSwitch;
    private CategoryRepo catRepo;
    private TransactionRepo txRepo;
    private BudgetRepo budgetRepo;
    private AccountRepo accountRepo;
    private String selectedCategoryId;
    private String selectedAccountId;
    private boolean isIncome = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_record, container, false);
        amountInput = v.findViewById(R.id.amount_input);
        noteInput = v.findViewById(R.id.note_input);
        categoryBar = v.findViewById(R.id.category_bar);
        dateView = v.findViewById(R.id.date_view);
        accountButton = v.findViewById(R.id.account_button);
        incomeSwitch = v.findViewById(R.id.sw_income);
        catRepo = new CategoryRepo(requireContext());
        txRepo = new TransactionRepo(requireContext());
        budgetRepo = new BudgetRepo(requireContext());
        accountRepo = new AccountRepo(requireContext());

        dateView.setText("今天 · " + LocalDate.now());
        buildCategoryBar();
        refreshAccountButton();

        accountButton.setOnClickListener(x -> showAccountPicker());
        v.findViewById(R.id.btn_add_account).setOnClickListener(x -> addAccountDialog());
        incomeSwitch.setOnCheckedChangeListener((b, checked) -> {
            isIncome = checked;
            buildCategoryBar();
        });
        v.findViewById(R.id.btn_voice).setOnClickListener(this::startVoice);
        v.findViewById(R.id.btn_save).setOnClickListener(x -> save());

        // 本月概览
        double[] s = txRepo.monthSummary(LocalDate.now().getYear(), LocalDate.now().getMonthValue());
        ((TextView) v.findViewById(R.id.summary_income)).setText(String.format("¥%.2f", s[0]));
        ((TextView) v.findViewById(R.id.summary_expense)).setText(String.format("¥%.2f", s[1]));
        ((TextView) v.findViewById(R.id.summary_balance)).setText(String.format("¥%.2f", s[2]));
        return v;
    }

    // ---------- 分类 ----------
    private void buildCategoryBar() {
        categoryBar.removeAllViews();
        List<Category> cats = isIncome ? catRepo.listIncome() : catRepo.listExpense();
        selectedCategoryId = null; // 切换 支出/收入 后需重新选分类
        for (Category c : cats) {
            Button b = new Button(requireContext());
            b.setText(c.icon == null ? c.name : c.icon + "\n" + c.name);
            b.setAllCaps(false);
            b.setTextColor(Color.parseColor("#1A1A1A"));
            b.setBackgroundResource(R.drawable.category_chip);
            b.setTag(c.clientId);
            b.setOnClickListener(view -> {
                selectedCategoryId = c.clientId;
                selectCategory(c.clientId);
                Toast.makeText(requireContext(), "分类：" + c.name, Toast.LENGTH_SHORT).show();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(4, 4, 4, 4);
            categoryBar.addView(b, lp);
        }
        // 追加“新增分类”按钮
        Button add = new Button(requireContext());
        add.setText("＋ 新增");
        add.setAllCaps(false);
        add.setTextColor(Color.parseColor("#1A1A1A"));
        add.setBackgroundResource(R.drawable.category_chip);
        add.setOnClickListener(x -> addCategoryDialog());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(4, 4, 4, 4);
        categoryBar.addView(add, lp);
    }

    private void selectCategory(String id) {
        for (int i = 0; i < categoryBar.getChildCount(); i++) {
            View child = categoryBar.getChildAt(i);
            child.setSelected(id.equals(child.getTag()));
        }
    }

    private void addCategoryDialog() {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), 0, dp(24), 0);
        EditText nameInput = new EditText(requireContext());
        nameInput.setHint("分类名称，如：宠物");
        EditText iconInput = new EditText(requireContext());
        iconInput.setHint("图标 emoji（可留空），如：🐱");
        box.addView(nameInput);
        box.addView(iconInput);
        new AlertDialog.Builder(requireContext())
                .setTitle(isIncome ? "新增收入分类" : "新增支出分类")
                .setView(box)
                .setPositiveButton("确定", (d, w) -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) return;
                    String icon = iconInput.getText().toString().trim();
                    Category c = new Category();
                    c.clientId = UUID.randomUUID().toString();
                    c.name = name;
                    c.icon = icon.isEmpty() ? null : icon;
                    c.type = isIncome ? "INCOME" : "EXPENSE";
                    c.lastModified = System.currentTimeMillis();
                    c.deleted = false;
                    catRepo.upsertLww(c);
                    buildCategoryBar();
                    selectedCategoryId = c.clientId;
                    Toast.makeText(requireContext(), "已新增分类：" + name, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int dp(int v) {
        return Math.round(requireContext().getResources().getDisplayMetrics().density * v);
    }

    private void matchCategory(String name) {
        List<Category> cats = isIncome ? catRepo.listIncome() : catRepo.listExpense();
        for (Category c : cats) {
            if (name.equals(c.name)) {
                selectedCategoryId = c.clientId;
                return;
            }
        }
        String fallback = isIncome ? "其他收入" : "其他";
        for (Category c : cats) {
            if (fallback.equals(c.name)) {
                selectedCategoryId = c.clientId;
                return;
            }
        }
    }

    // ---------- 账户 ----------
    private void refreshAccountButton() {
        List<Account> accounts = accountRepo.list();
        if (!accounts.isEmpty()) {
            if (selectedAccountId == null) selectedAccountId = accounts.get(0).clientId;
            String name = null;
            for (Account a : accounts) if (a.clientId.equals(selectedAccountId)) name = a.name;
            accountButton.setText("账户：" + (name == null ? accounts.get(0).name : name));
        } else {
            selectedAccountId = null;
            accountButton.setText("账户：请先新增");
        }
    }

    private void showAccountPicker() {
        List<Account> accounts = accountRepo.list();
        List<String> names = new ArrayList<>();
        for (Account a : accounts) names.add(a.name);
        names.add("＋ 新增账户");
        String[] items = names.toArray(new String[0]);
        new AlertDialog.Builder(requireContext())
                .setTitle("选择账户")
                .setItems(items, (d, which) -> {
                    if (which == items.length - 1) {
                        addAccountDialog();
                    } else {
                        selectedAccountId = accounts.get(which).clientId;
                        refreshAccountButton();
                    }
                })
                .show();
    }

    private void addAccountDialog() {
        EditText input = new EditText(requireContext());
        input.setHint("账户名，如：招商银行卡");
        new AlertDialog.Builder(requireContext())
                .setTitle("新增账户")
                .setView(input)
                .setPositiveButton("确定", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    Account a = new Account();
                    a.clientId = UUID.randomUUID().toString();
                    a.name = name;
                    a.type = "OTHER";
                    a.lastModified = System.currentTimeMillis();
                    a.deleted = false;
                    accountRepo.upsertLww(a);
                    selectedAccountId = a.clientId;
                    refreshAccountButton();
                    Toast.makeText(requireContext(), "已新增账户：" + name, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- 语音 ----------
    private void startVoice(View v) {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
            return;
        }
        boolean ok = VoiceRecognitionHelper.start(requireActivity(), REQ_VOICE, null);
        if (!ok) {
            showManualInputDialog("当前设备没有可用的语音识别服务，已切换为手动输入");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startVoice(null);
        } else if (requestCode == REQ_RECORD_AUDIO) {
            Toast.makeText(requireContext(), "未获得录音权限，无法使用语音记账", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VOICE) {
            String text = VoiceRecognitionHelper.resultText(data);
            if (text != null) {
                applyVoice(text);
            } else {
                showManualInputDialog("未识别到语音，已切换为手动输入");
            }
        }
    }

    private void applyVoice(String text) {
        VoiceInputParser.Parsed parsed = VoiceInputParser.parse(text);
        if (parsed.amount > 0) amountInput.setText(String.valueOf(parsed.amount));
        noteInput.setText(text);
        matchCategory(parsed.categoryName);
        Toast.makeText(requireContext(), "已解析：" + text, Toast.LENGTH_SHORT).show();
    }

    /** 语音不可用/识别失败时的兜底：弹出输入框，输入后同样自动解析并填表。 */
    private void showManualInputDialog(String message) {
        EditText input = new EditText(requireContext());
        input.setHint("输入内容，如：午餐27元");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(requireContext())
                .setTitle("语音不可用 · 手动输入")
                .setMessage(message)
                .setView(input)
                .setPositiveButton("确定", (d, w) -> {
                    String text = input.getText().toString().trim();
                    if (!text.isEmpty()) applyVoice(text);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- 保存 ----------
    private void save() {
        double amount;
        try {
            amount = Double.parseDouble(amountInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            Toast.makeText(requireContext(), "请输入有效金额", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectedCategoryId == null) {
            Toast.makeText(requireContext(), "请选择分类", Toast.LENGTH_SHORT).show();
            return;
        }

        Transaction t = new Transaction();
        t.clientId = UUID.randomUUID().toString();
        t.date = LocalDate.now();
        t.amount = amount;
        t.type = isIncome ? "INCOME" : "EXPENSE";
        t.categoryClientId = selectedCategoryId;
        t.accountClientId = selectedAccountId;
        t.note = noteInput.getText().toString().trim();
        t.source = "manual";
        t.lastModified = System.currentTimeMillis();
        t.deleted = false;
        txRepo.insert(t);

        String alert = checkBudgetAlert(selectedCategoryId);
        Toast.makeText(requireContext(),
                alert == null ? "已记账" : "已记账，" + alert, Toast.LENGTH_LONG).show();

        amountInput.setText("");
        noteInput.setText("");
        new SyncManager(requireContext()).sync(null);
    }

    /** 记账后预警：分类当月累计 >=100% 红色；>=90% 黄色。 */
    private String checkBudgetAlert(String categoryId) {
        if (isIncome) return null;
        LocalDate now = LocalDate.now();
        Budget b = budgetRepo.findActiveByKey(categoryId, now.getYear(), now.getMonthValue());
        if (b == null || b.amountLimit <= 0) return null;
        double used = txRepo.categoryExpense(categoryId, now.getYear(), now.getMonthValue());
        double pct = (used / b.amountLimit) * 100;
        if (pct >= 100) return String.format("⚠️「%s」预算超支 %.0f%%", "该分类", pct);
        if (pct >= 90) return String.format("🟡「%s」预算已用 %.0f%%", "该分类", pct);
        return null;
    }
}
