package com.personal.accounting.desktop.ui;

import com.personal.accounting.desktop.service.AiConfig;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

/**
 * AI 分析设置对话框（首次向导与「AI 设置」入口共用）。
 *
 * <p>解决一个实际缺口：以前只在首次启动时问一次 API Key，选了"规则引擎"之后就再没有地方改回大模型。</p>
 */
final class AiSettingsDialog {

    private AiSettingsDialog() {
    }

    /** 打开设置对话框；保存成功后执行 onSaved（用于刷新界面），取消则什么都不做。 */
    static void show(Runnable onSaved) {
        AiConfig.load();
        boolean currentLlm = AiConfig.canUseLlm();

        PasswordField keyField = new PasswordField();
        keyField.setPromptText("留空则用本地规则引擎");
        keyField.setText(AiConfig.apiKey());
        TextField baseField = new TextField(AiConfig.baseUrl());
        TextField modelField = new TextField(AiConfig.model());
        CheckBox useLlm = new CheckBox("使用大模型分析（需要上面的 API Key）");
        useLlm.setSelected(currentLlm);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("API Key"), keyField);
        grid.addRow(1, new Label("接口地址"), baseField);
        grid.addRow(2, new Label("模型"), modelField);
        grid.add(useLlm, 1, 3);
        grid.add(new Label("当前状态：" + (currentLlm ? "大模型分析" : "本地规则引擎（离线可用）")), 1, 4);

        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("AI 分析设置");
        dlg.setHeaderText("填入 API Key 并勾选「使用大模型」即启用大模型分析；\n"
                + "不填 Key（或取消勾选）则使用内置规则引擎：纯本地、离线可用。");
        dlg.getDialogPane().setContent(grid);

        ButtonType save = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        ButtonType useRule = new ButtonType("改用规则引擎", ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dlg.getDialogPane().getButtonTypes().addAll(save, useRule, cancel);

        dlg.showAndWait().ifPresent(bt -> {
            try {
                if (bt == save) {
                    String key = keyField.getText() == null ? "" : keyField.getText().trim();
                    // 必填项校验：勾了大模型却没 Key，等价于规则引擎
                    boolean llm = useLlm.isSelected() && !key.isEmpty();
                    AiConfig.save(llm, key, baseField.getText(), modelField.getText());
                    info(llm ? "已启用大模型分析" : "已保存（使用本地规则引擎）");
                } else if (bt == useRule) {
                    AiConfig.save(false, AiConfig.apiKey(), baseField.getText(), modelField.getText());
                    info("已改用本地规则引擎");
                } else {
                    return;
                }
                if (onSaved != null) onSaved.run();
            } catch (Exception e) {
                new Alert(Alert.AlertType.WARNING, "保存失败：" + e.getMessage()).show();
            }
        });
    }

    private static void info(String msg) {
        new Alert(Alert.AlertType.INFORMATION, msg).show();
    }
}
