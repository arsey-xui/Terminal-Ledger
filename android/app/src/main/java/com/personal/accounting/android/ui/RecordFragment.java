package com.personal.accounting.android.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
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
import com.personal.accounting.android.analysis.AiSettingsDialog;
import com.personal.accounting.android.bill.BillAiPrompt;
import com.personal.accounting.android.bill.BillAiRecognizer;
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
import com.personal.accounting.android.voice.VoiceEntryDialog;
import com.personal.accounting.android.voice.VoiceInputParser;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 快速记账页：数字键盘 + 分类快捷图标 + 账户选择 + 语音入口。 */
public class RecordFragment extends Fragment implements VoiceEntryDialog.Host {


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
    private BillAiRecognizer billAiRecognizer;

    /**
     * 从系统「分享」进来的一张图片，由 MainActivity 在展示本页之前设置。
     * 用一次性字段而不是 arguments：旋转重建时字段天然为 null，不会重复识别一遍。
     */
    private Uri sharedImageUri;
    private final ActivityResultLauncher<Intent> imagePicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
                Uri uri = result.getData().getData();
                if (uri != null) runOcr(uri);
            });

    /** 接收系统分享过来的图片；由 MainActivity 在**新实例**创建前调用，只生效一次。 */
    public void setSharedImageUri(Uri uri) {
        this.sharedImageUri = uri;
    }

    /**
     * 已经显示中的记账页收到新的分享图片。
     *
     * <p>记账页现在会被缓存（hide 而不是销毁），所以再次分享时复用的是同一个实例 ——
     * 这时来不及走 {@code onCreateView}，直接在这里跑识别。</p>
     */
    public void onSharedImage(Uri uri) {
        if (uri == null) return;
        sharedImageUri = null;
        runOcr(uri);
    }

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
        refreshFirstUseCard(v);

        // 从分享进来的截图：直接开始识别，省掉手动选图这一步
        if (sharedImageUri != null) {
            Uri uri = sharedImageUri;
            sharedImageUri = null;
            runOcr(uri);
        }
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        // 从设置页确认完待办回来时刷新提示
        View v = getView();
        if (v != null) {
            refreshRecurringHint(v);
            refreshFirstUseCard(v);
        }
    }

    /**
     * 首次使用引导卡：只在**一条流水都还没有**时显示。
     *
     * <p>不依赖「首次使用」标记，而是看流水总数 —— 记完第一笔它自己就消失了，
     * 卸载重装、清数据也能正确复位，不会出现「标记说已看过、但用户其实什么都没记」的错位。</p>
     */
    private void refreshFirstUseCard(View v) {
        View card = v.findViewById(R.id.first_use_card);
        if (card == null) return;
        card.setVisibility(txRepo.countAll() == 0 ? View.VISIBLE : View.GONE);
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
        // 一个分类都没有时，栏里只剩「＋ 新增」，用户不知道这是怎么回事，先说明一句
        if (cats.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText(isIncome ? "还没有收入分类，点「＋ 新增」建一个" : "还没有支出分类，点「＋ 新增」建一个");
            empty.setTextColor(ChartColors.of(requireContext(), R.color.text_secondary));
            empty.setTextSize(13f);
            empty.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams emptyLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            emptyLp.setMargins(4, 4, 12, 4);
            categoryBar.addView(empty, emptyLp);
        }
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

        // 选中态底色是酸橙绿，文字必须固定黑；未选中态用主题主文字色，
        // 否则深色模式下近黑文字压在深色卡面上会看不见。
        ColorStateList chipText = new ColorStateList(
                new int[][]{{android.R.attr.state_selected}, {}},
                new int[]{
                        ChartColors.of(ctx, R.color.chip_text_selected),
                        ChartColors.of(ctx, R.color.text_primary)
                });

        TextView iconView = new TextView(ctx);
        iconView.setText(iconOrNull == null ? "" : iconOrNull);
        iconView.setTextColor(chipText);
        iconView.setTextSize(16);
        iconView.setIncludeFontPadding(false);
        iconView.setGravity(Gravity.CENTER);
        iconView.setSingleLine(true);
        iconView.setEllipsize(TextUtils.TruncateAt.END);
        chip.addView(iconView);

        TextView nameView = new TextView(ctx);
        nameView.setText(name);
        nameView.setTextColor(chipText);
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
        if (billAiRecognizer != null) {
            billAiRecognizer.shutdown();
            billAiRecognizer = null;
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

    /** 打开共用的语音输入弹窗：记账页与桌面小组件用的是同一套格式与解析。 */
    private void startVoice(View v) {
        VoiceEntryDialog.show(requireContext(), this);
    }

    @Override
    public List<String> voiceAccountNames() {
        List<String> names = new ArrayList<>();
        for (Account a : accountRepo.list()) names.add(a.name);
        return names;
    }

    @Override
    public List<String> voiceCategoryNames() {
        List<String> names = new ArrayList<>();
        for (Category c : isIncome ? catRepo.listIncome() : catRepo.listExpense()) names.add(c.name);
        return names;
    }

    /**
     * 回填解析结果：金额写进输入框、备注只留有用的一段、分类高亮到对应分类框、
     * 账户按钮切到说到的那个账户。没说到的段保持原样，不清空。
     */
    @Override
    public void onVoiceParsed(VoiceInputParser.Parsed parsed) {
        if (parsed.amount > 0) amountInput.setText(VoiceEntryDialog.amountText(parsed.amount));
        noteInput.setText(parsed.note);

        // 分类：命中就高亮对应分类框（matchCategory 找不到时会回退「其他」）
        matchCategory(parsed.categoryName);
        if (selectedCategoryId != null) selectCategory(selectedCategoryId);

        // 账户：只有说到的账户确实存在于本机时才切换
        if (parsed.accountName != null) {
            for (Account a : accountRepo.list()) {
                if (parsed.accountName.equals(a.name)) {
                    selectedAccountId = a.clientId;
                    break;
                }
            }
            refreshAccountButton();
        }

        Toast.makeText(requireContext(),
                VoiceEntryDialog.describe(parsed, categoryNameOf(selectedCategoryId),
                        accountNameOf(selectedAccountId)),
                Toast.LENGTH_LONG).show();
    }

    /** 截图识别的中间结果：本地 OCR 与 AI 两条路都先归一到这里，再交给同一个确认框。 */
    private static final class BillSuggestion {
        double amount;
        /** 备注（本地是商户名，AI 是 merchant 字段）。 */
        String note;
        /** 分类名，可为 null；由 matchCategory 落到本机分类。 */
        String categoryName;
        /** 账户名，可为 null；为 null 时保持当前所选账户。 */
        String accountName;
        /** 是否收入（退款/收款）：AI 会判断，本地路径恒为 false。 */
        boolean income;
    }

    // ---------- 截图记账（配了 API Key 走 AI 多模态，否则用本机离线识别） ----------
    private void pickScreenshot() {
        // 首次使用且没配 API Key：提示一次可以改用 AI 识别（本机识别金额/分类容易认不准）
        if (BillAiRecognizer.shouldHint(requireContext())) {
            BillAiRecognizer.markHinted(requireContext());
            new AlertDialog.Builder(requireContext())
                    .setTitle("截图记账")
                    .setMessage("默认用本机离线识别（ML Kit + 规则）。账单页上数字很多时，"
                            + "金额和分类容易认错。\n\n填入自己的 API Key 就能改用多模态大模型识别，"
                            + "模型需要支持图像输入（如 gpt-4o-mini）。")
                    .setPositiveButton("配置 AI 识别",
                            (d, w) -> AiSettingsDialog.show(requireContext(), null))
                    .setNegativeButton("先用本地识别", (d, w) -> launchImagePicker())
                    .show();
            return;
        }
        launchImagePicker();
    }

    private void launchImagePicker() {
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
        if (BillAiRecognizer.isAvailable(requireContext())) {
            runAiOcr(uri);
        } else {
            runLocalOcr(uri);
        }
    }

    /** 本机离线识别：ML Kit 出文本，再用规则解析金额与商户。 */
    private void runLocalOcr(Uri uri) {
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
                BillSuggestion s = new BillSuggestion();
                s.amount = parsed.amount;
                s.note = parsed.merchant;
                s.categoryName = parsed.merchant;   // 按商户名猜分类（本地路径一直如此）
                showBillConfirmDialog(s, text);
            }

            @Override
            public void onError(String message) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** AI 识别：截图整张交给多模态模型，直接拿金额/账户/分类；失败就回退本地识别。 */
    private void runAiOcr(Uri uri) {
        if (billAiRecognizer == null) billAiRecognizer = new BillAiRecognizer(requireContext());
        Toast.makeText(requireContext(), "正在用 AI 识别截图…", Toast.LENGTH_SHORT).show();
        billAiRecognizer.recognize(uri, voiceAccountNames(), voiceCategoryNames(),
                new BillAiRecognizer.Callback() {
                    @Override
                    public void onResult(BillAiPrompt.Result result) {
                        if (!isAdded() || getActivity() == null || getActivity().isFinishing()) return;
                        if (!result.hasAmount()) {
                            Toast.makeText(requireContext().getApplicationContext(), "AI 没识别到金额，改用本机识别",
                                    Toast.LENGTH_LONG).show();
                            runLocalOcr(uri);
                            return;
                        }
                        BillSuggestion s = new BillSuggestion();
                        s.amount = result.amount;
                        s.note = result.merchant;
                        s.categoryName = result.categoryName;
                        s.accountName = result.accountName;
                        s.income = result.isIncome();
                        showBillConfirmDialog(s, "AI 识别"
                                + (result.merchant == null ? "" : " · " + result.merchant));
                    }

                    @Override
                    public void onError(String message) {
                        if (!isAdded() || getActivity() == null || getActivity().isFinishing()) return;
                        Toast.makeText(requireContext().getApplicationContext(), message + "，改用本机识别",
                                Toast.LENGTH_LONG).show();
                        runLocalOcr(uri);
                    }
                });
    }

    /**
     * 识别结果确认框：金额 / 备注可改，分类与账户按识别结果**预选**（用户仍可改）。
     * 本地 OCR 与 AI 两条路都归到这里。
     */
    private void showBillConfirmDialog(BillSuggestion suggestion, String rawText) {
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_bill_confirm, null, false);
        EditText amountView = view.findViewById(R.id.bill_amount);
        EditText noteView = view.findViewById(R.id.bill_note);
        Button categoryButton = view.findViewById(R.id.bill_pick_category);
        Button accountPickButton = view.findViewById(R.id.bill_pick_account);
        TextView rawView = view.findViewById(R.id.bill_raw_text);

        rawView.setText(rawText);

        // 收入/支出必须在选分类之前切：收支两套分类是分开的，切换会重建分类栏并清空已选分类
        if (suggestion.income != isIncome) {
            incomeSwitch.setChecked(suggestion.income);
        }

        amountView.setText(VoiceEntryDialog.amountText(suggestion.amount));
        noteView.setText(suggestion.note == null ? "" : suggestion.note);

        // 分类：识别到了就用它，没识别到就按商户名猜；matchCategory 找不到时会回退「其他」
        matchCategory(suggestion.categoryName == null ? "其他" : suggestion.categoryName);

        // 账户：识别到了就切过去，否则保持当前选择
        if (suggestion.accountName != null) {
            for (Account a : accountRepo.list()) {
                if (suggestion.accountName.equals(a.name)) {
                    selectedAccountId = a.clientId;
                    break;
                }
            }
        }
        if (selectedAccountId == null) refreshAccountButton();

        categoryButton.setText("分类：" + categoryNameOf(selectedCategoryId));
        categoryButton.setOnClickListener(x -> pickCategoryForBill(categoryButton));
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
                    amountInput.setText(VoiceEntryDialog.amountText(amount));
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
