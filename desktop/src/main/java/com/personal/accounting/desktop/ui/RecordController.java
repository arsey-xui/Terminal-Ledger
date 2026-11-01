package com.personal.accounting.desktop.ui;

import com.personal.accounting.desktop.data.DataManager;
import com.personal.accounting.desktop.model.Account;
import com.personal.accounting.desktop.model.Category;
import com.personal.accounting.desktop.model.Transaction;
import com.personal.accounting.desktop.sync.SyncManager;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class RecordController {

    @FXML private DatePicker datePicker;
    @FXML private TextField amountField, noteField;
    @FXML private ComboBox<String> categoryBox, accountBox;
    @FXML private RadioButton rbExpense, rbIncome;
    @FXML private TableView<com.personal.accounting.desktop.model.Transaction> table;
    @FXML private TableColumn<com.personal.accounting.desktop.model.Transaction, LocalDate> colDate;
    @FXML private TableColumn<com.personal.accounting.desktop.model.Transaction, BigDecimal> colAmount;
    @FXML private TableColumn<com.personal.accounting.desktop.model.Transaction, String> colCategory;
    @FXML private TableColumn<com.personal.accounting.desktop.model.Transaction, String> colNote;
    @FXML private VBox firstUseCard;

    private final DataManager db = AppState.db();

    /** 非 null 表示表单正在编辑这条流水：保存时沿用同一个 clientId（覆盖原记录）。 */
    private com.personal.accounting.desktop.model.Transaction editing;

    @FXML
    public void initialize() {
        datePicker.setValue(LocalDate.now());
        ToggleGroup type = new ToggleGroup();
        rbExpense.setToggleGroup(type);
        rbIncome.setToggleGroup(type);
        rbExpense.setSelected(true);
        rbExpense.setOnAction(e -> loadCategories(false));
        rbIncome.setOnAction(e -> loadCategories(true));

        loadCategories(false);
        accountBox.getItems().addAll(db.listAccounts().stream().map(a -> a.name).toList());

        colDate.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().date));
        colAmount.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().amount));
        colCategory.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(db.categoryName(d.getValue().categoryClientId)));
        colNote.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().note));

        // 双击一行 = 编辑这一条（单击只做选中，避免和滚动、列选择打架）
        table.setRowFactory(tv -> {
            javafx.scene.control.TableRow<com.personal.accounting.desktop.model.Transaction> row =
                    new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) startEditing(row.getItem());
            });
            return row;
        });
        loadTransactions();
    }

    private void loadCategories(boolean income) {
        categoryBox.getItems().clear();
        categoryBox.getItems().addAll(db.listCategories(income ? "INCOME" : "EXPENSE")
                .stream().map(c -> c.name).toList());
        categoryBox.getSelectionModel().selectFirst();
    }

    /** 新建分类：名称(必填) + emoji(可选)，类型跟随当前“支出/收入”切换。 */
    @FXML
    public void onAddCategory() {
        TextField nameField = new TextField();
        nameField.setPromptText("分类名称，如：宠物");
        TextField iconField = new TextField();
        iconField.setPromptText("图标 emoji（可留空），如：🐱");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("名称"), nameField);
        grid.addRow(1, new Label("图标"), iconField);

        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("新建分类");
        dlg.setHeaderText(rbIncome.isSelected() ? "新增收入分类" : "新增支出分类");
        dlg.getDialogPane().setContent(grid);
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dlg.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.OK) return;
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            if (name.isEmpty()) {
                warn("请输入分类名称");
                return;
            }
            Category c = new Category();
            c.clientId = UUID.randomUUID().toString();
            c.name = name;
            c.icon = iconField.getText() == null ? "" : iconField.getText().trim();
            c.type = rbIncome.isSelected() ? "INCOME" : "EXPENSE";
            c.lastModified = System.currentTimeMillis();
            c.deleted = false;
            db.upsertCategory(c);
            loadCategories(rbIncome.isSelected());
            if (categoryBox.getItems().contains(name)) categoryBox.getSelectionModel().select(name);
            info("已新增分类：" + name);
        });
    }

    /**
     * 删除分类（软删除，可同步）。分类下的历史流水**不会被删除**，
     * 只是这些流水会显示为"未分类"；删除前会提示影响条数。
     */
    @FXML
    public void onDeleteCategory() {
        boolean income = rbIncome.isSelected();
        var cats = db.listCategories(income ? "INCOME" : "EXPENSE");
        if (cats.isEmpty()) {
            warn(income ? "还没有收入分类可删除" : "还没有支出分类可删除");
            return;
        }
        String[] names = cats.stream()
                .map(c -> (c.icon == null || c.icon.isBlank() ? "" : c.icon + " ") + c.name)
                .toArray(String[]::new);

        ChoiceDialog<String> pick = new ChoiceDialog<>(names[0], names);
        pick.setTitle("删除分类");
        pick.setHeaderText(income ? "选择要删除的收入分类" : "选择要删除的支出分类");
        pick.setContentText("分类");
        var chosen = pick.showAndWait();
        if (chosen.isEmpty()) return;

        int index = 0;
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(chosen.get())) { index = i; break; }
        }
        Category target = cats.get(index);
        int affected = db.countTransactionsByCategory(target.clientId);

        String message = "确定删除分类「" + target.name + "」？\n"
                + (affected > 0
                ? "该分类下有 " + affected + " 条流水，这些流水会保留、但显示为「未分类」。"
                : "该分类下暂无流水。");
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("删除确认");
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        db.deleteCategory(target.clientId);
        loadCategories(income);
        loadTransactions();
        MainController.SYNC_EXECUTOR.submit(() -> {
            try {
                new SyncManager(db).sync();
            } catch (Exception ignore) {
                // 离线时静默失败，下次同步会补上
            }
        });
        info("已删除分类：" + target.name + (affected > 0 ? "（" + affected + " 条流水保留，显示为未分类）" : ""));
    }

    @FXML
    public void onSave() {
        if (amountField.getText() == null || amountField.getText().isBlank()) {
            warn("请输入金额");
            return;
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(amountField.getText());
        } catch (NumberFormatException e) {
            warn("金额格式错误");
            return;
        }

        String catName = categoryBox.getValue();
        String accName = accountBox.getValue();
        String type = rbIncome.isSelected() ? "INCOME" : "EXPENSE";
        Category cat = db.listCategories(type).stream().filter(c -> c.name.equals(catName)).findFirst().orElse(null);
        Account acc = db.listAccounts().stream().filter(a -> a.name.equals(accName)).findFirst().orElse(null);

        // 编辑时直接复用原对象：clientId / createdAt 保持不变，只改表单里的字段
        boolean wasEditing = editing != null;
        Transaction t = wasEditing ? editing : new Transaction();
        if (!wasEditing) t.clientId = UUID.randomUUID().toString();
        t.date = datePicker.getValue() == null ? LocalDate.now() : datePicker.getValue();
        t.amount = amount;
        t.type = rbIncome.isSelected() ? "INCOME" : "EXPENSE";
        t.categoryClientId = cat == null ? null : cat.clientId;
        t.accountClientId = acc == null ? null : acc.clientId;
        t.note = noteField.getText();
        if (!wasEditing) t.source = "manual";
        t.lastModified = System.currentTimeMillis();
        t.deleted = false;
        db.upsertTransaction(t);

        MainController.SYNC_EXECUTOR.submit(() -> {
            try {
                new SyncManager(db).sync();
            } catch (Exception ignore) {
            }
        });

        amountField.clear();
        noteField.clear();
        loadTransactions();
        editing = null;
        new Alert(Alert.AlertType.INFORMATION, wasEditing ? "已保存修改" : "已记账").show();
    }

    /** 把选中的流水填回表单。保存时沿用同一个 clientId，所以是覆盖而不是新增。 */
    private void startEditing(com.personal.accounting.desktop.model.Transaction t) {
        if (t == null) return;
        editing = t;
        amountField.setText(t.amount == null ? "" : t.amount.toPlainString());
        noteField.setText(t.note == null ? "" : t.note);
        datePicker.setValue(t.date);
        if ("INCOME".equals(t.type)) {
            rbIncome.setSelected(true);
            loadCategories(true);
        } else {
            rbExpense.setSelected(true);
            loadCategories(false);
        }
        categoryBox.setValue(db.categoryName(t.categoryClientId));
        db.listAccounts().stream()
                .filter(a -> a.clientId != null && a.clientId.equals(t.accountClientId))
                .findFirst()
                .ifPresent(a -> accountBox.setValue(a.name));
        info("正在编辑 " + t.date + " 这笔；改完点「保存」，会覆盖原记录");
    }

    // ---------------- 模板 ----------------

    /** 模板入口：套用已保存模板，或把当前表单存成模板、删除模板。 */
    @FXML
    public void onTemplate() {
        java.util.List<com.personal.accounting.desktop.model.Template> templates = listTemplates();
        java.util.ArrayList<String> choices = new java.util.ArrayList<>();
        choices.add("＋ 把当前内容存为模板");
        for (com.personal.accounting.desktop.model.Template t : templates) {
            choices.add(t.name + "　" + t.amount + " 元");
        }
        choices.add("删除模板…");
        javafx.scene.control.ChoiceDialog<String> dialog =
                new javafx.scene.control.ChoiceDialog<>(choices.get(0), choices);
        dialog.setTitle("模板");
        dialog.setHeaderText("选一个模板套到表单上，或管理模板");
        dialog.showAndWait().ifPresent(pick -> {
            if (pick.startsWith("＋")) {
                saveAsTemplate();
            } else if (pick.startsWith("删除模板")) {
                deleteTemplateFlow(templates);
            } else {
                int index = choices.indexOf(pick) - 1;
                if (index >= 0 && index < templates.size()) applyTemplate(templates.get(index));
            }
        });
    }

    /** 未删除的模板，按名字排序。 */
    private java.util.List<com.personal.accounting.desktop.model.Template> listTemplates() {
        return db.listTemplatesModifiedSince(0).stream()
                .filter(t -> !t.deleted)
                .sorted(java.util.Comparator.comparing(t -> t.name == null ? "" : t.name))
                .toList();
    }

    /** 套用模板：金额 / 收支 / 分类 / 账户 / 备注一起填回表单。 */
    private void applyTemplate(com.personal.accounting.desktop.model.Template t) {
        amountField.setText(t.amount == null ? "" : t.amount.toPlainString());
        noteField.setText(t.note == null ? "" : t.note);
        if ("INCOME".equals(t.type)) {
            rbIncome.setSelected(true);
            loadCategories(true);
        } else {
            rbExpense.setSelected(true);
            loadCategories(false);
        }
        categoryBox.setValue(db.categoryName(t.categoryClientId));
        db.listAccounts().stream()
                .filter(a -> a.clientId != null && a.clientId.equals(t.accountClientId))
                .findFirst()
                .ifPresent(a -> accountBox.setValue(a.name));
        info("已套用模板：" + t.name);
    }

    /** 把当前表单存成模板。模板记的是「怎么记」，不含日期。 */
    private void saveAsTemplate() {
        if (amountField.getText() == null || amountField.getText().isBlank()) {
            warn("先填一个金额");
            return;
        }
        if (categoryBox.getValue() == null) {
            warn("先选一个分类");
            return;
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(amountField.getText());
        } catch (NumberFormatException e) {
            warn("金额格式错误");
            return;
        }
        javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog();
        dialog.setTitle("存为模板");
        dialog.setHeaderText("给这个模板起个名字，例如「房租」");
        dialog.showAndWait().ifPresent(name -> {
            String trimmed = name == null ? "" : name.trim();
            if (trimmed.isEmpty()) {
                warn("模板名不能为空");
                return;
            }
            com.personal.accounting.desktop.model.Template t = new com.personal.accounting.desktop.model.Template();
            t.clientId = UUID.randomUUID().toString();
            t.name = trimmed;
            t.amount = amount;
            t.type = rbIncome.isSelected() ? "INCOME" : "EXPENSE";
            Category cat = db.listCategories(t.type).stream()
                    .filter(c -> c.name.equals(categoryBox.getValue())).findFirst().orElse(null);
            t.categoryClientId = cat == null ? null : cat.clientId;
            String accName = accountBox.getValue();
            Account acc = db.listAccounts().stream()
                    .filter(a -> a.name.equals(accName)).findFirst().orElse(null);
            t.accountClientId = acc == null ? null : acc.clientId;
            t.note = noteField.getText();
            t.sortOrder = 0;
            t.lastModified = System.currentTimeMillis();
            t.deleted = false;
            db.upsertTemplate(t);
            syncNow();
            info("已存为模板：" + trimmed);
        });
    }

    /** 删除模板：置墓碑再同步，另一端才会跟着删掉。 */
    private void deleteTemplateFlow(java.util.List<com.personal.accounting.desktop.model.Template> templates) {
        if (templates.isEmpty()) {
            warn("还没有模板");
            return;
        }
        java.util.List<String> names = templates.stream().map(t -> t.name).toList();
        javafx.scene.control.ChoiceDialog<String> dialog =
                new javafx.scene.control.ChoiceDialog<>(names.get(0), names);
        dialog.setTitle("删除模板");
        dialog.setHeaderText("选一个要删除的模板");
        dialog.showAndWait().ifPresent(name -> {
            templates.stream().filter(t -> name.equals(t.name)).findFirst().ifPresent(t -> {
                t.deleted = true;
                t.lastModified = System.currentTimeMillis();
                db.upsertTemplate(t);
                syncNow();
                info("已删除模板：" + name);
            });
        });
    }

    /** 写完本地数据后随手同步一次，失败静默。 */
    private void syncNow() {
        MainController.SYNC_EXECUTOR.submit(() -> {
            try {
                new SyncManager(db).sync();
            } catch (Exception ignore) {
            }
        });
    }

    /** 删除选中的流水（软删除，可同步）。 */
    @FXML
    public void onDelete() {
        com.personal.accounting.desktop.model.Transaction sel = table.getSelectionModel().getSelectedItem();
        if (sel == null) {
            warn("请先在列表中选中要删除的流水");
            return;
        }
        db.deleteTransaction(sel.clientId);
        // 删掉的正好是当前在编辑的那条：退出编辑态，免得保存时又把它写回去
        if (editing != null && editing.clientId.equals(sel.clientId)) editing = null;
        MainController.SYNC_EXECUTOR.submit(() -> {
            try {
                new SyncManager(db).sync();
            } catch (Exception ignore) {
            }
        });
        loadTransactions();
        info("已删除流水：" + sel.date.format(DateTimeFormatter.ISO_LOCAL_DATE) + "  " + sel.amount + " 元");
    }

    private void loadTransactions() {
        table.getItems().setAll(db.listTransactions(null, null, null, null));
        refreshFirstUseCard();
    }

    /**
     * 首次使用引导卡：只在**一条流水都还没有**时显示。
     *
     * <p>看的是流水总数而不是「首次使用」标记 —— 记完第一笔它自己就消失，
     * 换库、清数据也能正确复位，不会出现「标记说看过、用户其实没记过」的错位。
     * 同时切换 {@code managed}：JavaFX 里隐藏的节点若仍参与布局会留一块空白。</p>
     */
    private void refreshFirstUseCard() {
        if (firstUseCard == null) return;
        boolean empty = db.countTransactions() == 0;
        firstUseCard.setVisible(empty);
        firstUseCard.setManaged(empty);
    }

    private void warn(String msg) {
        new Alert(Alert.AlertType.WARNING, msg).show();
    }

    private void info(String msg) {
        new Alert(Alert.AlertType.INFORMATION, msg).show();
    }
}
