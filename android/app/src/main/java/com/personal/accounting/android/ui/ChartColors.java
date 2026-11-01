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
     * 给 count 个扇区分配颜色，保证**相邻扇区（含首尾）颜色不同**。
     *
     * <p>直接 {@code palette[i % length]} 有个坑：扇区数超过颜色数时，最后一块会拿回第一块的颜色，
     * 而环是首尾相接的 —— 那两块正好并排贴在一起，完全分不出来。真实撞过的例子是 7 个分类时的
     * 「医疗」（最小、最后一块）和「餐饮」（最大、第一块）都是同一个酸橙绿。</p>
     *
     * <p>颜色数不够时必然有重复，但只会落在不相邻的位置上。</p>
     */
    @ColorInt
    static int[] pieColors(@NonNull Context context, int count) {
        int[] palette = piePalette(context);
        int[] colors = new int[count];
        for (int i = 0; i < count; i++) {
            int index = i % palette.length;
            if (i > 0 && palette[index] == colors[i - 1]) {
                index = (index + 1) % palette.length;
            }
            colors[i] = palette[index];
        }
        // 首尾相接：最后一块和第一块撞色时退到下一个候选色，别在 12 点方向并排出现两块同色
        if (count > 1 && colors[count - 1] == colors[0]) {
            for (int step = 1; step < palette.length; step++) {
                int candidate = palette[(count - 1 + step) % palette.length];
                if (candidate != colors[0] && candidate != colors[count - 2]) {
                    colors[count - 1] = candidate;
                    break;
                }
            }
        }
        return colors;
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
