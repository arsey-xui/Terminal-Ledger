package com.personal.accounting.android.widget;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.personal.accounting.android.R;
import com.personal.accounting.android.data.CategoryRepo;
import com.personal.accounting.android.data.TransactionRepo;
import com.personal.accounting.android.model.Category;
import com.personal.accounting.android.model.Transaction;
import com.personal.accounting.android.sync.SyncManager;
import com.personal.accounting.android.voice.VoiceInputParser;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 小组件点击后打开的轻量记账户：金额 + 常用分类快捷按钮。
 */
public class QuickAddWidgetActivity extends Activity {

    private EditText amountInput;
    private TextView resultView;
    private TransactionRepo txRepo;
    private CategoryRepo catRepo;
    private String selectedCategoryId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quick_add);

        txRepo = new TransactionRepo(this);
        catRepo = new CategoryRepo(this);
        amountInput = findViewById(R.id.amount_input);
        resultView = findViewById(R.id.result_view);

        // 常用分类快捷按钮
        Button food = findViewById(R.id.cat_food);
        Button traffic = findViewById(R.id.cat_traffic);
        Button shopping = findViewById(R.id.cat_shopping);
        food.setOnClickListener(v -> pick("餐饮", food));
        traffic.setOnClickListener(v -> pick("交通", traffic));
        shopping.setOnClickListener(v -> pick("购物", shopping));

        findViewById(R.id.btn_save).setOnClickListener(this::save);
        findViewById(R.id.btn_voice).setOnClickListener(v -> {
            // 与记账页一致：不自研语音识别，改用输入法麦克风把语音转成文字
            Toast.makeText(this, "请用输入法键盘上的麦克风说话，文字会自动填入", Toast.LENGTH_LONG).show();
        });
    }

    private void pick(String name, Button source) {
        for (Category c : catRepo.listExpense()) {
            if (name.equals(c.name)) {
                selectedCategoryId = c.clientId;
                source.setText(c.name + " ✓");
                return;
            }
        }
        // 找不到则回退「其他」
        for (Category c : catRepo.listExpense()) {
            if ("其他".equals(c.name)) {
                selectedCategoryId = c.clientId;
                source.setText("其他 ✓");
                return;
            }
        }
    }

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
        t.accountClientId = tip(); // 默认账户，可扩展为账户选择
        t.note = "小组件快捷记账";
        t.source = "widget";
        t.lastModified = System.currentTimeMillis();
        t.deleted = false;
        txRepo.insert(t);

        resultView.setText(String.format("已记账：%.2f 元", amount));
        amountInput.setText("");

        // 触发后台同步
        new SyncManager(this).sync(null);
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        finish();
    }

    /** 默认账户：优先「现金」，否则取第一个账户。 */
    private String tip() {
        // 简化：使用第一个账户 clientId
        var accounts = new com.personal.accounting.android.data.AccountRepo(this).list();
        if (!accounts.isEmpty()) {
            for (var a : accounts) if ("现金".equals(a.name)) return a.clientId;
            return accounts.get(0).clientId;
        }
        return null;
    }
}
