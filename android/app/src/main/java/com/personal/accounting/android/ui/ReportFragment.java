package com.personal.accounting.android.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.components.MarkerView;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.utils.MPPointF;
import com.personal.accounting.android.R;
import com.personal.accounting.android.data.BudgetRepo;
import com.personal.accounting.android.data.CategoryRepo;
import com.personal.accounting.android.data.TransactionRepo;
import com.personal.accounting.android.model.Budget;
import com.personal.accounting.android.model.Category;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 报表页：概览卡片 + 分类饼图 + 近30天趋势 + 预算进度 + 年度热力图。 */
public class ReportFragment extends Fragment {

    private TransactionRepo txRepo;
    private CategoryRepo catRepo;
    private BudgetRepo budgetRepo;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_report, container, false);
        txRepo = new TransactionRepo(requireContext());
        catRepo = new CategoryRepo(requireContext());
        budgetRepo = new BudgetRepo(requireContext());
        renderSummary(v);
        renderPie(v.findViewById(R.id.pie));
        renderTrend(v.findViewById(R.id.trend));
        renderBudget(v);
        renderHeatmap(v.findViewById(R.id.heatmap_grid));
        return v;
    }

    private void renderSummary(View v) {
        LocalDate now = LocalDate.now();
        double[] s = txRepo.monthSummary(now.getYear(), now.getMonthValue());
        ((TextView) v.findViewById(R.id.money_income)).setText(String.format("¥%.2f", s[0]));
        ((TextView) v.findViewById(R.id.money_expense)).setText(String.format("¥%.2f", s[1]));
        ((TextView) v.findViewById(R.id.money_balance)).setText(String.format("¥%.2f", s[2]));

        // 环比：本月 vs 上月
        LocalDate prev = now.minusMonths(1);
        double[] ps = txRepo.monthSummary(prev.getYear(), prev.getMonthValue());
        double mom = ps[1] > 0 ? (s[1] - ps[1]) / ps[1] * 100 : 0;
        TextView momView = v.findViewById(R.id.money_mom);
        momView.setText(String.format("环比上月 %+.1f%%", mom));
        momView.setTextColor(mom >= 0 ? Color.parseColor("#D64545") : Color.parseColor("#2E9E24"));
    }

    private void renderPie(PieChart pie) {
        pie.setNoDataText("暂无数据");
        pie.setNoDataTextColor(Color.parseColor("#A6A6A6"));
        LocalDate now = LocalDate.now();
        Map<String, Double> byCat = new LinkedHashMap<>();
        for (var t : txRepo.search(LocalDate.of(now.getYear(), now.getMonthValue(), 1),
                LocalDate.of(now.getYear(), now.getMonthValue(),
                        LocalDate.of(now.getYear(), now.getMonthValue(), 1).lengthOfMonth()),
                null, null, null)) {
            if (!"EXPENSE".equals(t.type) || t.categoryClientId == null) continue;
            byCat.merge(catName(t.categoryClientId), t.amount, Double::sum);
        }
        List<PieEntry> entries = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        int[] palette = {0xFFC0FF00, 0xFF4A4A4A, 0xFFC0C0C0, 0xFFD64545, 0xFF8C8C8C, 0xFFA6D800};
        int i = 0;
        for (Map.Entry<String, Double> e : byCat.entrySet()) {
            entries.add(new PieEntry(e.getValue().floatValue(), e.getKey()));
            colors.add(palette[i++ % palette.length]);
        }
        if (entries.isEmpty()) return;
        PieDataSet ds = new PieDataSet(entries, "支出构成");
        ds.setColors(colors);
        ds.setValueTextSize(12f);
        PieData data = new PieData(ds);
        data.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format("%.0f元", value);
            }
        });
        pie.setData(data);
        pie.getDescription().setEnabled(false);
        pie.getLegend().setTextColor(Color.parseColor("#6B6B6B"));
        pie.invalidate();
    }

    private void renderTrend(LineChart chart) {
        chart.setNoDataText("暂无数据");
        chart.setNoDataTextColor(Color.parseColor("#A6A6A6"));
        int days = 30;
        LocalDate start = LocalDate.now().minusDays(days - 1L);
        // 净支出 = 支出 - 收入，逐日汇总
        Map<LocalDate, Double> perDayExp = new LinkedHashMap<>();
        Map<LocalDate, Double> perDayInc = new LinkedHashMap<>();
        for (var t : txRepo.listRecent(1000000)) {
            if (t.date == null) continue;
            if (t.date.isBefore(start) || t.date.isAfter(LocalDate.now())) continue;
            if ("EXPENSE".equals(t.type)) perDayExp.merge(t.date, t.amount, Double::sum);
            else if ("INCOME".equals(t.type)) perDayInc.merge(t.date, t.amount, Double::sum);
        }
        List<Entry> entries = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate d = start.plusDays(i);
            labels.add(d.getDayOfMonth() + "");
            double net = perDayExp.getOrDefault(d, 0.0) - perDayInc.getOrDefault(d, 0.0);
            entries.add(new Entry(i, (float) net));
        }
        LineDataSet ds = new LineDataSet(entries, "每日净支出");
        ds.setColor(Color.parseColor("#C0FF00"));
        ds.setLineWidth(2f);
        ds.setDrawFilled(true);
        ds.setFillColor(Color.parseColor("#66C0FF00"));
        ds.setDrawCircles(false);
        ds.setDrawValues(false);
        LineData data = new LineData(ds);
        chart.setData(data);
        chart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(labels));
        chart.getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM);
        chart.getXAxis().setLabelCount(8);
        chart.getXAxis().setGranularity(1f);
        chart.getXAxis().setTextColor(Color.parseColor("#8C8C8C"));
        chart.getDescription().setEnabled(false);
        chart.getAxisRight().setEnabled(false);
        // 净支出可为负，不强制 Y 轴最小值为 0
        chart.getAxisLeft().setTextColor(Color.parseColor("#8C8C8C"));
        chart.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format("%.0f", value);
            }
        });
        chart.getLegend().setTextColor(Color.parseColor("#6B6B6B"));
        // 点击数据点：显示具体值（日期 + 净支出金额）
        chart.setMarker(new DayMarker(requireContext(), start));
        chart.invalidate();
    }

    /** 点击数据点时弹出的标记：显示“日期 + 净支出金额”。 */
    private static class DayMarker extends MarkerView {
        private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("M.d");
        private final TextView tv;
        private final LocalDate start;

        DayMarker(Context context, LocalDate start) {
            super(context, R.layout.marker_view);
            this.start = start;
            tv = findViewById(R.id.marker_text);
        }

        @Override
        public void refreshContent(Entry e, Highlight highlight) {
            LocalDate d = start.plusDays((int) e.getX());
            tv.setText(d.format(FMT) + "  ¥" + String.format("%.2f", e.getY()));
            super.refreshContent(e, highlight);
        }

        @Override
        public MPPointF getOffset() {
            MPPointF o = super.getOffset();
            return new MPPointF(-(tv.getWidth() / 2f), -tv.getHeight());
        }
    }

    private void renderBudget(View v) {
        LinearLayout container = v.findViewById(R.id.budget_rows);
        container.removeAllViews();
        LocalDate now = LocalDate.now();
        for (Budget b : budgetRepo.listForMonth(now.getYear(), now.getMonthValue())) {
            double used = txRepo.categoryExpense(b.categoryClientId, now.getYear(), now.getMonthValue());
            int pct = (int) ((used / b.amountLimit) * 100);
            ProgressBar bar = new ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgress(Math.min(pct, 100));
            bar.setProgressTintList(ColorStateList.valueOf(Color.parseColor("#C0FF00")));
            TextView label = new TextView(requireContext());
            label.setText(String.format("%s  %.0f/%.0f   (%d%%)", catName(b.categoryClientId), used, b.amountLimit, pct));
            if (pct >= 100) label.setTextColor(Color.parseColor("#D64545"));
            else if (pct >= 90) label.setTextColor(Color.parseColor("#C0FF00"));
            container.addView(label);
            container.addView(bar);
        }
    }

    private void renderHeatmap(GridLayout grid) {
        int year = LocalDate.now().getYear();
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);
        Map<LocalDate, Double> perDay = new LinkedHashMap<>();
        for (var t : txRepo.listRecent(1000000)) {
            if (!"EXPENSE".equals(t.type) || t.date == null) continue;
            if (t.date.getYear() == year) perDay.merge(t.date, t.amount, Double::sum);
        }
        double max = 1;
        for (double d : perDay.values()) max = Math.max(max, d);

        grid.removeAllViews();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            double amt = perDay.getOrDefault(d, 0.0);
            int intensity = (int) Math.min(255, amt / max * 255);
            int color = amt <= 0 ? Color.parseColor("#EDEDED")
                    : Color.rgb((int) (255 + (192 - 255) * (intensity / 255.0)), 255,
                            (int) (255 + (0 - 255) * (intensity / 255.0))); // 白→酸橙绿渐变
            View cell = new View(requireContext());
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(10);
            lp.height = dp(10);
            lp.setMargins(1, 1, 1, 1);
            cell.setLayoutParams(lp);
            cell.setBackgroundColor(color);
            grid.addView(cell);
        }
    }

    private String catName(String clientId) {
        for (Category c : catRepo.all()) if (clientId.equals(c.clientId)) return c.name;
        return "未分类";
    }

    private int dp(int v) {
        return Math.round(requireContext().getResources().getDisplayMetrics().density * v);
    }
}
