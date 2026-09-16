package com.personal.accounting.desktop.ui;

import com.personal.accounting.desktop.data.DataManager;
import com.personal.accounting.desktop.model.Budget;
import com.personal.accounting.desktop.model.Category;
import com.personal.accounting.desktop.model.Transaction;
import com.personal.accounting.desktop.service.AiConfig;
import com.personal.accounting.desktop.service.AiReportGenerator;
import com.personal.accounting.desktop.sync.SyncManager;
import javafx.fxml.FXML;
import javafx.scene.Node;
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
    @FXML private VBox budgetBox;
    @FXML private GridPane heatmap;
    @FXML private TextArea aiReport;
    @FXML private Label pieEmpty, lineEmpty, budgetEmpty, heatEmpty;

    private boolean lineHasData, heatHasData;

    private final DataManager db = AppState.db();

    @FXML
    public void initialize() {
        pie.setTitle("支出构成");
        line.setTitle("近30天每日支出");
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
        labelMom.setTextFill(mom >= 0 ? Color.web("#D64545") : Color.web("#2E9E24"));

        renderPie(cur);
        renderLine(now);
        renderBudget(now);
        renderHeatmap(now.getYear());
        aiReport.setText(new AiReportGenerator(db).generate(now.getYear(), now.getMonthValue()));
        pieEmpty.setVisible(pie.getData().isEmpty());
        lineEmpty.setVisible(!lineHasData);
        budgetEmpty.setVisible(budgetBox.getChildren().isEmpty());
        heatEmpty.setVisible(!heatHasData);
    }

    private void renderPie(List<Transaction> cur) {
        List<PieChart.Data> data = categoryTotals(cur).entrySet().stream()
                .map(e -> new PieChart.Data(categoryName(e.getKey()), e.getValue().doubleValue()))
                .toList();
        pie.getData().setAll(data);
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
                    categoryName(b.categoryClientId), u, b.amountLimit, pct));
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
            Color c = amt <= 0 ? Color.web("#EDEDED") : Color.WHITE.interpolate(Color.web("#C0FF00"), intensity / 255.0);
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
        return txns.stream().filter(t -> "EXPENSE".equals(t.type) && t.categoryClientId != null)
                .collect(Collectors.groupingBy(t -> t.categoryClientId,
                        Collectors.reducing(BigDecimal.ZERO, t -> t.amount == null ? BigDecimal.ZERO : t.amount, BigDecimal::add)));
    }

    private BigDecimal sum(List<Transaction> txns, String type) {
        return txns.stream().filter(t -> type.equals(t.type))
                .map(t -> t.amount == null ? BigDecimal.ZERO : t.amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String categoryName(String id) {
        Category c = db.findCategory(id);
        return c == null ? "未分类" : c.name;
    }
}
