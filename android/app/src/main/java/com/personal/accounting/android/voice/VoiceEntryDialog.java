package com.personal.accounting.android.voice;

import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.List;

/**
 * 「语音输入 · 文本解析」弹窗。记账页和桌面小组件共用这一个入口，
 * 保证两处的输入格式、解析规则和提示文案完全一致。
 *
 * <p>用法：调用方实现 {@link Host} 提供本机已有的账户名/分类名，并在
 * {@link Host#onVoiceParsed} 里按自己的界面回填结果。</p>
 */
public final class VoiceEntryDialog {

    /** 使用方：提供本机已有的名字，并接收解析结果。 */
    public interface Host {
        /** 本机已有账户名，用于识别「账户」段；可为空集合。 */
        List<String> voiceAccountNames();

        /** 当前类型（支出/收入）下本机已有分类名，用于识别「分类」段；可为空集合。 */
        List<String> voiceCategoryNames();

        /** 解析完成，按自己的界面回填金额、分类、账户、备注。 */
        void onVoiceParsed(VoiceInputParser.Parsed parsed);
    }

    private static final String FORMAT =
            "格式：账户 + 分类 + 金额（顺序随意），例如「支付宝午餐27元」。\n"
                    + "点输入框，用输入法键盘上的麦克风说话即可。";

    private VoiceEntryDialog() {
    }

    public static void show(Context context, Host host) {
        EditText input = new EditText(context);
        input.setHint("例如：支付宝午餐27元");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        int pad = (int) (20 * context.getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new AlertDialog.Builder(context)
                .setTitle("语音输入 · 文本解析")
                .setMessage(FORMAT)
                .setView(input)
                .setPositiveButton("解析填入", (d, w) -> {
                    String text = input.getText().toString().trim();
                    if (text.isEmpty()) {
                        Toast.makeText(context, "没有内容可解析", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    host.onVoiceParsed(VoiceInputParser.parse(
                            text, host.voiceAccountNames(), host.voiceCategoryNames()));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 统一的结果提示：说清三段各自落到了哪里、哪一段没识别到。 */
    public static String describe(VoiceInputParser.Parsed parsed, String categoryName,
                                  String accountName) {
        if (parsed.amount <= 0) {
            return "没识别到金额。请说成「账户 + 分类 + 金额」，例如：支付宝午餐27元";
        }
        StringBuilder sb = new StringBuilder("已选好：金额 ").append(amountText(parsed.amount));
        sb.append(" · 分类 ").append(categoryName == null ? "未选出" : categoryName);
        sb.append(" · 账户 ").append(accountName == null ? "未选出" : accountName);
        if (parsed.accountName == null) sb.append("（没提到账户，沿用当前账户）");
        return sb.toString();
    }

    /** 27.50 → "27.5"；整数金额不带小数点。 */
    public static String amountText(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
