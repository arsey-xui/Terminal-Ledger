package com.personal.accounting.desktop.ui;

import com.personal.accounting.desktop.data.DataManager;
import com.personal.accounting.desktop.model.Budget;
import com.personal.accounting.desktop.model.Category;
import com.personal.accounting.desktop.model.Transaction;
import com.personal.accounting.desktop.service.AiConfig;
import com.personal.accounting.desktop.service.AiReportGenerator;
import com.personal.accounting.desktop.service.ReportMath;
import com.personal.accounting.desktop.sync.SyncManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 仪表盘：概览卡片 + 分类饼图 + 趋势折线 + 预算进度 + 日历热力图 + AI 分析。 */
public class DashboardController {

    @FXML private Label labelIncome, labelExpense, labelBalance, labelMom;
    @FXML private Button btnSync;
    @FXML private PieChart pie;
    @FXML private LineChart<Number, Number> line;
    @FXML private BarChart<String, Number> monthTrend;
    @FXML private ComboBox<String> monthTrendScope;
    @FXML private VBox budgetBox;
    @FXML private GridPane heatmap;
    @FXML private TextArea aiReport;
    @FXML private Label pieEmpty, lineEmpty, budgetEmpty, heatEmpty, monthTrendEmpty;

    private boolean lineHasData, heatHasData, monthTrendHasData;

    /** 跨月趋势看几个月（含当月）。 */
    private static final int MONTHS_IN_TREND = 6;

    /** 跨月趋势当前看的是不是收入（否则为支出）。 */
    private boolean monthTrendIncome;
    /** 跨月趋势限定的分类；null 表示不限。 */
    private String monthTrendCategoryId;
    /** 下拉框里第 3 项起对应的分类，避免每次切换都重查。 */
    private List<Category> monthTrendCategories = new ArrayList<>();

    /**
     * 饼图当前展示的月份；null 表示本月。
     *
     * <p>点跨月趋势的柱子会把它切到那个月，再点一次回到本月 —— 看历史月份的分类构成不必退出仪表盘。</p>
     */
    private LocalDate pieMonth;

    /** 趋势图里被选中那根柱子的颜色；与 pieMonth 同步。 */
    private static final String TREND_SELECTED_COLOR = "#4A4A4A";

    // ---------------- 调色板 ----------------
    // 与手机端 res/values/colors.xml 是同一套语义角色。
    // 图表里的颜色尽量集中在这里，避免又散回成各处字面量。
    /** 强调色：酸橙绿，品牌锚点。 */
    private static final Color COLOR_PRIMARY = Color.web("#C0FF00");
    /** 支出 / 上涨警示。 */
    private static final Color COLOR_EXPENSE = Color.web("#D64545");
    /** 收入 / 节流成效。 */
    private static final Color COLOR_INCOME = Color.web("#2E9E24");
    /** 细线族的浅灰：图表网格线与热力图空格子。 */
    private static final Color COLOR_HAIRLINE = Color.web("#D9D9D9");

    private final DataManager db = AppState.db();

    @FXML
    public void initialize() {
        // 标题由 refresh() 按选中月份重设
        line.setTitle("近30天每日支出");
        initMonthTrendScope();
        AiConfig.load();
        if (AiConfig.isSet()) {
            refresh();
        } else {
            // 首次：询问是否已有 API Key（结束后刷新界面）
            AiSettingsDialog.show(this::refresh);
        }
    }

    @FXML
    public void onSync() {
        try {
            String msg = new SyncManager(db).sync();
            refresh();
            new Alert(Alert.AlertType.INFORMATION, msg).show();
        } catch (Exception e) {
            new Alert(Alert.AlertType.INFORMATION,
                    "暂时连不上云端（后端未启动时属正常），已保持本地离线使用。\n" + e.getMessage()).show();
        }
    }

    private void info(String msg) {
        new Alert(Alert.AlertType.INFORMATION, msg).show();
    }

