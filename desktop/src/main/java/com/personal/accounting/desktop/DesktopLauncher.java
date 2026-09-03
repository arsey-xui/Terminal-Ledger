package com.personal.accounting.desktop;

import javafx.application.Application;

/**
 * 启动器。JavaFX 在 classpath（非模块路径）模式下，主类不能直接是 Application 子类，
 * 否则会报 "JavaFX runtime components are missing"。用一个普通类来调用 launch 即可。
 */
public class DesktopLauncher {
    public static void main(String[] args) {
        Application.launch(DesktopApp.class, args);
    }
}
