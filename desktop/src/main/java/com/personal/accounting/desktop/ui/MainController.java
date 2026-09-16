package com.personal.accounting.desktop.ui;

import com.personal.accounting.desktop.data.DataManager;
import com.personal.accounting.desktop.model.Account;
import com.personal.accounting.desktop.model.Budget;
import com.personal.accounting.desktop.model.Category;
import com.personal.accounting.desktop.service.BackupService;
import com.personal.accounting.desktop.service.AiReportGenerator;
import com.personal.accounting.desktop.service.UpdateCheck;
import com.personal.accounting.desktop.service.UpdateConfig;
import com.personal.accounting.desktop.sync.SyncConfig;
import com.personal.accounting.desktop.sync.SyncManager;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.application.Platform;
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

        // 已设置的预算列表：保存/重置后立刻能看到结果（否则无法判断有没有生效）
        ListView<String> budgetList = new ListView<>();
        budgetList.setPrefHeight(200);
        Runnable refreshList = () -> {
            LocalDate month = LocalDate.now();
            budgetList.getItems().setAll(db.listBudgets(month.getYear(), month.getMonthValue()).stream()
                    .map(b -> String.format("%s：上限 %.2f 元",
                            categoryName(b.categoryClientId),
                            b.amountLimit == null ? 0.0 : b.amountLimit.doubleValue()))
                    .toList());
        };

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        ComboBox<String> catBox = new ComboBox<>();
        catBox.getItems().addAll(db.listCategories("EXPENSE").stream().map(c -> c.name).toList());
        TextField amount = new TextField();
        amount.setPromptText("月度上限");
        Button save = new Button("设置");
        Label hint = new Label("设置后可在下方列表确认；「数据管理 → 重置预算」会清空这里");
        hint.getStyleClass().add("muted");
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
            try {
                b.amountLimit = new java.math.BigDecimal(amount.getText().trim());
            } catch (NumberFormatException ex) {
                info("金额格式不正确");
                return;
            }
            // findBudget 会返回已软删除的墓碑行（例如「重置预算」之后），
            // 这里必须把 deleted 置回 0，否则保存成功但按 deleted=0 的查询永远看不到
            b.deleted = false;
            b.lastModified = System.currentTimeMillis();
            db.upsertBudget(b);
            refreshList.run();
            amount.clear();
            Alert a = new Alert(Alert.AlertType.INFORMATION, "预算已保存");
            a.show();
        });
        form.add(catBox, 0, 0);
        form.add(amount, 1, 0);
        form.add(save, 2, 0);
        refreshList.run();
        root.getChildren().addAll(form, hint, budgetList);
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

        // AI 设置入口：首次向导之外，随时可以改 API Key / 切回规则引擎
        Button aiSettings = new Button("AI 设置");
        aiSettings.setOnAction(e -> AiSettingsDialog.show(null));
        HBox aiRow = new HBox(10, ai, aiSettings);

        // ---------- 重置为初次安装状态 ----------
        Button resetCat = new Button("重置分类");
        resetCat.setOnAction(e -> confirmReset("分类",
                "会删除你自建的全部分类，并恢复内置默认分类（餐饮、交通…工资、红包）。\n"
                        + "流水记录不会被删除，但这些流水会显示为「未分类」。\n\n"
                        + "此操作影响本地数据，并会在下次同步时同步到云端。",
                () -> db.resetCategories()));

        Button resetAcc = new Button("重置账户");
        resetAcc.setOnAction(e -> confirmReset("账户",
                "会删除你自建的全部账户，并恢复内置默认账户（现金、储蓄卡、信用卡、微信、支付宝）。\n"
                        + "流水记录不会被删除。\n\n"
                        + "此操作影响本地数据，并会在下次同步时同步到云端。",
                () -> db.resetAccounts()));

        Button resetBudget = new Button("重置预算（清空所有上限）");
        resetBudget.setOnAction(e -> confirmReset("预算",
                "会清空所有分类的预算上限，回到「未设置上限」的状态。\n"
                        + "流水记录不会被删除。\n\n"
                        + "此操作影响本地数据，并会在下次同步时同步到云端。",
                () -> db.resetBudgets()));

        HBox resetRow = new HBox(10, resetCat, resetAcc, resetBudget);

        // ---------- 检查更新 ----------
        Label versionLabel = new Label("当前版本：" + UpdateCheck.currentVersion());
        Button checkUpdate = new Button("检查更新");
        Button updateSource = new Button("更新源");
        updateSource.setOnAction(e -> configureUpdateSource());
        checkUpdate.setOnAction(e -> {
            checkUpdate.setDisable(true);
            new Thread(() -> {
                // 直接查 GitHub Releases（不需要后端在线）
                UpdateCheck.Result result = UpdateCheck.refresh();
                Platform.runLater(() -> {
                    checkUpdate.setDisable(false);
                    showUpdateResult(result);
                });
            }).start();
        });
        HBox updateRow = new HBox(10, versionLabel, checkUpdate, updateSource);

        root.getChildren().addAll(sync, csv, backup, restore, aiRow, resetRow, updateRow);
        return root;
    }

    /** 展示检查更新结果：有新版本时给出下载入口（桌面端无法自动覆盖安装）。 */
    private void showUpdateResult(UpdateCheck.Result result) {
        if (!result.hasInfo()) {
            info(result.message);
            return;
        }
        if (!UpdateCheck.isNewer(result.info.latestVersion, UpdateCheck.currentVersion())) {
            info("已是最新版本（" + UpdateCheck.currentVersion() + "）");
            return;
        }
        UpdateCheck.Info info = result.info;
        StringBuilder text = new StringBuilder();
        text.append("当前版本：").append(UpdateCheck.currentVersion()).append('\n');
        text.append("最新版本：").append(info.latestVersion).append('\n');
        if (info.notes != null && !info.notes.trim().isEmpty()) {
            text.append('\n').append(info.notes.trim());
        }
        text.append("\n\n桌面端是免安装目录，无法自动覆盖安装：请下载后手动替换。");
        text.append(info.hasDownloadUrl()
                ? "\n点「打开下载页」会在浏览器打开下载地址。"
                : "\n管理员尚未配置下载地址，请从发布渠道获取。");
        Alert alert = new Alert(Alert.AlertType.INFORMATION, text.toString());
        alert.setTitle("发现新版本");
        alert.setHeaderText(null);
        if (info.hasDownloadUrl()) {
            alert.getButtonTypes().setAll(
                    new ButtonType("打开下载页", ButtonBar.ButtonData.OK_DONE),
                    new ButtonType("稍后", ButtonBar.ButtonData.CANCEL_CLOSE));
        }
        alert.showAndWait().ifPresent(bt -> {
            if (info.hasDownloadUrl() && "打开下载页".equals(bt.getText())) openInBrowser(info.downloadUrl);
        });
    }

    /** 配置更新源（GitHub 仓库 owner/repo）。 */
    private void configureUpdateSource() {
        TextField input = new TextField(UpdateConfig.repo());
        input.setPrefWidth(360);
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("GitHub 仓库"), input);
        grid.addRow(1, new Label("格式：owner/repo，例：arsey-xui/Terminal-Ledger"), new Label(""));
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("更新源（GitHub 仓库）");
        dlg.setHeaderText("检查更新直接读取 GitHub Releases，不需要后端在线。\n"
                + "发版时在仓库创建 Release，正文写更新说明，并上传产物作为资产。");
        dlg.getDialogPane().setContent(grid);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (dlg.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        try {
            UpdateConfig.saveRepo(input.getText());
            info("已保存更新源：" + UpdateConfig.repo());
        } catch (Exception e) {
            info("保存失败：" + e.getMessage());
        }
    }

    /** 用系统默认浏览器打开链接；失败则退化为复制到剪贴板。 */
    private void openInBrowser(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
                return;
            }
        } catch (Exception e) {
            // 落到下面的剪贴板兜底
        }
        javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(url);
        clipboard.setContent(content);
        info("无法自动打开浏览器，下载地址已复制到剪贴板：\n" + url);
    }

    /** 重置前二次确认；确认后执行 action、后台同步一次，并刷新界面。 */
    private void confirmReset(String what, String message, ResetAction action) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("重置" + what + "？");
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        try {
            action.run();
        } catch (Exception ex) {
            info("重置失败：" + ex.getMessage());
            return;
        }
        new Thread(() -> {
            try {
                new SyncManager(db).sync();
            } catch (Exception ignore) {
                // 离线时静默失败，下次同步会补上
            }
        }).start();
        info("已重置" + what + "为初次安装状态");
        refreshCurrentArea();
    }

    /** 重置动作（DataManager 的方法会抛异常，用这个函数式接口包一下）。 */
    private interface ResetAction {
        void run() throws Exception;
    }

    /** 重建当前显示的区域，让改动立刻可见。 */
    private void refreshCurrentArea() {
        Toggle selected = group.getSelectedToggle();
        if (selected == navDashboard) onDashboard();
        else if (selected == navRecord) onRecord();
        else if (selected == navAccount) onAccount();
        else if (selected == navBudget) onBudget();
        else onData();
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

    /** 分类名（找不到时显示"未分类"），预算列表等处使用。 */
    private String categoryName(String clientId) {
        if (clientId == null) return "未分类";
        Category c = db.findCategory(clientId);
        return c == null ? "未分类" : c.name;
    }
}