    private void refresh() {
        LocalDate now = LocalDate.now();
        LocalDate start = now.withDayOfMonth(1);
        LocalDate end = now.withDayOfMonth(now.lengthOfMonth());
        List<Transaction> cur = db.listTransactions(start, end, null, null);
        LocalDate prev = now.minusMonths(1);
        List<Transaction> prevTxns = db.listTransactions(
                prev.withDayOfMonth(1), prev.withDayOfMonth(prev.lengthOfMonth()), null, null);

        BigDecimal income = sum(cur, "INCOME");
        BigDecimal expense = sum(cur, "EXPENSE");
        labelIncome.setText(String.format("¥%.2f", income));
        labelExpense.setText(String.format("¥%.2f", expense));
        labelBalance.setText(String.format("¥%.2f", income.subtract(expense)));

        BigDecimal prevExpense = sum(prevTxns, "EXPENSE");
        double mom = prevExpense.compareTo(BigDecimal.ZERO) > 0
                ? expense.subtract(prevExpense).multiply(BigDecimal.valueOf(100))
                .divide(prevExpense, 2, java.math.RoundingMode.HALF_UP).doubleValue()
                : 0;
        labelMom.setText(String.format("%+.1f%%", mom));
        labelMom.setTextFill(mom >= 0 ? COLOR_EXPENSE : COLOR_INCOME);

        renderPie(now, cur);
        renderLine(now);
        renderMonthTrend(now);
        renderBudget(now);
        renderHeatmap(now.getYear());
        aiReport.setText("正在生成 AI 分析报告...");
        new Thread(() -> {
            try {
                String report = new AiReportGenerator(db).generate(now.getYear(), now.getMonthValue());
                javafx.application.Platform.runLater(() -> aiReport.setText(report));
            } catch (Exception e) {
                javafx.application.Platform.runLater(() -> aiReport.setText("AI 报告生成失败"));
            }
        }).start();
        pieEmpty.setVisible(pie.getData().isEmpty());
        lineEmpty.setVisible(!lineHasData);
        monthTrendEmpty.setVisible(!monthTrendHasData);
        budgetEmpty.setVisible(budgetBox.getChildren().isEmpty());
        heatEmpty.setVisible(!heatHasData);
    }

    /**
     * 饼图：默认画本月的支出分类构成；点过跨月趋势的柱子则画那个月。
     *
     * <p>标题同时说明画的是哪个月、共多少 —— 否则用户会以为它跟上面的卡片是同一个口径。</p>
     */
    private void renderPie(LocalDate now, List<Transaction> currentMonthTxns) {
        LocalDate month = pieMonth == null ? now.withDayOfMonth(1) : pieMonth;
        boolean isCurrentMonth = month.getYear() == now.getYear() && month.getMonthValue() == now.getMonthValue();

        Map<String, BigDecimal> totals;
        if (isCurrentMonth) {
            totals = categoryTotals(currentMonthTxns);
        } else {
            totals = categoryTotals(db.listTransactions(
                    month.withDayOfMonth(1), month.withDayOfMonth(month.lengthOfMonth()), null, null));
        }

        List<PieChart.Data> data = totals.entrySet().stream()
                .map(e -> new PieChart.Data(db.categoryName(e.getKey()), e.getValue().doubleValue()))
                .toList();
        pie.getData().setAll(data);

        if (isCurrentMonth) {
            pie.setTitle("支出构成（本月）");
            return;
        }
        BigDecimal total = totals.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        pie.setTitle(String.format("%d年%d月支出构成：%.2f 元（再点该柱回到本月）",
                month.getYear(), month.getMonthValue(), total));
    }

    private void renderLine(LocalDate now) {
        int days = 30;
        LocalDate s = now.minusDays(days - 1L);
        // 每日支出：只统计支出（不含收入），逐日汇总
        List<Transaction> txns = db.listTransactions(s, now, null, null);
        Map<LocalDate, BigDecimal> perDayExp = new HashMap<>();
        for (Transaction t : txns) {
            if (t.date == null) continue;
            BigDecimal amt = t.amount == null ? BigDecimal.ZERO : t.amount;
            if ("EXPENSE".equals(t.type)) perDayExp.merge(t.date, amt, BigDecimal::add);
        }
        lineHasData = !txns.isEmpty();
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        List<Double> expensePerDay = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate d = s.plusDays(i);
            double expense = perDayExp.getOrDefault(d, BigDecimal.ZERO).doubleValue();
            expensePerDay.add(expense);
            series.getData().add(new XYChart.Data<>(i, expense));
        }
        line.getData().setAll(series);
        // 启用数据点符号，使每个点可悬停/点击查看数值
        line.setCreateSymbols(true);

