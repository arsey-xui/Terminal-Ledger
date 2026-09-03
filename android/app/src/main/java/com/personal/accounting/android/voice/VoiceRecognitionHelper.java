package com.personal.accounting.android.voice;

import android.app.Activity;
import android.content.Intent;
import android.speech.RecognizerIntent;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 语音识别封装：拉起系统语音输入界面（RecognizerIntent）。
 * 用 {@link Intent#resolveActivity} 先确认设备上确实存在能处理语音的 Activity，
 * 避免直接 startActivityForResult 抛 ActivityNotFoundException 而闪退。
 */
public final class VoiceRecognitionHelper {

    private VoiceRecognitionHelper() {
    }

    /**
     * 尝试拉起系统语音识别界面。
     *
     * @return true 表示已成功启动；false 表示设备上没有可用的语音识别服务。
     */
    public static boolean start(Activity activity, int requestCode, Locale locale) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,
                locale == null ? Locale.getDefault().toLanguageTag() : locale.toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        if (intent.resolveActivity(activity.getPackageManager()) == null) {
            return false;
        }
        activity.startActivityForResult(intent, requestCode);
        return true;
    }

    /** 在 onActivityResult 中取出识别到的文本。 */
    public static String resultText(Intent data) {
        if (data == null) return null;
        ArrayList<String> texts = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        return (texts == null || texts.isEmpty()) ? null : texts.get(0);
    }
}
