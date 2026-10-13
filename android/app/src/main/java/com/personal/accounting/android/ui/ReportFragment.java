package com.personal.accounting.android.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.BarLineChartBase;
import com.github.mikephil.charting.charts.Chart;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.components.MarkerView;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.highlight.Highlight;
import com.personal.accounting.android.R;
import com.personal.accounting.android.analysis.AiAnalysis;
import com.personal.accounting.android.analysis.AiSettingsDialog;
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

    /** 占比大于该值的扇区，标签默认画在环内；以下的小扇区直接画在环外。 */
    private static final float MIN_PERCENT_INSIDE_LABEL = 10f;

    /** 跨月趋势看几个月（含当月）。 */
    private static final int MONTHS_IN_TREND = 6;

    /** 折线图气泡里的日期格式：10.9。 */
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("M.d");

    private TransactionRepo txRepo;
    private CategoryRepo catRepo;
    private BudgetRepo budgetRepo;

    /** 跨月趋势当前看的是不是收入（否则为支出）。 */
    private boolean trendIncome;
    /** 跨月趋势限定的分类；null 表示不限（全部支出或全部收入）。 */
    private String trendCategoryId;
    /**
     * 环形图当前展示的月份；null 表示显示当月。
     *
     * <p>点跨月趋势的柱子会把它切到那个月，再点一次回到当月 —— 看历史月份明细不必退出报表页。</p>
     */
    private LocalDate pieMonth;
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
        updateTrendScopeButton(v);
        renderMonthTrend(v.findViewById(R.id.month_trend));
        v.findViewById(R.id.btn_trend_scope).setOnClickListener(x -> pickTrendScope());
        renderTrend(v.findViewById(R.id.trend));
        renderBudget(v);
        renderHeatmap(v.findViewById(R.id.heatmap_grid));
        v.findViewById(R.id.btn_ai_analysis).setOnClickListener(x -> onAiAnalysis());
        v.findViewById(R.id.btn_ai_settings).setOnClickListener(x -> showAiSettings());
        return v;
    }

    /**
     * 页面在底部导航里被切走 / 切回时由系统调用（hide/show 模式下的标准钩子）。
     *
     * <p>用 hide/show 之后页面**常驻**，切回来不会重建视图，所以图表的生长动画只播一次。
     * 这里在「重新变为可见」时重绘一次，让动画重新播放。
     * 用这个回调而不是让 Activity 主动调方法：它是系统在事务真正生效时触发的，
     * 不依赖调用时机；Activity 在 commit 之后立刻回调会因为页面还是 hidden 而被跳过。</p>
     */
    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) return;
        // 延迟一帧：此刻事务刚生效，等布局稳定后再重绘，生长动画才动得起来
        main.post(this::replayChartAnimations);
    }

    /** 重绘三张带动画的图表，让生长动画重新播放。 */
    private void replayChartAnimations() {
        View v = getView();
        if (v == null) return;
        renderPie(v.findViewById(R.id.pie));
        renderMonthTrend(v.findViewById(R.id.month_trend));
        renderTrend(v.findViewById(R.id.trend));
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

    /** 随时修改 AI 设置：补填 / 更换 API Key，或改回规则引擎、切回大模型。弹窗与记账页共用。 */
    private void showAiSettings() {
        AiSettingsDialog.show(requireContext(), this::runAnalysis);
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
            note.setTextColor(ChartColors.of(requireContext(), R.color.warning));
            box.addView(note);
        }

        TextView tv = new TextView(requireContext());
        tv.setText(result.text);
        tv.setTextSize(14);
        tv.setTextColor(ChartColors.of(requireContext(), R.color.text_primary));
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
        momView.setTextColor(mom >= 0
                ? ChartColors.of(requireContext(), R.color.expense)
                : ChartColors.of(requireContext(), R.color.income));
    }

    private void renderPie(PieChart pie) {
        // 占位文案说清「为什么是空的」和「怎么让它有内容」，比一句「暂无数据」有用
        pie.setNoDataText("本月还没有支出记录\n记一笔就会出现在这里");
        pie.setNoDataTextColor(ChartColors.of(requireContext(), R.color.no_data));
        shrinkNoDataText(pie);
        LocalDate now = LocalDate.now();
        // 默认当月；点了跨月趋势的柱子则显示那个月
        LocalDate month = pieMonth == null ? now.withDayOfMonth(1) : pieMonth;

        Map<String, Double> byCat = expenseByCategory(month);

        View root = getView();
        TextView title = root == null ? null : root.findViewById(R.id.pie_title);
        if (title != null) title.setText(pieTitleText(month, byCat, now));

        List<PieEntry> entries = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        // 调色板随深浅模式切换（values / values-night 各一套），大扇区固定落在靠前的浅色上
        int[] palette = ChartColors.piePalette(requireContext());
        int i = 0;
        for (Map.Entry<String, Double> e : byCat.entrySet()) {
            entries.add(new PieEntry(e.getValue().floatValue(), e.getKey()));
            colors.add(palette[i++ % palette.length]);
        }
        if (entries.isEmpty()) {
            pie.clear();
            pie.invalidate();
            return;
        }
        PieDataSet ds = new PieDataSet(entries, "支出构成");
        ds.setColors(colors);
        // 标签位置由 PieSliceLabelRenderer 按扇区分别决定：
        // >10% 的扇区标在扇区内（金额一行、分类名一行），<=10% 的标在扇区外用引线连回
        ds.setValueTextSize(12f);
        ds.setValueTextColor(ChartColors.of(requireContext(), R.color.chart_text));
        ds.setXValuePosition(PieDataSet.ValuePosition.INSIDE_SLICE);
        ds.setYValuePosition(PieDataSet.ValuePosition.INSIDE_SLICE);
        PieData data = new PieData(ds);
        data.setValueFormatter(new ValueFormatter() {
            @Override
            public String getPieLabel(float value, PieEntry entry) {
                // 实际标签由 PieSliceLabelRenderer 绘制（它需要"按扇区"决定内/外），
                // 这里只保留取值口径：分类名取自 entry 自身，不能按金额反查
                String label = entry.getLabel();
                return (label == null || label.isEmpty()) ? "未分类" : label;
            }
        });
        pie.setData(data);
        // 给外侧标签留空间：加大上下边距（标签主要在上下两侧），避免出界
        pie.setExtraOffsets(10f, 26f, 10f, 26f);
        pie.setDrawEntryLabels(false);
        pie.setDrawCenterText(false);
        // 环形图：洞更大、圆更小，给外置标签让出空间
        pie.setHoleRadius(58f);
        pie.setTransparentCircleRadius(62f);
        // 自定义渲染器：大扇区内置标签、小扇区外置标签 + 引线。
        // 标签反色由渲染器按每个扇区的实际颜色自动选（浅扇区深字、深扇区浅字），
        // 所以深浅两套调色板共用同一个渲染器。
        PieSliceLabelRenderer renderer = new PieSliceLabelRenderer(pie, pie.getViewPortHandler(), pie.getAnimator(),
                MIN_PERCENT_INSIDE_LABEL,
                dp(12),                          // 金额字号
                dp(11),                          // 分类名字号
                ChartColors.of(requireContext(), R.color.pie_label_name),   // 深色底上的浅标签
                ChartColors.of(requireContext(), R.color.pie_label_value),  // 浅色底上的深标签
                ChartColors.of(requireContext(), R.color.pie_label_halo_for_dark),
                ChartColors.of(requireContext(), R.color.pie_label_halo_for_light),
                ChartColors.of(requireContext(), R.color.no_data),          // 极窄扇区兜底引线
                ChartColors.of(requireContext(), R.color.background));      // 环外标签压着的页面底色
        pie.setRenderer(renderer);

        // 洞与「透明圆」都要设成舞台底色。
        // 两个原因必须这样写：
        //   1. 这两个 Paint 的颜色是 MPAndroidChart 内部写死的纯白（setColor(-1)），不受主题影响；
        //      drawHole() 先画 transparentCircleRadius 处的圆、再画洞，深色下就是深底顶着一个白圆。
        //   2. PieChart.setHoleColor()/setTransparentCircleColor() 是「取当前 mRenderer 的 Paint 再设色」，
        //      必须在这之后调用；直接对刚装上的渲染器设色最稳，不依赖封装顺序。
        int chartBg = ChartColors.of(requireContext(), R.color.background);
        renderer.getPaintHole().setColor(chartBg);
        renderer.getPaintTransparentCircle().setColor(chartBg);

        pie.getDescription().setEnabled(false);
        // 标签已含金额与分类名，图例冗余，隐藏以腾出空间
        pie.getLegend().setEnabled(false);
        // 生长动画：按数据扫开 / 从底部升起
        animateGrowth(pie);
    }

    /**
     * 指定月份的支出分类合计（按金额从多到少）。
     *
     * <p>分类名取自分类表而不是 clientId：流水里存的是 clientId，直接显示会是一串英文。</p>
     */
    private Map<String, Double> expenseByCategory(LocalDate month) {
        LocalDate start = month.withDayOfMonth(1);
        LocalDate end = month.withDayOfMonth(month.lengthOfMonth());
        Map<String, Double> byCategory = new LinkedHashMap<>();
        for (var t : txRepo.search(start, end, null, null, null)) {
            if (!"EXPENSE".equals(t.type) || t.categoryClientId == null) continue;
            byCategory.merge(catName(t.categoryClientId), t.amount, Double::sum);
        }
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(byCategory.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        Map<String, Double> ordered = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : sorted) ordered.put(e.getKey(), e.getValue());
        return ordered;
    }

    /**
     * 环形图标题：当月就是原来的「支出分类占比」，选了历史月份则写明是哪个月、共多少，
     * 并提示怎么切回来 —— 否则用户看不出这张图和上面的卡片不是同一个月。
     */
    private static String pieTitleText(LocalDate month, Map<String, Double> byCategory, LocalDate now) {
        if (month.getYear() == now.getYear() && month.getMonthValue() == now.getMonthValue()) {
            return "支出分类占比（本月）";
        }
        double total = 0;
        for (double v : byCategory.values()) total += v;
        return String.format("%d年%d月支出分类占比：%.2f 元（再点一次该柱回到本月）",
                month.getYear(), month.getMonthValue(), total);
    }

    // ---------- 跨月趋势：近 6 个月，一根柱子一个月 ----------

    /**
     * 柱状图一次只画一个口径（支出 / 收入 / 某个分类）。
     *
     * <p>没有做成「收入支出并排」是因为那需要 MPAndroidChart 的分组柱（多个 dataset 共享 x
     * 加 groupBars 手动排版），出问题时在这台机器上看不出来；单序列没有这个风险，
     * 而想对比时切换一下范围就够了。</p>
     */
    private void renderMonthTrend(BarChart chart) {
        chart.setNoDataText("近半年还没有支出记录\n记一笔就会出现在这里");
        chart.setNoDataTextColor(ChartColors.of(requireContext(), R.color.no_data));
        shrinkNoDataText(chart);

        LocalDate now = LocalDate.now();
        LocalDate start = now.withDayOfMonth(1).minusMonths(MONTHS_IN_TREND - 1L);
        LocalDate end = now.withDayOfMonth(now.lengthOfMonth());
        Map<String, double[]> totals = txRepo.monthlyTotals(start, end, trendCategoryId);

        List<BarEntry> entries = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        // 被选中（环形图正在显示）的那个月用深色柱子标出来
        List<Integer> barColors = new ArrayList<>();
        int baseColor = trendIncome
                ? ChartColors.of(requireContext(), R.color.income)
                : ChartColors.of(requireContext(), R.color.primary);
        int selectedColor = ChartColors.of(requireContext(), R.color.chart_text);
        LocalDate pie = pieMonth;
        for (int i = 0; i < MONTHS_IN_TREND; i++) {
            LocalDate m = start.plusMonths(i);
            double[] pair = totals.get(monthKey(m));
            double value = pair == null ? 0 : (trendIncome ? pair[0] : pair[1]);
            entries.add(new BarEntry(i, (float) value));
            labels.add(m.getMonthValue() + "月");
            boolean selected = pie != null
                    && pie.getYear() == m.getYear() && pie.getMonthValue() == m.getMonthValue();
            barColors.add(selected ? selectedColor : baseColor);
        }

        BarDataSet ds = new BarDataSet(entries, trendIncome ? "收入" : "支出");
        ds.setColors(barColors);
        ds.setValueTextColor(ChartColors.of(requireContext(), R.color.chart_text));
        ds.setValueTextSize(10f);
        ds.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                // 没有数据的月份不标数字，免得柱子上挂一排 0
                return value <= 0 ? "" : String.format("%.0f", value);
            }
        });

        chart.setData(new BarData(ds));
        // 不设 marker：柱子顶部已经标了金额，而这里选中的语义是「切换上方环形图到那个月」，
        // 选中后紧接着就会清掉高亮（见 selectTrendMonth），气泡只会闪一下反而干扰。
        // 再点一次同一根柱子：切换上面的环形图到那个月 / 回到当月
        chart.setOnChartValueSelectedListener(new com.github.mikephil.charting.listener.OnChartValueSelectedListener() {
            @Override
            public void onValueSelected(Entry e, Highlight h) {
                selectTrendMonth(start, (int) e.getX(), chart);
            }

            @Override
            public void onNothingSelected() {
                // 手指抬起就清除高亮，选中状态由柱子颜色表示，不依赖高亮
            }
        });
        chart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(labels));
        chart.getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM);
        chart.getXAxis().setGranularity(1f);
        chart.getXAxis().setLabelCount(MONTHS_IN_TREND);
        chart.getXAxis().setTextColor(ChartColors.of(requireContext(), R.color.chart_axis));
        chart.getXAxis().setTypeface(axisTypeface());
        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.getAxisRight().setEnabled(false);
        chart.getAxisLeft().setTextColor(ChartColors.of(requireContext(), R.color.chart_axis));
        chart.getAxisLeft().setTypeface(axisTypeface());
        chart.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format("%.0f", value);
            }
        });
        // 网格线统一到配色表的 1px 细线，保留这套工业语言的秩序感
        styleGridLines(chart);
        // 柱子从底部升起（与圆环图同一个节奏与缓动）
        animateGrowth(chart);
    }

    /**
     * 点跨月趋势的柱子 → 上面的环形图切到那个月；再点同一根 → 回到当月。
     *
     * <p>点完就把高亮清掉：高亮块会一直留在柱子上，而「当前选中」这件事已经由柱子颜色表达了。</p>
     *
     * @param trendStart 趋势图第一根柱子对应的月份
     * @param index      被点柱子的下标（0 起）
     */
    private void selectTrendMonth(LocalDate trendStart, int index, BarChart chart) {
        if (index < 0 || index >= MONTHS_IN_TREND) return;
        LocalDate clicked = trendStart.plusMonths(index).withDayOfMonth(1);
        LocalDate now = LocalDate.now().withDayOfMonth(1);
        // 点当月 = 取消选择；点已选中的历史月份也回到当月
        boolean sameAsCurrent = clicked.getYear() == now.getYear() && clicked.getMonthValue() == now.getMonthValue();
        if (sameAsCurrent || (pieMonth != null && pieMonth.equals(clicked))) {
            pieMonth = null;
        } else {
            pieMonth = clicked;
        }
        chart.highlightValue(null);
        renderMonthTrend(chart);
        View root = getView();
        if (root != null) renderPie(root.findViewById(R.id.pie));
    }

    /** 切换统计范围：支出（全部）/ 收入（全部）/ 某个分类的支出。 */
    private void pickTrendScope() {
        List<Category> cats = catRepo.allExpenseFirst();
        String[] labels = new String[cats.size() + 2];
        labels[0] = "支出（全部）";
        labels[1] = "收入（全部）";
        for (int i = 0; i < cats.size(); i++) {
            Category c = cats.get(i);
            labels[i + 2] = (c.icon == null || c.icon.isEmpty() ? "" : c.icon + " ") + c.name;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("统计范围")
                .setItems(labels, (d, which) -> {
                    if (which == 0) {
                        trendIncome = false;
                        trendCategoryId = null;
                    } else if (which == 1) {
                        trendIncome = true;
                        trendCategoryId = null;
                    } else {
                        trendIncome = false;
                        trendCategoryId = cats.get(which - 2).clientId;
                    }
                    // 柱子/口径换了，之前选中的月份不再对应当前视图：环形图回到当月
                    pieMonth = null;
                    View root = getView();
                    if (root == null) return;
                    updateTrendScopeButton(root);
                    renderMonthTrend(root.findViewById(R.id.month_trend));
                    renderPie(root.findViewById(R.id.pie));
                })
                .show();
    }

    private void updateTrendScopeButton(View root) {
        Button button = root.findViewById(R.id.btn_trend_scope);
        String scope = trendCategoryId != null ? catName(trendCategoryId) : (trendIncome ? "收入" : "支出");
        button.setText("统计范围：" + scope + "（点击切换）");
    }

    /** 与 SQL 里 substr(date,1,7) 对齐：2026-09。 */
    private static String monthKey(LocalDate d) {
        return String.format("%04d-%02d", d.getYear(), d.getMonthValue());
    }

    private void renderTrend(TrendLineChart chart) {
        chart.setNoDataText("最近 30 天还没有支出记录\n记一笔就会出现在这里");
        chart.setNoDataTextColor(ChartColors.of(requireContext(), R.color.no_data));
        shrinkNoDataText(chart);
        int days = 30;
        LocalDate start = LocalDate.now().minusDays(days - 1L);
        LocalDate end = LocalDate.now();
        Map<String, Double> perDayExp = txRepo.dailyExpenseBetween(start.toString(), end.toString());
        List<Entry> entries = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate d = start.plusDays(i);
            labels.add(d.getDayOfMonth() + "");
            entries.add(new Entry(i, perDayExp.getOrDefault(d.toString(), 0.0).floatValue()));
        }
        LineDataSet ds = new LineDataSet(entries, "每日支出");
        ds.setColor(ChartColors.of(requireContext(), R.color.primary));
        ds.setLineWidth(2f);
        ds.setDrawFilled(true);
        // 折线下方的填充：极低不透明度的酸橙绿，深浅底上都成立
        ds.setFillColor(ChartColors.of(requireContext(), R.color.chart_fill));
        ds.setDrawCircles(false);
        ds.setDrawValues(false);
        LineData data = new LineData(ds);
        chart.setData(data);
        chart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(labels));
        chart.getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM);
        chart.getXAxis().setLabelCount(8);
        chart.getXAxis().setGranularity(1f);
        chart.getXAxis().setTextColor(ChartColors.of(requireContext(), R.color.chart_axis));
        chart.getXAxis().setTypeface(axisTypeface());
        chart.getDescription().setEnabled(false);
        chart.getAxisRight().setEnabled(false);
        // 只统计支出，数值非负；仍不强制 Y 轴最小值为 0，避免小金额被压平
        chart.getAxisLeft().setTextColor(ChartColors.of(requireContext(), R.color.chart_axis));
        chart.getAxisLeft().setTypeface(axisTypeface());
        chart.getAxisLeft().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format("%.0f", value);
            }
        });
        chart.getLegend().setTextColor(ChartColors.of(requireContext(), R.color.chart_text));
        // 网格线统一到配色表的 1px 细线
        styleGridLines(chart);
        // 上下各留 28dp：气泡画在数据点上方，贴近图顶时才有位置可放，
        // 免得被 PlotArea 裁掉（Math 上「翻到下方」也没用，下方就是折线本身）。
        chart.setExtraOffsets(10f, 28f, 10f, 28f);
        // 气泡由 TrendLineChart 自己在画布上绘制（不用 MarkerView：它那套偏移逻辑
        // 拿到的气泡宽度是旧值，钳制会算错、右边缘被裁）。
        chart.setOnChartValueSelectedListener(new com.github.mikephil.charting.listener.OnChartValueSelectedListener() {
            @Override
            public void onValueSelected(Entry e, Highlight h) {
                LocalDate d = start.plusDays((int) e.getX());
                chart.setTipText(DAY_LABEL.format(d) + "  ¥" + String.format("%.2f", e.getY()));
            }

            @Override
            public void onNothingSelected() {
                chart.setTipText(null);
            }
        });
        // 折线从底部升起（与另外两张图同一个节奏与缓动）
        animateGrowth(chart);
    }

    /**
     * 图表的生长动画：环形图按数据扫开、柱状图与折线图从底部升起。
     *
     * <p>为什么不用库自带的 {@code animateY}：它会创建 {@code ObjectAnimator} 去动画
     * {@code ChartAnimator.mPhaseY}，机制本身正常，但实测在真机上不产生可见效果。
     * 这里改成**自己逐帧驱动同一个相位** —— 用 {@code ValueAnimator} 调
     * {@code animator.setPhaseY(...)} 并重绘。渲染端读取相位的逻辑（环形图渲染器 10 处、
     * 柱状图渲染器 6 处）原样保留，所以观感就是库原本那种「按数据生长」，
     * 只是驱动源换成了可靠的一类。</p>
     *
     * <p>相位起点取 0.02 而不是 0：避免整帧完全不可见导致的闪烁。</p>
     */
    private void animateGrowth(Chart chart) {
        if (chart == null) return;
        chart.post(() -> {
            final ValueAnimator va = ValueAnimator.ofFloat(0.02f, 1f);
            // 800ms。这个值比常见的入场动画（200~300ms）长不少，是刻意的：
            // 环形图扫开是覆盖整块画布的连续变化，时长太短会被看成「一下就位」而不是生长。
            // 实测 600ms 可辨、280ms 基本看不见，取 800 留出余量。
            va.setDuration(800);
            va.setInterpolator(new DecelerateInterpolator());
            va.addUpdateListener(a -> {
                chart.getAnimator().setPhaseY((float) a.getAnimatedValue());
                chart.invalidate();
            });
            va.start();
        });
    }

    /**
     * 把坐标轴网格线统一到这套工业语言：1px 细线 + 配色表的 hairline 色。
     *
     * <p>不设的话用的是 MPAndroidChart 默认的虚线灰网格，和「酸橙绿 + 直角 + 细线」这套观感不是一路。
     * 同时把坐标轴自身的线也收成细线，避免出现比数据更抢眼的分隔线。</p>
     */
    private void styleGridLines(BarLineChartBase<?> chart) {
        int lineColor = ChartColors.of(requireContext(), R.color.chart_line);
        float onePx = 1f;

        XAxis x = chart.getXAxis();
        x.setDrawGridLines(false);          // 竖向网格线对这几张图没有信息量，去掉更干净
        x.setDrawAxisLine(true);
        x.setAxisLineColor(lineColor);
        x.setAxisLineWidth(onePx);

        YAxis left = chart.getAxisLeft();
        left.setDrawGridLines(true);
        left.setGridColor(lineColor);
        left.setGridLineWidth(onePx);
        left.setDrawAxisLine(false);        // 左侧不画竖线：网格已经承担了刻度参照
    }

    /**
     * 坐标轴数字用等宽字体：设计规范要求关键数据用 tabular numerals，
     * 等宽能保证刻度数字左右对齐（默认比例字体下 1 和 8 的宽度不同，刻度会参差）。
     */
    private android.graphics.Typeface axisTypeface() {
        return android.graphics.Typeface.MONOSPACE;
    }

    /**
     * 收小「空状态占位文字」的字号。
     *
     * <p>MPAndroidChart v3.1.0 的 Chart 上没有 {@code setNoDataTextSize}，
     * 占位文字是用内部的 {@code mInfoPaint} 画的，只能通过 {@link Chart#getPaint(int)}
     * 取到 {@link Chart#PAINT_INFO} 再调。默认 12sp 是给单行设计的，
     * 我们这里写了两行文案，收到 11sp 才不显得挤。</p>
     */
    private void shrinkNoDataText(Chart<?> chart) {
        android.graphics.Paint info = chart.getPaint(Chart.PAINT_INFO);
        if (info == null) return;
        info.setTextSize(11f * getResources().getDisplayMetrics().scaledDensity);
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
            bar.setProgressTintList(ColorStateList.valueOf(ChartColors.of(requireContext(), R.color.primary)));
            TextView label = new TextView(requireContext());
            label.setText(String.format("%s  %.0f/%.0f   (%d%%)", catName(b.categoryClientId), used, b.amountLimit, pct));
            if (pct >= 100) label.setTextColor(ChartColors.of(requireContext(), R.color.expense));
            else if (pct >= 90) label.setTextColor(ChartColors.of(requireContext(), R.color.warning));
            container.addView(label);
            container.addView(bar);
        }
    }

    private void renderHeatmap(GridLayout grid) {
        int year = LocalDate.now().getYear();
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);
        Map<String, Double> perDay = txRepo.dailyExpenseForYear(year);
        double max = 1;
        for (double d : perDay.values()) max = Math.max(max, d);

        // 空值色与强调色都走资源：深色模式下空格子不能还是浅灰，否则整片热力图会发亮
        int emptyColor = ChartColors.of(requireContext(), R.color.heatmap_empty);
        int accent = ChartColors.of(requireContext(), R.color.primary);

        grid.removeAllViews();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            double amt = perDay.getOrDefault(d.toString(), 0.0);
            int color = amt <= 0 ? emptyColor : blend(emptyColor, accent, Math.min(1.0, amt / max));
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

    /**
     * 在底色与强调色之间按比例插值。
     *
     * <p>原来这里是把「白→酸橙绿」的通道值写死算出来的（{@code 255 + (192-255)*t} 之类），
     * 深色模式下底色不再是白色，那套硬编码就会算出错误的颜色。改成通用插值，
     * 底色换成近黑也自然成立。</p>
     *
     * @param t 0..1 的强度
     */
    private static int blend(int from, int to, double t) {
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return Color.rgb(clamp(r), clamp(g), clamp(b));
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
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
