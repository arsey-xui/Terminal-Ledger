package com.personal.accounting.android.analysis;

import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

/**
 * 「AI 设置」弹窗：API Key / 接口地址 / 模型 / 分析提示词。报表页与记账页（截图识别）共用同一份配置，
 * 所以只需要设置一次。
 *
 * <p>提示里明确写了截图识别需要支持图像输入的模型 —— 报表分析只要文本能力，
 * 截图识别要的是多模态，用户不知道这一点时最容易踩坑。</p>
 *
 * <p>「分析提示词」只影响报表层的大模型分析，不影响截图识别的固定输出契约
 * （截图识别要求只输出 JSON，改掉会直接解析失败），所以两者分开。</p>
 */
public final class AiSettingsDialog {

    private AiSettingsDialog() {
    }

    /**
     * @param onSaved 保存或切回规则引擎后的回调，可为 null
     */
    public static void show(Context context, final Runnable onSaved) {
        String current = AiAnalysis.useLlm(context)
                ? "当前：大模型分析（已配置 API Key）"
                : "当前：本地规则引擎（未配置 API Key）";

        EditText key = new EditText(context);
        key.setHint("API Key（如 sk-...）");
        EditText base = new EditText(context);
        base.setHint("接口地址（默认 api.openai.com）");
        base.setText(AiAnalysis.baseUrl(context));
        EditText model = new EditText(context);
        model.setHint("模型（默认 gpt-4o-mini）");
        model.setText(AiAnalysis.model(context));

        // 提示词单独用一个弹窗编辑：多行长文本塞进这个表单输入框会很难用
        Button promptButton = new Button(context);
        promptButton.setAllCaps(false);
        updatePromptButton(promptButton, context);
        promptButton.setOnClickListener(v -> showPromptEditor(context, () -> updatePromptButton(promptButton, context)));

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * context.getResources().getDisplayMetrics().density);
        box.setPadding(pad, 0, pad, 0);
        box.addView(key);
        box.addView(base);
        box.addView(model);
        box.addView(promptButton);

        new AlertDialog.Builder(context)
                .setTitle("AI 设置")
                .setMessage(current + "\n\n"
                        + "填入 API Key 后点「保存」改用大模型；想切回本地规则引擎可直接点「改用规则引擎」。\n\n"
                        + "「截图记账」的 AI 识别用的是同一个 Key，所以模型需要**支持图像输入**"
                        + "（如 gpt-4o-mini）；模型不支持图像时，截图会自动退回本机离线识别。")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    AiAnalysis.save(context, key.getText().toString(),
                            base.getText().toString(), model.getText().toString(),
                            AiAnalysis.prompt(context));
                    Toast.makeText(context,
                            AiAnalysis.useLlm(context) ? "已切换为大模型" : "未填 Key，继续用本地规则引擎",
                            Toast.LENGTH_SHORT).show();
                    if (onSaved != null) onSaved.run();
                })
                // 一步切回规则引擎：清掉已保存的 Key，不再需要手动清空输入框
                .setNeutralButton("改用规则引擎", (d, w) -> {
                    AiAnalysis.clearLlm(context);
                    Toast.makeText(context, "已改回本地规则引擎", Toast.LENGTH_SHORT).show();
                    if (onSaved != null) onSaved.run();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 按钮文字直接反映当前用的是自定义还是默认提示词，省得用户点进去才知道。 */
    private static void updatePromptButton(Button button, Context context) {
        button.setText(AiAnalysis.hasCustomPrompt(context) ? "分析提示词：已自定义（点击修改）"
                : "分析提示词：默认（点击修改）");
    }

    /**
     * 提示词编辑弹窗：留空即恢复默认，另有「恢复默认」一键填回默认文案。
     * 改的是 {@link AiAnalysis#savePrompt}，与 API Key 分开存，互不影响。
     */
    public static void showPromptEditor(Context context, final Runnable onChanged) {
        final EditText input = new EditText(context);
        input.setGravity(android.view.Gravity.TOP);
        input.setMinLines(8);
        input.setHint("留空使用默认提示词");
        input.setText(AiAnalysis.prompt(context));

        int pad = (int) (20 * context.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, 0, pad, 0);
        TextView hint = new TextView(context);
        hint.setText("提示词决定大模型分析的侧重点；留空即用默认那份（已包含日平均 / 日标准差 / 日中位数）。");
        hint.setTextSize(12);
        hint.setPadding(0, 0, 0, pad / 2);
        box.addView(hint);
        box.addView(input);

        new AlertDialog.Builder(context)
                .setTitle("分析提示词")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    AiAnalysis.savePrompt(context, input.getText().toString());
                    Toast.makeText(context,
                            AiAnalysis.hasCustomPrompt(context) ? "已保存自定义提示词" : "已恢复默认提示词",
                            Toast.LENGTH_SHORT).show();
                    if (onChanged != null) onChanged.run();
                })
                .setNeutralButton("恢复默认", (d, w) -> {
                    input.setText(AiAnalysis.DEFAULT_PROMPT);
                    AiAnalysis.clearPrompt(context);
                    Toast.makeText(context, "已恢复默认提示词", Toast.LENGTH_SHORT).show();
                    if (onChanged != null) onChanged.run();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
