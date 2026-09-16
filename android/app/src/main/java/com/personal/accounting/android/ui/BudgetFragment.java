package com.personal.accounting.android.ui;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.personal.accounting.android.R;
import com.personal.accounting.android.data.BudgetRepo;
import com.personal.accounting.android.data.CategoryRepo;
import com.personal.accounting.android.data.TransactionRepo;
import com.personal.accounting.android.model.Budget;
import com.personal.accounting.android.model.Category;

import java.text.DecimalFormat;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 预算管理：为每个支出分类设置当月上限，并显示已用/进度（超 90% 黄、超 100% 红）。 */
public class BudgetFragment extends Fragment {

    private static final DecimalFormat MONEY = new DecimalFormat("0.##");

    private LinearLayout container;
    private TextView monthTitle;
    private CategoryRepo catRepo;
    private TransactionRepo txRepo;
    private BudgetRepo budgetRepo;
    private final int year = LocalDate.now().getYear();
    private final int month = LocalDate.now().getMonthValue();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_budget, container, false);
        this.container = v.findViewById(R.id.budget_container);
        monthTitle = v.findViewById(R.id.budget_month_title);
        catRepo = new CategoryRepo(requireContext());
        txRepo = new TransactionRepo(requireContext());
        budgetRepo = new BudgetRepo(requireContext());
        monthTitle.setText(year + "年" + month + "月 · 预算管理");
        v.findViewById(R.id.btn_reset_budget).setOnClickListener(x -> confirmReset());
        return v;
    }

    private void confirmReset() {
        new AlertDialog.Builder(requireContext())
                .setTitle("重置预算管理？")
                .setMessage("会清空所有分类的预算上限，回到「未设置上限」的状态；流水记录不会被删除。\n\n"
                        + "此操作影响本地数据，并会在下次同步时同步到云端。")
                .setPositiveButton("重置", (d, w) -> {
                    budgetRepo.resetToDefaults();
                    rebuildRows();
                    Toast.makeText(requireContext(), "预算已清空", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onResume() {
        super.onResume();
        rebuildRows();
    }

    private void rebuildRows() {
        container.removeAllViews();
        List<Category> cats = catRepo.listExpense();
        for (Category c : cats) {
            View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_budget_row, container, false);
            bindRow(row, c);
            container.addView(row);
        }
        if (cats.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText("暂无分类，请先在记账页新增分类");
            container.addView(empty);
        }
    }

    private void bindRow(View row, Category cat) {
        TextView catLabel = row.findViewById(R.id.budget_cat);
        EditText limitEdit = row.findViewById(R.id.budget_limit);
        Button save = row.findViewById(R.id.budget_save);
        TextView progress = row.findViewById(R.id.budget_progress);

        catLabel.setText((cat.icon == null ? "" : cat.icon + " ") + cat.name);
        limitEdit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);

        // 用 findActiveByKey：软删除（墓碑）的预算不算已设置上限，显示“未设置上限”
        Budget existing = budgetRepo.findActiveByKey(cat.clientId, year, month);
        double used = txRepo.categoryExpense(cat.clientId, year, month);
        double limit = 0;
        if (existing != null) {
            limit = existing.amountLimit;
            limitEdit.setText(MONEY.format(limit));
        }
        updateProgress(progress, used, limit);

        save.setOnClickListener(x -> {
            String text = limitEdit.getText().toString().trim();
            if (text.isEmpty()) {
                Toast.makeText(requireContext(), "请填写预算上限", Toast.LENGTH_SHORT).show();
                return;
            }
            double amount;
            try {
                amount = Double.parseDouble(text);
            } catch (NumberFormatException e) {
                Toast.makeText(requireContext(), "金额格式不正确", Toast.LENGTH_SHORT).show();
                return;
            }
            Budget b = budgetRepo.findByKey(cat.clientId, year, month);
            if (b == null) {
                b = new Budget();
                b.clientId = UUID.randomUUID().toString();
                b.categoryClientId = cat.clientId;
                b.year = year;
                b.month = month;
            }
            b.amountLimit = amount;
            b.lastModified = System.currentTimeMillis();
            b.deleted = false;
            budgetRepo.upsertLww(b);
            updateProgress(progress, used, amount);
            Toast.makeText(requireContext(), "已保存「" + cat.name + "」预算", Toast.LENGTH_SHORT).show();
        });
    }

    private void updateProgress(TextView tv, double used, double limit) {
        if (limit <= 0) {
            tv.setText("本月已用 " + MONEY.format(used) + "（未设置上限）");
            tv.setTextColor(0xFF888888);
            return;
        }
        double pct = used / limit * 100;
        String badge = pct >= 100 ? String.format("%.0f%%", pct) : String.format("%.1f%%", pct);
        tv.setText("本月已用 " + MONEY.format(used) + " / " + MONEY.format(limit) + "（" + badge + "）");
        if (pct >= 100) tv.setTextColor(0xFFE53935);
        else if (pct >= 90) tv.setTextColor(0xFFF9A825);
        else tv.setTextColor(0xFF2E7D32);
    }
}
