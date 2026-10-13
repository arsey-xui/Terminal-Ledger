package com.personal.accounting.android.ui;

import android.content.Context;
import android.content.res.TypedArray;

import androidx.annotation.ColorInt;
import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.personal.accounting.android.R;

/**
 * 统一从资源/主题取色。
 *
 * <p>为什么必须走这里：图表的坐标轴、网格线、饼图调色板等颜色是**在代码里设置**的，
 * {@code values-night} 只能覆盖 XML 里的引用，覆盖不到 Java 里写死的 {@code 0xFF8C8C8C}。
 * 之前 ReportFragment 一个文件就有 24 处硬编码，结果系统切深色时界面底色变了、
 * 图表文字和调色板还是浅色那一套，出现「混搭」。</p>
 *
 * <p>取色一律走资源：{@code values/colors.xml} 与 {@code values-night/colors.xml}
 * 定义同一套语义名字，系统按深浅模式自动选，代码里不再关心当前是哪个模式。</p>
 */
final class ChartColors {

    private ChartColors() {
    }

    /** 按名字取当前模式下的颜色（深色模式下自动取 values-night 的值）。 */
    @ColorInt
    static int of(@NonNull Context context, @ColorRes int colorRes) {
        return ContextCompat.getColor(context, colorRes);
    }

    /** 主题属性取色（例如 colorPrimary、android:colorBackground）。 */
    @ColorInt
    static int attr(@NonNull Context context, int attrRes, @ColorInt int fallback) {
        TypedArray a = context.getTheme().obtainStyledAttributes(new int[]{attrRes});
        try {
            return a.getColor(0, fallback);
        } finally {
            a.recycle();
        }
    }

    /**
     * 当前模式下的饼图调色板。
     *
     * <p>浅色/深色各一套（{@code values/pie_palette.xml} 与 {@code values-night/pie_palette.xml}），
     * 顺序上靠前的颜色落在占比最大的扇区，保证大扇区上的标签始终可读。</p>
     */
    @ColorInt
    static int[] piePalette(@NonNull Context context) {
        int[] palette = context.getResources().getIntArray(R.array.pie_palette);
        return palette.length == 0 ? new int[]{of(context, R.color.primary)} : palette;
    }

    /**
     * 按背景明度给标签挑反色：在给定的两种候选色里选**对比度更高**的那个。
     *
     * <p>早先的实现是「亮度 &gt; 0.55 就用深色」，在中间调上会判错：中灰（如 #8C8C8C，亮度 0.42）
     * 会被判给浅色文字，结果是白字压在浅灰扇区上完全看不见 —— 但同一块灰在浅色基底里
     * 换成了更浅的取值，又该用深字。固定阈值在这两端之间必有一头是错的。</p>
     *
     * <p>直接比较两个候选的实际对比度就没有这个临界问题：谁亮得开用谁。
     * 用 W3C 的相对亮度公式（含 sRGB 反伽马），比单纯亮度加权更贴近肉眼。</p>
     */
    @ColorInt
    static int contrastOn(@ColorInt int background, @ColorInt int light, @ColorInt int dark) {
        return contrastRatio(background, dark) >= contrastRatio(background, light) ? dark : light;
    }

    /** W3C 对比度：(较亮 + 0.05) / (较暗 + 0.05)，取值 1..21。 */
    private static double contrastRatio(@ColorInt int a, @ColorInt int b) {
        double la = relativeLuminance(a);
        double lb = relativeLuminance(b);
        double hi = Math.max(la, lb);
        double lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    /** WCAG 相对亮度：先反伽马到线性空间，再按 0.2126/0.7152/0.0722 加权。 */
    private static double relativeLuminance(@ColorInt int color) {
        double r = channel((color >> 16) & 0xFF);
        double g = channel((color >> 8) & 0xFF);
        double b = channel(color & 0xFF);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(int value) {
        double v = value / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }
}
