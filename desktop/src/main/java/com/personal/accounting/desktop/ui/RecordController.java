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

    private final DataManager db = AppState.db();

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
        colCategory.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(categoryName(d.getValue().categoryClientId)));
        colNote.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().note));
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
        new Thread(() -> {
            try {
                new SyncManager(db).sync();
            } catch (Exception ignore) {
                // 离线时静默失败，下次同步会补上
            }
        }).start();
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

        Transaction t = new Transaction();
        t.clientId = UUID.randomUUID().toString();
        t.date = datePicker.getValue() == null ? LocalDate.now() : datePicker.getValue();
        t.amount = amount;
        t.type = rbIncome.isSelected() ? "INCOME" : "EXPENSE";
        t.categoryClientId = cat == null ? null : cat.clientId;
        t.accountClientId = acc == null ? null : acc.clientId;
        t.note = noteField.getText();
        t.source = "manual";
        t.lastModified = System.currentTimeMillis();
        t.deleted = false;
        db.upsertTransaction(t);

        new Thread(() -> {
            try {
                new SyncManager(db).sync();
            } catch (Exception ignore) {
            }
        }).start();

        amountField.clear();
        noteField.clear();
        loadTransactions();
        new Alert(Alert.AlertType.INFORMATION, "已记账").show();
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
        loadTransactions();
        info("已删除流水：" + sel.date.format(DateTimeFormatter.ISO_LOCAL_DATE) + "  " + sel.amount + " 元");
    }

    private void loadTransactions() {
        table.getItems().setAll(db.listTransactions(null, null, null, null));
    }

    private String categoryName(String clientId) {
        if (clientId == null) return "未分类";
        var c = db.findCategory(clientId);
        return c == null ? "未分类" : c.name;
    }

    private void warn(String msg) {
        new Alert(Alert.AlertType.WARNING, msg).show();
    }

    private void info(String msg) {
        new Alert(Alert.AlertType.INFORMATION, msg).show();
    }
}
