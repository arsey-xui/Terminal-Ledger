package com.personal.accounting.desktop;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.scene.Parent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.effect.GaussianBlur;

import java.util.Objects;

/** JavaFX 应用入口。 */
public class DesktopApp extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        Parent content = FXMLLoader.load(Objects.requireNonNull(
                getClass().getResource("/fxml/main.fxml")));

        // 真·毛玻璃：底层固定高斯模糊的背景层，前景半透明面板透出模糊背景
        Region backdrop = new Region();
        backdrop.getStyleClass().add("backdrop");
        backdrop.setEffect(new GaussianBlur(16));

        StackPane root = new StackPane(backdrop, content);
        Scene scene = new Scene(root, 1080, 720);
        scene.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("/css/app.css")).toExternalForm());
        stage.setTitle("终端账本");
        // 窗口/任务栏图标：使用打包进 jar 的 /icon.png（仓库根 icon.png 的资源副本）
        var iconRes = getClass().getResource("/icon.png");
        if (iconRes != null) {
            stage.getIcons().add(new javafx.scene.image.Image(iconRes.toExternalForm()));
        }
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
