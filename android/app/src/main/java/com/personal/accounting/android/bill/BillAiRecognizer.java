package com.personal.accounting.android.bill;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.personal.accounting.android.analysis.AiAnalysis;
import com.personal.accounting.android.util.BitmapUtils;

import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 截图记账的 AI 识别：把整张截图交给**多模态**大模型，直接得到金额 / 账户 / 分类 / 商户。
 *
 * <p>与 {@link ScreenshotOcr}（ML Kit 端上识别 + {@link BillTextParser} 规则解析）是两条并行的路：
 * 用户没配 API Key 时走本地那条；配了就优先走这条，任何失败（网络、模型不支持图片、返回无法解析）
 * 都会回退到本地那条，不会让功能不可用。</p>
 *
 * <p>复用的是「AI 消费分析」里那一套配置（{@link AiAnalysis} 的 Key / 接口地址 / 模型），
 * 所以用户只需要配一次。注意模型必须支持图像输入，默认的 {@code gpt-4o-mini} 支持。</p>
 *
 * <p>隐私：截图会**整张**上传到用户自己填的接口地址，不经由本项目的中转。</p>
 */
public final class BillAiRecognizer {

    public interface Callback {
        void onResult(BillAiPrompt.Result result);

        void onError(String message);
    }

    private static final String TAG = "BillAiRecognizer";
    private static final String PREFS = "bill_ai_prefs";
    private static final String K_HINTED = "hint_shown";
    /** 长边上限：再大对识别金额没有帮助，只会让请求变慢、变贵。 */
    private static final int MAX_EDGE = 1568;
    private static final int JPEG_QUALITY = 85;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public BillAiRecognizer(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 是否具备走 AI 识别的条件：用户配了 API Key（且没切回规则引擎）。 */
    public static boolean isAvailable(Context context) {
        return AiAnalysis.canUseLlm(context);
    }

    /**
     * 是否该在截图前提示一次「可以配置 AI 识别」。
     * 只在没配 Key 且从没提示过时为 true，避免每次截图都弹。
     */
    public static boolean shouldHint(Context context) {
        if (isAvailable(context)) return false;
        return !context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(K_HINTED, false);
    }

    /** 记下已经提示过，之后不再弹。 */
    public static void markHinted(Context context) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(K_HINTED, true).apply();
    }

    /** 识别指定图片，回调在主线程。 */
    public void recognize(Uri uri, Collection<String> accountNames, Collection<String> categoryNames,
                          Callback callback) {
        executor.execute(() -> {
            try {
                String dataUri = imageDataUri(uri);
                if (dataUri == null) {
                    postError(callback, "无法读取这张图片");
                    return;
                }
                String content = callVision(dataUri, accountNames, categoryNames);
                BillAiPrompt.Result result = BillAiPrompt.parseResponse(content);
                if (result == null) {
                    postError(callback, "AI 返回的内容无法解析");
                    return;
                }
                // 账户/分类必须落在本机列表里，模型编出来的名字直接丢掉，交给界面保持原值
                if (!contains(accountNames, result.accountName)) result.accountName = null;
                if (!contains(categoryNames, result.categoryName)) result.categoryName = null;
                main.post(() -> callback.onResult(result));
            } catch (Exception e) {
                Log.w(TAG, "recognize failed", e);
                postError(callback, "AI 识别失败：" + e.getMessage());
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
    }

    private void postError(Callback callback, String message) {
        main.post(() -> callback.onError(message));
    }

    private static boolean contains(Collection<String> names, String value) {
        if (value == null || names == null) return false;
        for (String n : names) {
            if (n != null && n.trim().equals(value)) return true;
        }
        return false;
    }

    // ---------- 图片 ----------

    /** 缩到长边 MAX_EDGE 再转 JPEG，最后拼成 data URI（OpenAI 兼容接口接受这种内联图片）。 */
    private String imageDataUri(Uri uri) throws Exception {
        Bitmap bitmap = BitmapUtils.decodeDownscaled(context, uri, MAX_EDGE);
        if (bitmap == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
        if (!bitmap.isRecycled()) bitmap.recycle();
        String base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        return "data:image/jpeg;base64," + base64;
    }

    // ---------- 请求 ----------

    /** OpenAI 兼容的多模态对话补全：文字 + 图片放在同一条 user 消息里。 */
    private String callVision(String dataUri, Collection<String> accountNames,
                              Collection<String> categoryNames) throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("model", AiAnalysis.model(context));
        root.addProperty("temperature", 0);
        // 刻意不设 max_tokens：DeepSeek 等模型的「思考模式」默认开启，思维链与最终回答共享输出预算，
        // 给一个小上限会让思维链把预算吃光、content 直接为空，白白回退到本机识别。
        // 这里要的输出只有一行 JSON，不设上限也失控不了。
        // （temperature 在思考模式下会被忽略，但不会报错，保留它对非思考模型仍然有效。）

        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", BillAiPrompt.SYSTEM);

        JsonArray parts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", BillAiPrompt.userText(accountNames, categoryNames));
        parts.add(textPart);

        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", dataUri);
        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("type", "image_url");
        imagePart.add("image_url", imageUrl);
        parts.add(imagePart);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.add("content", parts);

        JsonArray messages = new JsonArray();
        messages.add(system);
        messages.add(user);
        root.add("messages", messages);

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)   // 图片推理比纯文本慢，给足时间
                .build();

        Request request = new Request.Builder()
                .url(trimTrailingSlash(AiAnalysis.baseUrl(context)) + "/chat/completions")
                .addHeader("Authorization", "Bearer " + AiAnalysis.apiKey(context))
                .post(RequestBody.create(root.toString(), JSON))
                .build();

        try (Response resp = client.newCall(request).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new java.io.IOException("HTTP " + resp.code());
            }
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            JsonArray choices = json.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) return "";
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null || !message.has("content")) return "";
            return message.get("content").getAsString();
        }
    }

    private static String trimTrailingSlash(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }
}
