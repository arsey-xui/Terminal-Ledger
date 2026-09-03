package com.personal.accounting.desktop.ui;

import com.personal.accounting.desktop.data.DataManager;
import com.personal.accounting.desktop.model.Account;
import com.personal.accounting.desktop.model.Budget;
import com.personal.accounting.desktop.model.Category;
import com.personal.accounting.desktop.service.BackupService;
import com.personal.accounting.desktop.service.AiReportGenerator;
import com.personal.accounting.desktop.sync.SyncConfig;
import com.personal.accounting.desktop.sync.SyncManager;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.time.LocalDate;
import java.util.UUID;

/** 主控制器：左侧导航切换中心内容。 */
public class MainController {

    @FXML private StackPane content;
    @FXML private ToggleButton navDashboard, navRecord, navAccount, navBudget, navData;
    @FXML private Label headerTitle, headerStatus;

    private final ToggleGroup group = new ToggleGroup();
    private final DataManager db = AppState.db();

    @FXML
    public void initialize() {
        SyncConfig.loadBaseUrl();
        for (ToggleButton b : new ToggleButton[]{navDashboard, navRecord, navAccount, navBudget, navData}) {
            b.setToggleGroup(group);
        }
        navDashboard.setSelected(true);
        headerStatus.setText("● 离线优先 · 本地模式");
        showDashboard();
    }

    @FXML
    public void onDashboard() {
        headerTitle.setText("仪表盘");
        showDashboard();
    }

