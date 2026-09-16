package com.personal.accounting.android.update;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 版本号比较："1.0.10" &gt; "1.0.9"（按数字逐段比较，而不是字符串比较）。
 * 支持 "1.0.2-test" 这类带后缀的版本：后缀不参与比较。
 *
 * <p>纯逻辑，不依赖 Android，便于单独测试。</p>
 */
public final class VersionComparator {

    private static final Pattern LEADING_NUMBER = Pattern.compile("^(\\d+)");

    private VersionComparator() {
    }

    /** @return a 比 b 新返回正数，相同返回 0，更旧返回负数。 */
    public static int compare(String a, String b) {
        int[] left = parts(a);
        int[] right = parts(b);
        int len = Math.max(left.length, right.length);
        for (int i = 0; i < len; i++) {
            int l = i < left.length ? left[i] : 0;
            int r = i < right.length ? right[i] : 0;
            if (l != r) return l - r;
        }
        return 0;
    }

    /** 是否 a 比 b 新。 */
    public static boolean isNewer(String a, String b) {
        return compare(a, b) > 0;
    }

    private static int[] parts(String version) {
        if (version == null) return new int[0];
        String cleaned = version.trim();
        // 去掉常见前缀 "v" 与空白
        if (cleaned.startsWith("v") || cleaned.startsWith("V")) cleaned = cleaned.substring(1);
        String[] segments = cleaned.split("\\.");
        int[] result = new int[segments.length];
        for (int i = 0; i < segments.length; i++) {
            Matcher m = LEADING_NUMBER.matcher(segments[i].trim());
            result[i] = m.find() ? parseIntSafely(m.group(1)) : 0;
        }
        return result;
    }

    private static int parseIntSafely(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
