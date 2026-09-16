package com.personal.accounting.android.update;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 应用内更新：下载新版 apk 到应用私有目录，然后交给系统安装界面。
 *
 * <p>Android 8.0+ 需要用户在系统设置里允许本应用"安装未知应用"，
 * 这里会检测并引导用户去开启。</p>
 */
public final class ApkUpdater {

    /** 下载进度回调（主线程之外调用，注意切回 UI 线程更新界面）。 */
    public interface Progress {
        void onProgress(int percent);
    }

    private static final String AUTHORITY_SUFFIX = ".fileprovider";
    private static final String APK_MIME = "application/vnd.android.package-archive";

    private ApkUpdater() {
    }

    /** 已安装版本号（versionName），读取失败返回 "0"。 */
    public static String installedVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "0" : info.versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** 下载 apk。返回下载完成的文件；失败抛异常。 */
    public static File download(Context context, String url, Progress progress) throws IOException {
        if (url == null || url.trim().isEmpty()) {
            throw new IOException("下载地址为空");
        }
        File dir = new File(context.getExternalFilesDir(null), "updates");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建下载目录");
        }
        File target = new File(dir, "TerminalLedger-update.apk");
        File temp = new File(dir, "TerminalLedger-update.apk.part");

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder().url(url.trim()).get().build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("下载失败：HTTP " + response.code());
            }
            long total = response.body().contentLength();
            long read = 0;
            try (InputStream in = response.body().byteStream();
                 FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[8192];
                int len;
                int lastPercent = -1;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                    read += len;
                    if (total > 0 && progress != null) {
                        int percent = (int) (read * 100 / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            progress.onProgress(percent);
                        }
                    }
                }
                out.flush();
            }
        } catch (IOException e) {
            // 下载中断时清掉临时文件，避免留下半个包
            if (temp.exists() && !temp.delete()) {
                temp.deleteOnExit();
            }
            throw e;
        }
        if (!looksLikeApk(temp)) {
            if (temp.exists() && !temp.delete()) {
                temp.deleteOnExit();
            }
            throw new IOException("下载到的文件不是有效的安装包");
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("无法覆盖旧安装包");
        }
        if (!temp.renameTo(target)) {
            throw new IOException("无法保存安装包");
        }
        return target;
    }

    /** apk 是 zip 容器，校验魔数避免把错误页面当安装包。 */
    private static boolean looksLikeApk(File file) {
        try (InputStream in = new java.io.FileInputStream(file)) {
            return in.read() == 'P' && in.read() == 'K';
        } catch (IOException e) {
            return false;
        }
    }

    /** 调起系统安装界面；未授予"安装未知应用"权限时跳转设置页并返回 false。 */
    public static boolean install(Activity activity, File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(activity, "请先允许本应用「安装未知应用」，返回后再试一次", Toast.LENGTH_LONG).show();
            try {
                Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(settings);
            } catch (Exception e) {
                Toast.makeText(activity, "无法打开安装权限设置：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            return false;
        }
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + AUTHORITY_SUFFIX, apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, APK_MIME);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
            return true;
        } catch (Exception e) {
            Toast.makeText(activity, "无法启动安装界面：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }
}