    private void showDashboard() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/dashboard.fxml"));
            content.getChildren().setAll(loader.<Node>load());
        } catch (Exception e) {
            content.getChildren().setAll(label(e.getMessage()));
        }
    }

    @FXML
    public void onRecord() {
        headerTitle.setText("记账入口");
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/record.fxml"));
            content.getChildren().setAll(loader.<Node>load());
        } catch (Exception e) {
            content.getChildren().setAll(label(e.getMessage()));
        }
    }

    @FXML
    public void onAccount() {
        headerTitle.setText("账户管理");
        content.getChildren().setAll(buildAccount());
    }

    @FXML
    public void onBudget() {
        headerTitle.setText("预算管理");
        content.getChildren().setAll(buildBudget());
    }

    @FXML
    public void onData() {
        headerTitle.setText("数据管理");
        content.getChildren().setAll(buildData());
    }

    // ---------- 账户管理 ----------
    private Node buildAccount() {
        VBox root = new VBox(12);
        root.getStyleClass().add("area");
        root.getChildren().add(label0("账户管理", "h1"));

        ListView<Account> list = new ListView<>();
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Account a, boolean empty) {
                super.updateItem(a, empty);
                setText(empty || a == null ? null : a.name + " (" + a.type + ")");
            }
        });
        Runnable refresh = () -> list.getItems().setAll(db.listAccounts());

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        TextField name = new TextField();
        name.setPromptText("账户名");
        TextField type = new TextField();
        type.setPromptText("类型: CASH/DEBIT_CARD/...");
        Button add = new Button("新增");
        add.setOnAction(e -> {
            Account a = new Account();
            a.clientId = UUID.randomUUID().toString();
            a.name = name.getText();
            a.type = type.getText();
            a.lastModified = System.currentTimeMillis();
            db.upsertAccount(a);
            refresh.run();
            name.clear();
            type.clear();
        });
        Button del = new Button("删除");
        del.setOnAction(e -> {
            Account selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) {
                info("请先在列表中选中要删除的账户");
                return;
            }
            db.deleteAccount(selected.clientId);
            refresh.run();
            info("已删除账户：" + selected.name);
        });
        refresh.run();
        form.add(name, 0, 0);
        form.add(type, 1, 0);
        form.add(add, 2, 0);
        form.add(del, 3, 0);
        root.getChildren().addAll(list, form);
        return root;
    }

    // ---------- 预算管理 ----------
    private Node buildBudget() {
        VBox root = new VBox(12);
        root.getStyleClass().add("area");
        root.getChildren().add(label0("预算管理", "h1"));
        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        ComboBox<String> catBox = new ComboBox<>();
        catBox.getItems().addAll(db.listCategories("EXPENSE").stream().map(c -> c.name).toList());
        TextField amount = new TextField();
        amount.setPromptText("月度上限");
        Button save = new Button("设置");
        save.setOnAction(e -> {
            String catName = catBox.getValue();
            if (catName == null) return;
            Category c = db.listCategories("EXPENSE").stream().filter(x -> x.name.equals(catName)).findFirst().orElse(null);
            if (c == null) return;
            LocalDate now = LocalDate.now();
            Budget b = db.findBudget(c.clientId, now.getYear(), now.getMonthValue());
            if (b == null) {
                b = new Budget();
                b.clientId = UUID.randomUUID().toString();
                b.categoryClientId = c.clientId;
                b.year = now.getYear();
                b.month = now.getMonthValue();
            }
            b.amountLimit = new java.math.BigDecimal(amount.getText());
            b.lastModified = System.currentTimeMillis();
            db.upsertBudget(b);
            amount.clear();
            Alert a = new Alert(Alert.AlertType.INFORMATION, "预算已保存");
            a.show();
        });
        form.add(catBox, 0, 0);
        form.add(amount, 1, 0);
        form.add(save, 2, 0);
        root.getChildren().add(form);
        return root;
    }

    // ---------- 数据管理 ----------
    private Node buildData() {
        VBox root = new VBox(12);
        root.getStyleClass().add("area");
        root.getChildren().add(label0("数据管理", "h1"));

        // 设置后端地址（空默认 + 必填 + 自动补 http:// + 保存）
        TextField urlField = new TextField(SyncConfig.BASE_URL);
        urlField.setPromptText("后端地址，如 http://localhost:8080 或 http://192.168.1.100:8080");
        urlField.setPrefWidth(420);
        Button saveUrl = new Button("保存后端地址");
        saveUrl.setOnAction(e -> {
            String url = urlField.getText().trim();
            if (url.isEmpty()) {
                info("请先填写后端地址，例如 http://localhost:8080");
                return;
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://" + url;
            try {
                SyncConfig.saveBaseUrl(url);
                info("已保存后端地址：" + url);
            } catch (Exception ex) {
                info("保存失败: " + ex.getMessage());
            }
        });
        HBox urlRow = new HBox(10, urlField, saveUrl);
        root.getChildren().add(urlRow);

        Button sync = new Button("立即同步");
        sync.setOnAction(e -> {
            try {
                Alert a = new Alert(Alert.AlertType.INFORMATION, new SyncManager(db).sync());
                a.show();
            } catch (Exception ex) {
                Alert a = new Alert(Alert.AlertType.INFORMATION,
                        "暂时连不上云端（后端未启动时属正常），已保持本地离线使用。\n" + ex.getMessage());
                a.show();
            }
        });

        Button csv = new Button("导出 CSV");
        csv.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
            File f = fc.showSaveDialog(null);
            if (f != null) {
                try {
                    BackupService.exportCsv(f.toPath(), db.listTransactions(null, null, null, null));
                    info("已导出 " + f.getAbsolutePath());
                } catch (Exception ex) {
                    info("导出失败: " + ex.getMessage());
                }
            }
        });

        Button backup = new Button("导出加密备份包");
        backup.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP", "*.zip"));
            File f = fc.showSaveDialog(null);
            if (f != null) backupPack(f, false);
        });

        Button restore = new Button("从备份包恢复（覆盖）");
        restore.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP", "*.zip"));
            File f = fc.showOpenDialog(null);
            if (f != null) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        "恢复将覆盖当前全部数据，确定继续？", ButtonType.OK, ButtonType.CANCEL);
                if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                    restorePack(f);
                }
            }
        });

        Button ai = new Button("生成 AI 消费分析");
        ai.setOnAction(e -> {
            LocalDate now = LocalDate.now();
            String report = new AiReportGenerator(db).generate(now.getYear(), now.getMonthValue());
            Alert a = new Alert(Alert.AlertType.INFORMATION, report);
            a.setHeaderText("AI 消费分析");
            a.show();
        });

        root.getChildren().addAll(sync, csv, backup, restore, ai);
        return root;
    }

    private void backupPack(File f, boolean xx) {
        PasswordDialog dlg = new PasswordDialog();
        String pass = dlg.showAndWait().orElse(null);
        if (pass == null || pass.isEmpty()) {
            info("已取消");
            return;
        }
        try {
            BackupService.createBackup(f.toPath(), db, pass.toCharArray());
            info("已备份到 " + f.getAbsolutePath());
        } catch (Exception ex) {
            info("备份失败: " + ex.getMessage());
        }
    }

    private void restorePack(File f) {
        PasswordDialog dlg = new PasswordDialog();
        String pass = dlg.showAndWait().orElse(null);
        if (pass == null || pass.isEmpty()) {
            info("已取消");
            return;
        }
        try {
            BackupService.restoreBackup(f.toPath(), db, pass.toCharArray());
            info("恢复完成");
        } catch (Exception ex) {
            info("恢复失败: " + ex.getMessage());
        }
    }

    private Label label(String s) {
        return new Label(s);
    }

    private Label label0(String s, String cls) {
        Label l = new Label(s);
        l.getStyleClass().add(cls);
        return l;
    }

    private void info(String msg) {
        new Alert(Alert.AlertType.INFORMATION, msg).show();
    }
}
