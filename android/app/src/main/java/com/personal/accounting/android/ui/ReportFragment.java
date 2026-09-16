package com.personal.accounting.android.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
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
import com.personal.accounting.android.analysis.AiAnalysis;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 报表页：概览卡片 + 分类饼图 + 近30天趋势 + 预算进度 + 年度热力图。 */
public class ReportFragment extends Fragment {

    private TransactionRepo txRepo;
    private CategoryRepo catRepo;
    private BudgetRepo budgetRepo;
    private final ExecutorService aiExec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

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
        v.findViewById(R.id.btn_ai_analysis).setOnClickListener(x -> onAiAnalysis());
        v.findViewById(R.id.btn_ai_settings).setOnClickListener(x -> showAiSettings());
        return v;
    }

    // ---------- AI 消费分析：本地规则引擎（离线可用），配置了 API Key 时用大模型增强 ----------
    private void onAiAnalysis() {
        if (!AiAnalysis.isConfigured(requireContext()) && !AiAnalysis.canUseLlm(requireContext())) {
            // 首次使用：先问一次是否配置自己的 API Key，不配就用本地规则引擎
            new AlertDialog.Builder(requireContext())
                    .setTitle("AI 消费分析")
                    .setMessage("默认使用内置规则引擎（纯本地、离线可用）。\n"
                            + "也可以在「设置 → AI 消费分析」填入自己的 API Key，改用大模型分析。")
                    .setPositiveButton("用规则引擎", (d, w) -> {
                        AiAnalysis.useRulesOnly(requireContext());
                        runAnalysis();
                    })
                    .setNegativeButton("稍后", null)
                    .show();
            return;
        }
        runAnalysis();
    }

    /** 随时修改 AI 分析设置：补填 / 更换 API Key，或改回规则引擎、切回大模型。 */
    private void showAiSettings() {
        String current = AiAnalysis.useLlm(requireContext())
                ? "当前：大模型分析（已配置 API Key）"
                : "当前：本地规则引擎（未配置 API Key）";

        EditText key = new EditText(requireContext());
        key.setHint("API Key（如 sk-...）");
        EditText base = new EditText(requireContext());
        base.setHint("接口地址（默认 api.openai.com）");
        base.setText(AiAnalysis.baseUrl(requireContext()));
        EditText model = new EditText(requireContext());
        model.setHint("模型（默认 gpt-4o-mini）");
        model.setText(AiAnalysis.model(requireContext()));

        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, 0, pad, 0);
        box.addView(key);
        box.addView(base);
        box.addView(model);

        new AlertDialog.Builder(requireContext())
                .setTitle("AI 分析设置")
                .setMessage(current + "\n\n填入 API Key 后点「保存」改用大模型分析；想切回本地规则引擎可直接点「改用规则引擎」。")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    AiAnalysis.save(requireContext(), key.getText().toString(),
                            base.getText().toString(), model.getText().toString());
                    Toast.makeText(requireContext(),
                            AiAnalysis.useLlm(requireContext()) ? "已切换为大模型分析" : "未填 Key，继续用规则引擎",
                            Toast.LENGTH_SHORT).show();
                    runAnalysis();
                })
                // 一步切回规则引擎：清掉已保存的 Key，不再需要手动清空输入框
                .setNeutralButton("改用规则引擎", (d, w) -> {
                    AiAnalysis.clearLlm(requireContext());
                    Toast.makeText(requireContext(), "已改回本地规则引擎", Toast.LENGTH_SHORT).show();
                    runAnalysis();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void runAnalysis() {
        Button button = requireView().findViewById(R.id.btn_ai_analysis);
        LocalDate now = LocalDate.now();
        button.setEnabled(false);
        button.setText("分析中…");
        aiExec.execute(() -> {
            AiAnalysis.Result result = AiAnalysis.analyze(requireContext().getApplicationContext(),
                    now.getYear(), now.getMonthValue());
            main.post(() -> {
                button.setEnabled(true);
                button.setText("AI 消费分析（本月）");
                showReport(result);
            });
        });
    }

    private void showReport(AiAnalysis.Result result) {
        String title = AiAnalysis.ENGINE_LLM.equals(result.engine)
                ? "AI 消费分析（大模型）" : "AI 消费分析（本地规则）";
        new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setView(reportTextView(result))
                .setPositiveButton("好", null)
                .show();
    }

    /** 长文本放进可滚动的卡片，避免弹窗被撑爆或截断。 */
    private View reportTextView(AiAnalysis.Result result) {
        ScrollView scroll = new ScrollView(requireContext());
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, pad / 2);

        if (result.note != null && !result.note.isEmpty()) {
            TextView note = new TextView(requireContext());
            note.setText(result.note);
            note.setTextSize(12);
            note.setTextColor(0xFFF9A825);
            box.addView(note);
        }

        TextView tv = new TextView(requireContext());
        tv.setText(result.text);
        tv.setTextSize(14);
        tv.setTextColor(Color.parseColor("#1A1A1A"));
        tv.setLineSpacing(0f, 1.15f);
        box.addView(tv);

        scroll.addView(box);
        return scroll;
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
        // 分类名与金额都放到扇区外面，用引线连回扇区（扇区内只留引线锚点）
        ds.setValueTextSize(11f);
        ds.setValueTextColor(Color.parseColor("#4A4A4A"));
        ds.setXValuePosition(PieDataSet.ValuePosition.OUTSIDE_SLICE);
        ds.setYValuePosition(PieDataSet.ValuePosition.OUTSIDE_SLICE);
        ds.setValueLinePart1OffsetPercentage(70f);
        ds.setValueLinePart1Length(0.55f);
        ds.setValueLinePart2Length(0.4f);
        ds.setValueLineColor(Color.parseColor("#B0B0B0"));
        ds.setValueLineWidth(1f);
        ds.setUsingSliceColorAsValueLineColor(false);
        PieData data = new PieData(ds);
        final double totalAmount = pieTotal(byCat);
        data.setValueFormatter(new ValueFormatter() {
            @Override
            public String getPieLabel(float value, PieEntry entry) {
                // 饼图外部标签走的是 getPieLabel（不是 getFormattedValue）：
                // 分类名必须取自 entry 自身，不能按金额反查（金额相同的分类会被标错名）
                String label = entry.getLabel();
                String name = (label == null || label.isEmpty()) ? "" : label + " ";
                double percent = totalAmount > 0 ? value * 100 / totalAmount : 0;
                return String.format("%s%.0f元 · %.0f%%", name, value, percent);
            }
        });
        pie.setData(data);
        // 给外侧标签留空间：额外偏移，标签才不会溢出边界
        pie.setExtraOffsets(14f, 10f, 14f, 10f);
        pie.setDrawEntryLabels(false);
        pie.setDrawCenterText(false);
        // 保持环形图形态：中心留洞（用回 MPAndroidChart 默认值，之前误设为 0 会变成实心饼图）
        pie.setHoleRadius(50f);
        pie.setTransparentCircleRadius(55f);
        pie.getDescription().setEnabled(false);
        // 外侧标签已含分类名与占比，图例冗余，隐藏以腾出空间
        pie.getLegend().setEnabled(false);
        pie.invalidate();
    }

    private void renderTrend(LineChart chart) {
        chart.setNoDataText("暂无数据");
        chart.setNoDataTextColor(Color.parseColor("#A6A6A6"));
        int days = 30;
        LocalDate start = LocalDate.now().minusDays(days - 1L);
        // 每日支出：只统计支出（不含收入），逐日汇总
        Map<LocalDate, Double> perDayExp = new LinkedHashMap<>();
        for (var t : txRepo.listRecent(1000000)) {
            if (t.date == null) continue;
            if (t.date.isBefore(start) || t.date.isAfter(LocalDate.now())) continue;
            if ("EXPENSE".equals(t.type)) perDayExp.merge(t.date, t.amount, Double::sum);
        }
        List<Entry> entries = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate d = start.plusDays(i);
            labels.add(d.getDayOfMonth() + "");
            entries.add(new Entry(i, perDayExp.getOrDefault(d, 0.0).floatValue()));
        }
        LineDataSet ds = new LineDataSet(entries, "每日支出");
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
        // 只统计支出，数值非负；仍不强制 Y 轴最小值为 0，避免小金额被压平
        chart.getAxisLeft().setTextColor(Color.parseColor("#8C8C8C"));
        chart.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format("%.0f", value);
            }
        });
        chart.getLegend().setTextColor(Color.parseColor("#6B6B6B"));
        // 点击数据点：显示具体值（日期 + 当日支出金额）
        chart.setMarker(new DayMarker(requireContext(), start));
        chart.invalidate();
    }

    /** 点击数据点时弹出的标记：显示“日期 + 当日支出金额”。 */
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

    /** 饼图各分类金额合计，用于计算占比。 */
    private static double pieTotal(Map<String, Double> byCategory) {
        double total = 0;
        for (double v : byCategory.values()) total += v;
        return total;
    }

    private int dp(int v) {
        return Math.round(requireContext().getResources().getDisplayMetrics().density * v);
    }
}
