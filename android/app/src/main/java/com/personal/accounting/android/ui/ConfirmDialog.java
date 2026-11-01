package com.personal.accounting.android.ui;

import android.content.Context;

import androidx.appcompat.app.AlertDialog;

/**
 * 破坏性操作前的确认弹窗。
 *
 * <p>把「取消在左、确认在右、点外面等于取消」统一到一处，避免各页面各写一份而不一致。
 * 确认之后做什么由调用方决定，这里不碰任何数据。</p>
 */
public final class ConfirmDialog {

    private ConfirmDialog() {
    }

    /**
     * @param title        标题，一句话说清要做什么
     * @param message      正文，写清影响范围（删的是什么、会不会同步到另一端）
     * @param confirmLabel 确认按钮文案，用动词而不是「确定」
     * @param onConfirm    用户确认后执行的动作
     */
    public static void show(Context context, String title, String message,
                            String confirmLabel, Runnable onConfirm) {
        if (context == null) return;
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton(confirmLabel, (d, w) -> {
                    if (onConfirm != null) onConfirm.run();
                })
                .show();
    }
}
