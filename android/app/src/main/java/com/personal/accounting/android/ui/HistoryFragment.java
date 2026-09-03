package com.personal.accounting.android.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.personal.accounting.android.R;
import com.personal.accounting.android.data.AccountRepo;
import com.personal.accounting.android.data.CategoryRepo;
import com.personal.accounting.android.data.TransactionRepo;
import com.personal.accounting.android.model.Category;
import com.personal.accounting.android.model.Transaction;

import java.util.ArrayList;
import java.util.List;

/** 历史记录：按时间倒序列出流水，支持删除（软删除）。 */
public class HistoryFragment extends Fragment {

    private ListView listView;
    private TransactionRepo txRepo;
    private CategoryRepo catRepo;
    private AccountRepo accountRepo;
    private List<Transaction> items = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_history, container, false);
        listView = v.findViewById(R.id.history_list);
        txRepo = new TransactionRepo(requireContext());
        catRepo = new CategoryRepo(requireContext());
        accountRepo = new AccountRepo(requireContext());
        listView.setEmptyView(v.findViewById(R.id.history_empty));
        listView.setAdapter(new Adapter());
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        items.clear();
        items.addAll(txRepo.listRecent(10000));
        ((BaseAdapter) listView.getAdapter()).notifyDataSetChanged();
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
            if (row == null) {
                row = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_transaction, parent, false);
            }
            Transaction t = items.get(position);
            TextView date = row.findViewById(R.id.item_date);
            TextView note = row.findViewById(R.id.item_note);
            TextView amount = row.findViewById(R.id.item_amount);
            Button del = row.findViewById(R.id.item_delete);

            date.setText(t.date == null ? "" : t.date.toString());
            note.setText(buildSubtitle(t));
            boolean isExpense = "EXPENSE".equals(t.type);
            amount.setText((isExpense ? "-" : "+") + String.format("%.2f", t.amount));
            amount.setTextColor(isExpense ? 0xFFE53935 : 0xFF2E7D32);

            del.setOnClickListener(x -> {
                txRepo.delete(t.clientId);
                reload();
            });
            return row;
        }
    }

    private String buildSubtitle(Transaction t) {
        String cat = catName(t.categoryClientId);
        String acc = accName(t.accountClientId);
        StringBuilder sb = new StringBuilder();
        sb.append(cat);
        if (acc != null) sb.append(" · ").append(acc);
        if (t.note != null && !t.note.isEmpty()) sb.append(" · ").append(t.note);
        return sb.toString();
    }

    private String catName(String id) {
        if (id == null) return "未分类";
        for (Category c : catRepo.listExpense()) if (id.equals(c.clientId)) return c.name;
        return "未分类";
    }

    private String accName(String id) {
        if (id == null) return null;
        for (var a : accountRepo.list()) if (id.equals(a.clientId)) return a.name;
        return null;
    }
}
