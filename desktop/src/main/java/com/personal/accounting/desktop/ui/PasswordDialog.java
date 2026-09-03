package com.personal.accounting.desktop.ui;

import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.GridPane;

/** 简单密码输入对话框。 */
public class PasswordDialog extends Dialog<String> {

    private final PasswordField field = new PasswordField();

    public PasswordDialog() {
        setTitle("加密备份");
        setHeaderText("请输入备份密码");
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.add(field, 0, 0);
        getDialogPane().setContent(grid);
        setResultConverter(bt -> bt == ButtonType.OK ? field.getText() : null);
    }
}