        // X 轴：索引映射成日期；Y 轴：当日支出金额
        NumberAxis xAxis = (NumberAxis) line.getXAxis();
        xAxis.setTickUnit(5);
        xAxis.setLabel("日期");
        xAxis.setTickLabelFormatter(new StringConverter<Number>() {
            @Override
            public String toString(Number n) {
                return s.plusDays(n.intValue()).format(DateTimeFormatter.ofPattern("MM-dd"));
            }

            @Override
            public Number fromString(String string) {
                return null;
            }
        });
        NumberAxis yAxis = (NumberAxis) line.getYAxis();
        yAxis.setLabel("支出(元)");
        yAxis.setTickLabelFormatter(new StringConverter<Number>() {
            @Override
            public String toString(Number n) {
                return String.format("%.0f", n.doubleValue());
            }

            @Override
            public Number fromString(String string) {
                return null;
            }
        });

        // 悬停/点击数据点：显示具体值（日期 + 当日支出金额）。
        // 直接按鼠标最近的数据点节点定位，避免依赖节点布局时机。
        final DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern("M.d");
        final Tooltip tip = new Tooltip();
        line.setOnMouseMoved(e -> {
            XYChart.Data<Number, Number> best = null;
            double bestDist = Double.MAX_VALUE;
            double sx = e.getSceneX(), sy = e.getSceneY();
            for (XYChart.Data<Number, Number> d : series.getData()) {
                Node n = d.getNode();
                if (n == null) continue;
                var c = n.localToScene(n.getBoundsInLocal().getCenterX(), n.getBoundsInLocal().getCenterY());
                double dist = Math.hypot(sx - c.getX(), sy - c.getY());
                if (dist < bestDist) {
                    bestDist = dist;
                    best = d;
                }
            }
            if (best != null && bestDist < 60) {
                int idx = best.getXValue().intValue();
                LocalDate day = s.plusDays(idx);
                tip.setText(day.format(dayFmt) + "  支出 ¥" + String.format("%.2f", expensePerDay.get(idx)));
                tip.show(line, e.getScreenX(), e.getScreenY() + 14);
            } else {
                tip.hide();
            }
        });
        line.setOnMouseExited(e -> tip.hide());
    }

    /** 跨月趋势：近 6 个月的支出，鼠标悬停显示具体金额，点击切换上面饼图的月份。 */
    private void renderMonthTrend(LocalDate now) {
        LocalDate start = now.withDayOfMonth(1).minusMonths(MONTHS_IN_TREND - 1L);
        LocalDate end = now.withDayOfMonth(now.lengthOfMonth());
        Map<String, BigDecimal[]> totals = db.monthlyTotals(start, end, monthTrendCategoryId);
        final String unit = monthTrendIncome ? "收入" : "支出";

        XYChart.Series<String, Number> series = new XYChart.Series<>();
        boolean hasData = false;
        Map<String, LocalDate> monthByLabel = new HashMap<>();
        for (int i = 0; i < MONTHS_IN_TREND; i++) {
            LocalDate m = start.plusMonths(i);
            BigDecimal[] pair = totals.get(monthKey(m));
            BigDecimal value = pair == null ? BigDecimal.ZERO : pair[monthTrendIncome ? 0 : 1];
            if (value.compareTo(BigDecimal.ZERO) > 0) hasData = true;
            series.getData().add(new XYChart.Data<>(m.getMonthValue() + "月", value.doubleValue()));
            monthByLabel.put(m.getMonthValue() + "月", m.withDayOfMonth(1));
        }
        monthTrendHasData = hasData;
        monthTrend.getData().setAll(series);
        monthTrend.setLegendVisible(false);
        monthTrend.setAnimated(false);
        applyTrendSelection(series, monthByLabel);

        NumberAxis yAxis = (NumberAxis) monthTrend.getYAxis();
        yAxis.setLabel("支出(元)");
        yAxis.setTickLabelFormatter(new StringConverter<Number>() {
            @Override
            public String toString(Number n) {
                return String.format("%.0f", n.doubleValue());
            }

            @Override
            public Number fromString(String string) {
                return null;
            }
        });

        // 悬停显示「月份 + 金额」。与折线图同一套做法：按鼠标最近的数据点节点定位，
        // 不依赖节点是否已经布局完成（没布局时 getNode() 为 null，跳过即可）。
        final Tooltip tip = new Tooltip();
        monthTrend.setOnMouseMoved(e -> {
            XYChart.Data<String, Number> best = null;
            double bestDist = Double.MAX_VALUE;
            for (XYChart.Data<String, Number> d : series.getData()) {
                Node n = d.getNode();
                if (n == null) continue;
                var c = n.localToScene(n.getBoundsInLocal().getCenterX(), n.getBoundsInLocal().getCenterY());
                double dist = Math.hypot(e.getSceneX() - c.getX(), e.getSceneY() - c.getY());
                if (dist < bestDist) {
                    bestDist = dist;
                    best = d;
                }
            }
            if (best != null && bestDist < 80) {
                tip.setText(best.getXValue() + "  " + unit + " ¥"
                        + String.format("%.2f", best.getYValue().doubleValue()));
                tip.show(monthTrend, e.getScreenX(), e.getScreenY() + 14);
            } else {
                tip.hide();
            }
        });
        monthTrend.setOnMouseExited(e -> tip.hide());
    }

    /** 下拉框：支出（全部）/ 收入（全部）/ 各支出分类。只在初始化时填一次。 */
    private void initMonthTrendScope() {
        monthTrendCategories = db.listCategories("EXPENSE");
        List<String> items = new ArrayList<>();
        items.add("支出（全部）");
        items.add("收入（全部）");
        for (Category c : monthTrendCategories) {
            items.add((c.icon == null || c.icon.isBlank() ? "" : c.icon + " ") + c.name);
        }
        monthTrendScope.getItems().setAll(items);
        monthTrendScope.getSelectionModel().select(0);
        monthTrendScope.setOnAction(e -> {
            applyMonthTrendScope(monthTrendScope.getSelectionModel().getSelectedIndex());
            // 柱子/口径换了，之前选中的月份不再对应当前视图：饼图回到本月
            pieMonth = null;
            LocalDate today = LocalDate.now();
            renderMonthTrend(today);
            renderPie(today, db.listTransactions(today.withDayOfMonth(1),
                    today.withDayOfMonth(today.lengthOfMonth()), null, null));
        });
    }

    /**
     * 给趋势图的柱子挂上点击：点某个月 → 上面的饼图切到那个月；再点同一根 → 回到本月。
     *
     * <p>柱子的节点由 JavaFX 在布局时创建，所以挂监听放到 {@code Platform.runLater} 里。
     * 点完清掉选中态（{@code pieMonth}）后要重建一次趋势图，被选中的柱子才能立刻换色。</p>
     */
    private void applyTrendSelection(XYChart.Series<String, Number> series, Map<String, LocalDate> monthByLabel) {
        Platform.runLater(() -> {
            for (XYChart.Data<String, Number> d : series.getData()) {
                Node node = d.getNode();
                if (node == null) continue;
                // 重建过程中 JavaFX 会先把 Y 置成 -Infinity，这类临时节点不挂事件
                if (d.getYValue() != null && d.getYValue().doubleValue() == Double.NEGATIVE_INFINITY) continue;
                node.setCursor(Cursor.HAND);
                node.setOnMouseClicked(e -> selectTrendMonth(
                        monthByLabel.get(String.valueOf(d.getXValue()))));
            }
        });
    }

    /** 切换饼图月份；传 null 或本月表示回到本月。 */
    private void selectTrendMonth(LocalDate clickedMonth) {
        if (clickedMonth == null) return;
        LocalDate month = clickedMonth.withDayOfMonth(1);
        LocalDate today = LocalDate.now().withDayOfMonth(1);
        boolean sameAsCurrent = month.getYear() == today.getYear() && month.getMonthValue() == today.getMonthValue();
        // 点本月 = 取消选择；点已选中的历史月份也回到本月
        pieMonth = (sameAsCurrent || month.equals(pieMonth)) ? null : month;

        LocalDate now = LocalDate.now();
        renderMonthTrend(now);
        renderPie(now, db.listTransactions(now.withDayOfMonth(1),
                now.withDayOfMonth(now.lengthOfMonth()), null, null));
        pieEmpty.setVisible(pie.getData().isEmpty());
    }

    /** 把下拉框的选中下标换算成「看收入还是支出 + 限定哪个分类」。 */
    private void applyMonthTrendScope(int index) {
        if (index == 1) {
            monthTrendIncome = true;
            monthTrendCategoryId = null;
        } else if (index >= 2 && index - 2 < monthTrendCategories.size()) {
            monthTrendIncome = false;
            monthTrendCategoryId = monthTrendCategories.get(index - 2).clientId;
        } else {
            monthTrendIncome = false;
            monthTrendCategoryId = null;
        }
    }

    /** 与 H2 里 FORMATDATETIME(date,'yyyy-MM') 对齐：2026-09。 */
    private static String monthKey(LocalDate d) {
        return String.format("%04d-%02d", d.getYear(), d.getMonthValue());
    }

    private void renderBudget(LocalDate now) {
        budgetBox.getChildren().clear();
        Map<String, BigDecimal> used = categoryTotals(db.listTransactions(
                now.withDayOfMonth(1), now.withDayOfMonth(now.lengthOfMonth()), null, null));
        for (Budget b : db.listBudgets(now.getYear(), now.getMonthValue())) {
            if (b.amountLimit == null || b.amountLimit.compareTo(BigDecimal.ZERO) <= 0) continue;
            BigDecimal u = used.getOrDefault(b.categoryClientId, BigDecimal.ZERO);
            double pct = u.multiply(BigDecimal.valueOf(100))
                    .divide(b.amountLimit, 0, java.math.RoundingMode.HALF_UP).doubleValue();
            Label label = new Label(String.format("%s  %.2f / %.2f  (%.0f%%)",
                    db.categoryName(b.categoryClientId), u, b.amountLimit, pct));
            ProgressBar bar = new ProgressBar(Math.min(pct / 100, 1.0));
            bar.setPrefWidth(420);
            HBox row = new HBox(10, label, bar);
            budgetBox.getChildren().add(row);
        }
    }

    private void renderHeatmap(int year) {
        heatmap.getChildren().clear();
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);
        Map<LocalDate, BigDecimal> perDay = db.listTransactions(start, end, null, null).stream()
                .filter(t -> "EXPENSE".equals(t.type))
                .collect(Collectors.groupingBy(t -> t.date,
                        Collectors.reducing(BigDecimal.ZERO, t -> t.amount == null ? BigDecimal.ZERO : t.amount, BigDecimal::add)));
        heatHasData = !perDay.isEmpty();
        double max = perDay.values().stream().mapToDouble(BigDecimal::doubleValue).max().orElse(1);
        int col = 0, row = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            double amt = perDay.getOrDefault(d, BigDecimal.ZERO).doubleValue();
            int intensity = (int) Math.min(255, amt / max * 255);
            Color c = amt <= 0 ? COLOR_HAIRLINE : Color.WHITE.interpolate(COLOR_PRIMARY, intensity / 255.0);
            Rectangle rect = new Rectangle(10, 10, c);
            heatmap.add(rect, col, row);
            col++;
            if (col == 7) {
                col = 0;
                row++;
            }
        }
    }

    private Map<String, BigDecimal> categoryTotals(List<Transaction> txns) {
        return ReportMath.expenseByCategory(txns);
    }

    private BigDecimal sum(List<Transaction> txns, String type) {
        return txns.stream().filter(t -> type.equals(t.type))
                .map(t -> t.amount == null ? BigDecimal.ZERO : t.amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
