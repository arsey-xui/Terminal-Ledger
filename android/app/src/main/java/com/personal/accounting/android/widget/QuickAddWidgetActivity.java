package com.personal.accounting.android.widget;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.personal.accounting.android.R;
import com.personal.accounting.android.data.AccountRepo;
import com.personal.accounting.android.data.CategoryRepo;
import com.personal.accounting.android.data.TransactionRepo;
import com.personal.accounting.android.model.Account;
import com.personal.accounting.android.model.Category;
import com.personal.accounting.android.model.Transaction;
import com.personal.accounting.android.sync.SyncManager;
import com.personal.accounting.android.voice.VoiceEntryDialog;
import com.personal.accounting.android.voice.VoiceInputParser;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 小组件点击后打开的轻量记账户：金额 + 常用分类快捷按钮 + 语音。
 *
 * <p>语音走 {@link VoiceEntryDialog}，与记账页是同一套「账户 + 分类 + 金额」格式和同一套解析，
 * 解析出的备注也会写进流水，不再固定成「小组件快捷记账」。</p>
 */
public class QuickAddWidgetActivity extends Activity implements VoiceEntryDialog.Host {

    /** 快捷分类按钮：与 {@link #QUICK_CATEGORY_NAMES} 按下标一一对应。 */
    private static final int[] QUICK_BUTTON_IDS = {R.id.cat_food, R.id.cat_traffic, R.id.cat_shopping};
    private static final String[] QUICK_CATEGORY_NAMES = {"餐饮", "交通", "购物"};

    private EditText amountInput;
    private TextView resultView;
    private TransactionRepo txRepo;
    private CategoryRepo catRepo;
    private AccountRepo accountRepo;
    private String selectedCategoryId;
    private String selectedCategoryName;
    private String selectedAccountId;
    /** 语音解析出的备注；为空时保存沿用「小组件快捷记账」。 */
    private String note = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quick_add);

        txRepo = new TransactionRepo(this);
        catRepo = new CategoryRepo(this);
        accountRepo = new AccountRepo(this);
        amountInput = findViewById(R.id.amount_input);
        resultView = findViewById(R.id.result_view);

        for (int i = 0; i < QUICK_BUTTON_IDS.length; i++) {
            final String name = QUICK_CATEGORY_NAMES[i];
            findViewById(QUICK_BUTTON_IDS[i]).setOnClickListener(v -> selectCategory(name));
        }

        findViewById(R.id.btn_save).setOnClickListener(this::save);
        findViewById(R.id.btn_voice).setOnClickListener(v -> VoiceEntryDialog.show(this, this));
    }

    // ---------- 语音（与记账页共用同一套格式与解析） ----------

    @Override
    public List<String> voiceAccountNames() {
        List<String> names = new ArrayList<>();
        for (Account a : accountRepo.list()) names.add(a.name);
        return names;
    }

    @Override
    public List<String> voiceCategoryNames() {
        List<String> names = new ArrayList<>();
        for (Category c : catRepo.listExpense()) names.add(c.name);
        return names;
    }

    @Override
    public void onVoiceParsed(VoiceInputParser.Parsed parsed) {
        if (parsed.amount > 0) amountInput.setText(VoiceEntryDialog.amountText(parsed.amount));
        note = parsed.note;
        selectCategory(parsed.categoryName);

        if (parsed.accountName != null) {
            for (Account a : accountRepo.list()) {
                if (parsed.accountName.equals(a.name)) {
                    selectedAccountId = a.clientId;
                    break;
                }
            }
        }

        resultView.setText(VoiceEntryDialog.describe(parsed, selectedCategoryName, accountName()));
        Toast.makeText(this, "已填入，确认后点保存", Toast.LENGTH_SHORT).show();
    }

    // ---------- 分类 ----------

    /** 选中分类并刷新快捷按钮的勾选；名字不存在时回退「其他」，再没有就清空选择。 */
    private void selectCategory(String name) {
        List<Category> cats = catRepo.listExpense();
        Category match = findByName(cats, name);
        if (match == null) match = findByName(cats, "其他");
        selectedCategoryId = match == null ? null : match.clientId;
        selectedCategoryName = match == null ? null : match.name;
        refreshQuickButtons();
    }

    private Category findByName(List<Category> cats, String name) {
        if (name == null) return null;
        for (Category c : cats) {
            if (name.equals(c.name)) return c;
        }
        return null;
    }

    /**
     * 语音可能选出「餐饮」以外的分类，所以三个按钮都按当前选择重画，
     * 避免出现两个按钮同时带勾。
     */
    private void refreshQuickButtons() {
        for (int i = 0; i < QUICK_BUTTON_IDS.length; i++) {
            String base = QUICK_CATEGORY_NAMES[i];
            ((Button) findViewById(QUICK_BUTTON_IDS[i]))
                    .setText(base.equals(selectedCategoryName) ? base + " ✓" : base);
        }
    }

    // ---------- 保存 ----------

    private void save(View v) {
        double amount;
        try {
            amount = Double.parseDouble(amountInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "请输入有效金额", Toast.LENGTH_SHORT).show();
            return;
        }
        if (amount <= 0) {
            Toast.makeText(this, "金额需大于 0", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectedCategoryId == null) {
            Toast.makeText(this, "请选择分类", Toast.LENGTH_SHORT).show();
            return;
        }

        Transaction t = new Transaction();
        t.clientId = UUID.randomUUID().toString();
        t.date = LocalDate.now();
        t.amount = amount;
        t.type = "EXPENSE";
        t.categoryClientId = selectedCategoryId;
        // 语音说到账户就用它，否则退回默认账户
        t.accountClientId = selectedAccountId != null ? selectedAccountId : defaultAccountId();
        t.note = note == null || note.isEmpty() ? "小组件快捷记账" : note;
        t.source = "widget";
        t.lastModified = System.currentTimeMillis();
        t.deleted = false;
        txRepo.insert(t);

        resultView.setText(String.format("已记账：%.2f 元", amount));
        amountInput.setText("");
        note = "";

        // 触发后台同步
        new SyncManager(this).sync(null);
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        finish();
    }

    private String accountName() {
        if (selectedAccountId == null) return null;
        for (Account a : accountRepo.list()) {
            if (selectedAccountId.equals(a.clientId)) return a.name;
        }
        return null;
    }

    /** 没指定账户时的默认账户：优先「现金」，否则取第一个。 */
    private String defaultAccountId() {
        List<Account> accounts = accountRepo.list();
        if (accounts.isEmpty()) return null;
        for (Account a : accounts) {
            if ("现金".equals(a.name)) return a.clientId;
        }
        return accounts.get(0).clientId;
    }
}
