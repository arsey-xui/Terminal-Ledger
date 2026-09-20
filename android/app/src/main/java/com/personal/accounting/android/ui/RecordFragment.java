package com.personal.accounting.android.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.graphics.Color;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.personal.accounting.android.R;
import com.personal.accounting.android.bill.BillTextParser;
import com.personal.accounting.android.bill.ScreenshotOcr;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 快速记账页：数字键盘 + 分类快捷图标 + 账户选择 + 语音入口。 */
public class RecordFragment extends Fragment {


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
    private ScreenshotOcr screenshotOcr;
    private final ActivityResultLauncher<Intent> imagePicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
                Uri uri = result.getData().getData();
                if (uri != null) runOcr(uri);
            });

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
        screenshotOcr = new ScreenshotOcr(requireContext());

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
        v.findViewById(R.id.btn_screenshot).setOnClickListener(x -> pickScreenshot());
        v.findViewById(R.id.btn_save).setOnClickListener(x -> save());

        // 本月概览
        double[] s = txRepo.monthSummary(LocalDate.now().getYear(), LocalDate.now().getMonthValue());
        ((TextView) v.findViewById(R.id.summary_income)).setText(String.format("¥%.2f", s[0]));
        ((TextView) v.findViewById(R.id.summary_expense)).setText(String.format("¥%.2f", s[1]));
        ((TextView) v.findViewById(R.id.summary_balance)).setText(String.format("¥%.2f", s[2]));

        refreshRecurringHint(v);
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        // 从设置页确认完待办回来时刷新提示
        View v = getView();
        if (v != null) refreshRecurringHint(v);
    }

    /** 周期记账：有待确认项时提示去「设置 → 周期记账」确认。 */
    private void refreshRecurringHint(View v) {
        TextView hint = v.findViewById(R.id.recurring_pending_hint);
        if (hint == null) return;
        int pendingCount;
        try {
            pendingCount = new com.personal.accounting.android.data.RecurringRepo(requireContext()).pendingCount();
        } catch (Exception e) {
            pendingCount = 0;
        }
        if (pendingCount > 0) {
            hint.setVisibility(View.VISIBLE);
            hint.setText("● 有 " + pendingCount + " 笔周期记账待确认，去「设置 → 周期记账」确认入账");
        } else {
            hint.setVisibility(View.GONE);
        }
    }

    // ---------- 分类 ----------
    private void buildCategoryBar() {
        categoryBar.removeAllViews();
        List<Category> cats = isIncome ? catRepo.listIncome() : catRepo.listExpense();
        selectedCategoryId = null; // 切换 支出/收入 后需重新选分类
        int chipMinWidth = categoryChipMinWidth(cats);
        for (Category c : cats) {
            LinearLayout chip = categoryChip(c.icon, c.name, chipMinWidth);
            chip.setTag(c.clientId);
            chip.setOnClickListener(view -> {
                selectedCategoryId = c.clientId;
                selectCategory(c.clientId);
                Toast.makeText(requireContext(), "分类：" + c.name, Toast.LENGTH_SHORT).show();
            });
            // 宽度按内容自适应（不能用 weight 等分：窄框会逼分类名换行、打乱高度与基线）
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(4, 4, 4, 4);
            categoryBar.addView(chip, lp);
        }
        // 追加“新增分类”按钮
        LinearLayout add = categoryChip(null, "＋ 新增", chipMinWidth);
        add.setOnClickListener(x -> addCategoryDialog());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(4, 4, 4, 4);
        categoryBar.addView(add, lp);
    }

    /**
     * 统一的最小框宽：按本组分类里最长的名字测算，保证任何分类名都排在一行内、且所有框等宽。
     * 这样不依赖 emoji 回退字体的度量差异，也不会出现名字被拆成两行导致高度/基线错乱。
     */
    private int categoryChipMinWidth(List<Category> cats) {
        float widest = 0f;
        for (Category c : cats) {
            widest = Math.max(widest, categoryNamePaint().measureText(c.name));
        }
        // 左右内边距(6+6dp) + 边框余量 + 留一点间距
        return (int) Math.ceil(widest) + dp(18);
    }

    /** 与实际渲染同字号的画笔，用于测算分类名宽度。 */
    private android.text.TextPaint categoryNamePaint() {
        android.text.TextPaint p = new android.text.TextPaint();
        p.setTextSize(16 * requireContext().getResources().getDisplayMetrics().scaledDensity);
        return p;
    }

    /**
     * 分类框：外层容器统一高度、内容整体居中，图标与文字各占一行。
     * 不用单个 Button 的多行文本，是因为 emoji 行会把字体行高撑大，
     * 有/无 emoji 时文本块的居中位置会不一致（无 emoji 的文字偏上、有 emoji 的偏下）；
     * 这里用显式两行 + TextView 去掉 fontPadding，居中位置由容器决定。
     */
    private LinearLayout categoryChip(String iconOrNull, String name, int minWidth) {
        Context ctx = requireContext();
        LinearLayout chip = new LinearLayout(ctx);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setGravity(Gravity.CENTER);
        chip.setMinimumWidth(Math.max(dp(64), minWidth));
        chip.setMinimumHeight(dp(72));
        chip.setBackgroundResource(R.drawable.category_chip);
        chip.setPadding(dp(6), dp(8), dp(6), dp(8));

        TextView iconView = new TextView(ctx);
        iconView.setText(iconOrNull == null ? "" : iconOrNull);
        iconView.setTextColor(Color.parseColor("#1A1A1A"));
        iconView.setTextSize(16);
        iconView.setIncludeFontPadding(false);
        iconView.setGravity(Gravity.CENTER);
        iconView.setSingleLine(true);
        iconView.setEllipsize(TextUtils.TruncateAt.END);
        chip.addView(iconView);

        TextView nameView = new TextView(ctx);
        nameView.setText(name);
        nameView.setTextColor(Color.parseColor("#1A1A1A"));
        nameView.setTextSize(16);
        nameView.setIncludeFontPadding(false);
        nameView.setGravity(Gravity.CENTER);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        chip.addView(nameView);

        return chip;
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

    @Override
    public void onDestroyView() {
        if (screenshotOcr != null) {
            screenshotOcr.shutdown();
            screenshotOcr = null;
        }
        super.onDestroyView();
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

    // ---------- 语音输入（交给输入法的语音转文字，本应用只做文本解析） ----------
    private void startVoice(View v) {
        // 不再自研语音识别：用输入法的麦克风说话转成文字，这里负责把文字解析成金额 + 分类。
        EditText input = new EditText(requireContext());
        input.setHint("例如：午餐27元");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        int pad = dp(20);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        new AlertDialog.Builder(requireContext())
                .setTitle("语音输入 · 文本解析")
                .setMessage("点击输入框后，用输入法键盘上的麦克风说话，文字会出现在框里；"
                        + "也可以用键盘手动输入或粘贴。确认后自动识别金额与分类。")
                .setView(input)
                .setPositiveButton("解析填入", (d, w) -> {
                    String text = input.getText().toString().trim();
                    if (text.isEmpty()) {
                        Toast.makeText(requireContext(), "没有内容可解析", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    applyParsedText(text);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 把一句口语/文字解析成金额与分类，并回填记账表单。 */
    private void applyParsedText(String text) {
        VoiceInputParser.Parsed parsed = VoiceInputParser.parse(text);
        if (parsed.amount > 0) amountInput.setText(String.valueOf(parsed.amount));
        noteInput.setText(text);
        matchCategory(parsed.categoryName);
        Toast.makeText(requireContext(), "已解析：" + text, Toast.LENGTH_SHORT).show();
    }

    // ---------- 截图记账（OCR 识别金额 + 用户自选分类/账户） ----------
    private void pickScreenshot() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 系统照片选择器，无需相册读取权限
            intent = new Intent("android.provider.action.PICK_IMAGES");
            intent.setType("image/*");
        } else {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
        }
        try {
            imagePicker.launch(intent);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "无法打开图片选择器：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void runOcr(Uri uri) {
        if (screenshotOcr == null) screenshotOcr = new ScreenshotOcr(requireContext());
        Toast.makeText(requireContext(), "正在识别截图…", Toast.LENGTH_SHORT).show();
        screenshotOcr.recognize(uri, new ScreenshotOcr.Callback() {
            @Override
            public void onText(String text) {
                BillTextParser.Result parsed = BillTextParser.parse(text);
                if (!parsed.hasAmount()) {
                    Toast.makeText(requireContext(), "没有从截图里识别到金额，请重试或手动输入",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                showBillConfirmDialog(parsed, text);
            }

            @Override
            public void onError(String message) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 识别结果确认框：金额/备注可改，分类与账户由用户自选。 */
    private void showBillConfirmDialog(BillTextParser.Result parsed, String rawText) {
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_bill_confirm, null, false);
        EditText amountView = view.findViewById(R.id.bill_amount);
        EditText noteView = view.findViewById(R.id.bill_note);
        Button categoryButton = view.findViewById(R.id.bill_pick_category);
        Button accountPickButton = view.findViewById(R.id.bill_pick_account);
        TextView rawView = view.findViewById(R.id.bill_raw_text);

        rawView.setText(rawText);
        amountView.setText(trimZeros(parsed.amount));
        noteView.setText(parsed.merchant == null ? "" : parsed.merchant);

        // 分类先按商户名猜一个，用户可改
        matchCategory(parsed.merchant == null ? "其他" : parsed.merchant);
        categoryButton.setText("分类：" + categoryNameOf(selectedCategoryId));
        categoryButton.setOnClickListener(x -> pickCategoryForBill(categoryButton));

        if (selectedAccountId == null) refreshAccountButton();
        accountPickButton.setText("账户：" + accountNameOf(selectedAccountId));
        accountPickButton.setOnClickListener(x -> pickAccountForBill(accountPickButton));

        new AlertDialog.Builder(requireContext())
                .setTitle("截图记账 · 确认")
                .setView(view)
                .setPositiveButton("记账", (d, w) -> {
                    double amount;
                    try {
                        amount = Double.parseDouble(amountView.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        Toast.makeText(requireContext(), "金额格式不正确", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (amount <= 0) {
                        Toast.makeText(requireContext(), "金额需大于 0", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    amountInput.setText(trimZeros(amount));
                    noteInput.setText(noteView.getText().toString().trim());
                    save();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickCategoryForBill(Button target) {
        List<Category> cats = isIncome ? catRepo.listIncome() : catRepo.listExpense();
        String[] names = new String[cats.size()];
        for (int i = 0; i < cats.size(); i++) {
            Category c = cats.get(i);
            names[i] = (c.icon == null ? "" : c.icon + " ") + c.name;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("选择分类")
                .setItems(names, (d, which) -> {
                    selectedCategoryId = cats.get(which).clientId;
                    target.setText("分类：" + categoryNameOf(selectedCategoryId));
                })
                .show();
    }

    private void pickAccountForBill(Button target) {
        List<Account> accounts = accountRepo.list();
        if (accounts.isEmpty()) {
            Toast.makeText(requireContext(), "还没有账户，请先在记账页新增账户", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[accounts.size()];
        for (int i = 0; i < accounts.size(); i++) names[i] = accounts.get(i).name;
        new AlertDialog.Builder(requireContext())
                .setTitle("选择账户")
                .setItems(names, (d, which) -> {
                    selectedAccountId = accounts.get(which).clientId;
                    target.setText("账户：" + accountNameOf(selectedAccountId));
                    refreshAccountButton();
                })
                .show();
    }

    private String categoryNameOf(String clientId) {
        if (clientId == null) return "请选择";
        for (Category c : catRepo.all()) {
            if (clientId.equals(c.clientId)) return c.name;
        }
        return "请选择";
    }

    private String accountNameOf(String clientId) {
        if (clientId == null) return "请选择";
        for (Account a : accountRepo.list()) {
            if (clientId.equals(a.clientId)) return a.name;
        }
        return "请选择";
    }

    /** 27.50 → "27.5"；整数金额不带小数点。 */
    private static String trimZeros(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
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
