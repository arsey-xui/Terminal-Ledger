package com.personal.accounting.desktop.ui;

import com.personal.accounting.desktop.data.DataManager;
import com.personal.accounting.desktop.model.Budget;
import com.personal.accounting.desktop.model.Category;
import com.personal.accounting.desktop.model.Transaction;
import com.personal.accounting.desktop.service.AiConfig;
import com.personal.accounting.desktop.service.AiReportGenerator;
import com.personal.accounting.desktop.sync.SyncManager;
import javafx.application.Platform;
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
        line.setTitle("近30天净支出");
        AiConfig.load();
        if (AiConfig.isSet()) {
            refresh();
        } else {
            showAiSetup();   // 首次：询问是否已有 API Key（内部完成后 refresh）
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

    /** 首次使用 AI 分析：询问是否已有自己的 API Key；选择并保存后不再提示。 */
    private void showAiSetup() {
        Dialog<Boolean> dlg = new Dialog<>();
        dlg.setTitle("AI 消费分析");
        dlg.setHeaderText("AI 分析默认使用内置规则引擎。\n是否已有自己的 API Key？可填入 Key 启用大模型分析。");

        TextField keyField = new TextField();
        keyField.setPromptText("API Key（如 sk-...）");
        TextField baseField = new TextField();
        baseField.setText("https://api.openai.com/v1");
        baseField.setPromptText("接口地址");
        TextField modelField = new TextField();
        modelField.setText("gpt-4o-mini");
        modelField.setPromptText("模型");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("API Key"), keyField);
        grid.addRow(1, new Label("接口地址"), baseField);
        grid.addRow(2, new Label("模型"), modelField);
        dlg.getDialogPane().setContent(grid);

        ButtonType btLlm = new ButtonType("使用大模型", ButtonBar.ButtonData.OK_DONE);
        ButtonType btRule = new ButtonType("用默认规则引擎", ButtonBar.ButtonData.CANCEL_CLOSE);
        dlg.getDialogPane().getButtonTypes().addAll(btLlm, btRule);
        dlg.setResultConverter(bt -> bt == btLlm ? Boolean.TRUE : Boolean.FALSE);

        Boolean useLlm = dlg.showAndWait().orElse(false);
        try {
            if (Boolean.TRUE.equals(useLlm)) {
                String key = keyField.getText().trim();
                if (key.isEmpty()) {
                    AiConfig.save(false, "", "", "");
                    info("未填写 API Key，已使用默认规则引擎");
                } else {
                    AiConfig.save(true, key, baseField.getText().trim(), modelField.getText().trim());
                    info("已启用大模型分析（Key 已保存）");
                }
            } else {
                AiConfig.save(false, "", "", "");
                info("已使用默认规则引擎");
            }
        } catch (Exception e) {
            info("AI 设置保存失败: " + e.getMessage());
        }
        refresh();
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
        // 净支出 = 支出 - 收入，逐日汇总
        List<Transaction> txns = db.listTransactions(s, now, null, null);
        Map<LocalDate, BigDecimal> perDayExp = new HashMap<>();
        Map<LocalDate, BigDecimal> perDayInc = new HashMap<>();
        for (Transaction t : txns) {
            if (t.date == null) continue;
            BigDecimal amt = t.amount == null ? BigDecimal.ZERO : t.amount;
            if ("EXPENSE".equals(t.type)) perDayExp.merge(t.date, amt, BigDecimal::add);
            else if ("INCOME".equals(t.type)) perDayInc.merge(t.date, amt, BigDecimal::add);
        }
        lineHasData = !txns.isEmpty();
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        List<Double> netPerDay = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate d = s.plusDays(i);
            double net = perDayExp.getOrDefault(d, BigDecimal.ZERO)
                    .subtract(perDayInc.getOrDefault(d, BigDecimal.ZERO)).doubleValue();
            netPerDay.add(net);
            series.getData().add(new XYChart.Data<>(i, net));
        }
        line.getData().setAll(series);
        // 启用数据点符号，使每个点可悬停/点击查看数值
        line.setCreateSymbols(true);

        // X 轴：索引映射成日期；Y 轴：净支出金额
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
        yAxis.setLabel("净支出(元)");
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

        // 悬停/点击数据点：显示具体值（日期 + 净支出金额）
        final DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern("M.d");
        Platform.runLater(() -> {
            for (XYChart.Data<Number, Number> d : series.getData()) {
                Node node = d.getNode();
                if (node != null) {
                    int idx = d.getXValue().intValue();
                    LocalDate day = s.plusDays(idx);
                    Tooltip.install(node, new Tooltip(
                            day.format(dayFmt) + "  ¥" + String.format("%.2f", netPerDay.get(idx))));
                }
            }
        });
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
