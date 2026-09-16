package com.personal.accounting.android.bill;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 账单截图 OCR：用 ML Kit 端上中文文本识别（离线，不需要 Google Play 服务联网下载模型）。
 *
 * <p>为了避免大图导致 OOM，先把图片按最长边约 1600px 采样解码，再交给识别器。</p>
 */
public final class ScreenshotOcr {

    public interface Callback {
        void onText(String text);

        void onError(String message);
    }

    private static final String TAG = "ScreenshotOcr";
    private static final int MAX_EDGE = 1600;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public ScreenshotOcr(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 识别指定图片，回调在主线程。 */
    public void recognize(Uri uri, Callback callback) {
        executor.execute(() -> {
            try {
                Bitmap bitmap = decodeDownscaled(uri);
                if (bitmap == null) {
                    postError(callback, "无法读取这张图片");
                    return;
                }
                TextRecognizer recognizer = TextRecognition.getClient(
                        new ChineseTextRecognizerOptions.Builder().build());
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener(result -> {
                            recognizer.close();
                            if (!bitmap.isRecycled()) bitmap.recycle();
                            main.post(() -> callback.onText(textOf(result)));
                        })
                        .addOnFailureListener(e -> {
                            Log.w(TAG, "ocr failed", e);
                            recognizer.close();
                            if (!bitmap.isRecycled()) bitmap.recycle();
                            postError(callback, "识别失败：" + e.getMessage());
                        });
            } catch (Exception e) {
                Log.w(TAG, "recognize failed", e);
                postError(callback, "识别失败：" + e.getMessage());
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
    }

    private void postError(Callback callback, String message) {
        main.post(() -> callback.onError(message));
    }

    /** ML Kit 结果按行拼接，保留换行便于后续按行解析商户与金额。 */
    private static String textOf(Text result) {
        StringBuilder sb = new StringBuilder();
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line.getText());
            }
        }
        return sb.toString();
    }

    /** 只读图片头，按最长边 MAX_EDGE 采样解码，避免整张大图进内存。 */
    private Bitmap decodeDownscaled(Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
        while (maxSide / sample > MAX_EDGE * 2) sample *= 2;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(in, null, opts);
        }
    }
}
