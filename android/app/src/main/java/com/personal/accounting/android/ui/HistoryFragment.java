package com.personal.accounting.android.ui;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.personal.accounting.android.R;
import com.personal.accounting.android.data.AccountRepo;
import com.personal.accounting.android.data.CategoryRepo;
import com.personal.accounting.android.data.TransactionRepo;
import com.personal.accounting.android.model.Account;
import com.personal.accounting.android.model.Category;
import com.personal.accounting.android.model.Transaction;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 历史记录：按时间倒序列出流水，支持删除（软删除）与筛选。
 *
 * <p>筛选条件是时间范围 / 分类 / 账户 / 关键词，交给 {@link TransactionRepo#search} ——
 * 这套条件在数据层一直都有，只是之前界面没给入口，只能看最近的一万条。</p>
 *
 * <p>时间用固定预设（全部 / 本月 / 上月 / 近 3 个月 / 今年）而不是自定义日期选择：
 * 覆盖日常绝大多数场景，也少两个日期选择器。需要精确区间时再加。</p>
 */
public class HistoryFragment extends Fragment {

    private static final String[] DATE_LABELS = {"全部", "本月", "上月", "近 3 个月", "今年"};

    private ListView listView;
    private TextView emptyView;
    private TextView summaryView;
    private Button dateButton;
    private Button categoryButton;
    private Button accountButton;
    private Button keywordButton;

    private TransactionRepo txRepo;
    private CategoryRepo catRepo;
    private AccountRepo accountRepo;

    private Map<String, String> categoryNameCache = new HashMap<>();
    private Map<String, String> accountNameCache = new HashMap<>();

    private final List<Transaction> items = new ArrayList<>();

    /** 0 = 全部；下标对应 {@link #DATE_LABELS}。 */
    private int dateRangeIndex = 0;
    private String categoryId;
    private String accountId;
    private String keyword;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_history, container, false);
        listView = v.findViewById(R.id.history_list);
        emptyView = v.findViewById(R.id.history_empty);
        summaryView = v.findViewById(R.id.history_summary);
        dateButton = v.findViewById(R.id.history_filter_date);
        categoryButton = v.findViewById(R.id.history_filter_category);
        accountButton = v.findViewById(R.id.history_filter_account);
        keywordButton = v.findViewById(R.id.history_filter_keyword);

        txRepo = new TransactionRepo(requireContext());
        catRepo = new CategoryRepo(requireContext());
        accountRepo = new AccountRepo(requireContext());

        listView.setEmptyView(emptyView);
        listView.setAdapter(new Adapter());

        dateButton.setOnClickListener(x -> pickDateRange());
        categoryButton.setOnClickListener(x -> pickCategory());
        accountButton.setOnClickListener(x -> pickAccount());
        keywordButton.setOnClickListener(x -> editKeyword());
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    // ---------- 筛选 ----------

    /** 预设区间换算成 [from, to]；两端都可能为 null，表示那一侧不限。 */
    private LocalDate[] dateBounds() {
        LocalDate today = LocalDate.now();
        switch (dateRangeIndex) {
            case 1: {   // 本月
                LocalDate start = today.withDayOfMonth(1);
                return new LocalDate[]{start, start.withDayOfMonth(start.lengthOfMonth())};
            }
            case 2: {   // 上月
                LocalDate start = today.withDayOfMonth(1).minusMonths(1);
                return new LocalDate[]{start, start.withDayOfMonth(start.lengthOfMonth())};
            }
            case 3:     // 近 3 个月，含今天
                return new LocalDate[]{today.minusMonths(3), today};
            case 4:     // 今年
                return new LocalDate[]{LocalDate.of(today.getYear(), 1, 1),
                        LocalDate.of(today.getYear(), 12, 31)};
            default:
                return new LocalDate[]{null, null};
        }
    }

    private boolean hasFilter() {
        return dateRangeIndex != 0 || categoryId != null || accountId != null || keyword != null;
    }

    private void pickDateRange() {
        new AlertDialog.Builder(requireContext())
                .setTitle("时间范围")
                .setItems(DATE_LABELS, (d, which) -> {
                    dateRangeIndex = which;
                    reload();
                })
                .show();
    }

    private void pickCategory() {
        List<Category> cats = catRepo.allExpenseFirst();
        String[] labels = new String[cats.size() + 1];
        labels[0] = "全部";
        for (int i = 0; i < cats.size(); i++) {
            Category c = cats.get(i);
            labels[i + 1] = (c.icon == null || c.icon.isEmpty() ? "" : c.icon + " ") + c.name;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("分类")
                .setItems(labels, (d, which) -> {
                    categoryId = which == 0 ? null : cats.get(which - 1).clientId;
                    reload();
                })
                .show();
    }

    private void pickAccount() {
        List<Account> accounts = accountRepo.list();
        String[] labels = new String[accounts.size() + 1];
        labels[0] = "全部";
        for (int i = 0; i < accounts.size(); i++) labels[i + 1] = accounts.get(i).name;
        new AlertDialog.Builder(requireContext())
                .setTitle("账户")
                .setItems(labels, (d, which) -> {
                    accountId = which == 0 ? null : accounts.get(which - 1).clientId;
                    reload();
                })
                .show();
    }

    private void editKeyword() {
        EditText input = new EditText(requireContext());
        input.setHint("备注里的关键词，如「星巴克」");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        if (keyword != null) input.setText(keyword);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        new AlertDialog.Builder(requireContext())
                .setTitle("关键词")
                .setView(input)
                .setPositiveButton("应用", (d, w) -> {
                    String text = input.getText().toString().trim();
                    keyword = text.isEmpty() ? null : text;
                    reload();
                })
                .setNeutralButton("清除", (d, w) -> {
                    keyword = null;
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- 列表 ----------

    private void reload() {
        categoryNameCache.clear();
        for (Category c : catRepo.list()) categoryNameCache.put(c.clientId, c.name);
        accountNameCache.clear();
        for (Account a : accountRepo.list()) accountNameCache.put(a.clientId, a.name);

        LocalDate[] bounds = dateBounds();
        items.clear();
        items.addAll(txRepo.search(bounds[0], bounds[1], categoryId, accountId, keyword));
        ((BaseAdapter) listView.getAdapter()).notifyDataSetChanged();
        updateHeader();
    }

    /** 汇总行与按钮上的当前条件；空列表时区分「本来就没有记录」和「筛掉了」。 */
    private void updateHeader() {
        double expense = 0;
        double income = 0;
        for (Transaction t : items) {
            if ("EXPENSE".equals(t.type)) expense += t.amount;
            else if ("INCOME".equals(t.type)) income += t.amount;
        }
        summaryView.setText(String.format("共 %d 笔 · 支出 ¥%.2f · 收入 ¥%.2f", items.size(), expense, income));

        dateButton.setText("时间：" + DATE_LABELS[dateRangeIndex]);
        categoryButton.setText("分类：" + (categoryId == null ? "全部" : nameOr(catName(categoryId), "全部")));
        accountButton.setText("账户：" + (accountId == null ? "全部" : nameOr(accName(accountId), "全部")));
        keywordButton.setText("关键词：" + (keyword == null ? "无" : keyword));

        emptyView.setText(hasFilter()
                ? "没有符合条件的记录\n把上面的筛选放宽一点试试"
                : "还没有任何流水\n去「记账」填一笔，这里就会列出来");
    }

    private static String nameOr(String value, String fallback) {
        return (value == null || value.isEmpty()) ? fallback : value;
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            ViewHolder holder;
            if (row == null) {
                row = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_transaction, parent, false);
                holder = new ViewHolder(row);
                row.setTag(holder);
            } else {
                holder = (ViewHolder) row.getTag();
            }
            Transaction t = items.get(position);
            holder.date.setText(t.date == null ? "" : t.date.toString());
            holder.note.setText(buildSubtitle(t));
            boolean isExpense = "EXPENSE".equals(t.type);
            holder.amount.setText((isExpense ? "-" : "+") + String.format("%.2f", t.amount));
            holder.amount.setTextColor(isExpense
                    ? ChartColors.of(requireContext(), R.color.expense)
                    : ChartColors.of(requireContext(), R.color.income));

            holder.del.setOnClickListener(x -> {
                txRepo.delete(t.clientId);
                reload();
            });
            return row;
        }

        static class ViewHolder {
            final TextView date;
            final TextView note;
            final TextView amount;
            final Button del;

            ViewHolder(View row) {
                date = row.findViewById(R.id.item_date);
                note = row.findViewById(R.id.item_note);
                amount = row.findViewById(R.id.item_amount);
                del = row.findViewById(R.id.item_delete);
            }
        }
    }

    private String buildSubtitle(Transaction t) {
        String cat = catName(t.categoryClientId);
        if (cat == null || cat.isEmpty()) cat = "未分类";
        String acc = accName(t.accountClientId);
        StringBuilder sb = new StringBuilder();
        sb.append(cat);
        if (acc != null && !acc.isEmpty()) sb.append(" · ").append(acc);
        if (t.note != null && !t.note.isEmpty()) sb.append(" · ").append(t.note);
        return sb.toString();
    }

    private String catName(String id) {
        if (id == null) return "";
        String name = categoryNameCache.get(id);
        return name != null ? name : "";
    }

    private String accName(String id) {
        if (id == null) return "";
        String name = accountNameCache.get(id);
        return name != null ? name : "";
    }
}
