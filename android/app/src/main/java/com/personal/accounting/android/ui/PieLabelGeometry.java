package com.personal.accounting.android.ui;

/**
 * 环形图环外标签的布点几何：纯函数，不依赖 Android，可在 JVM 上直接测。
 *
 * <p>为什么单独抽出来：这段数学出过两个已经发出去的缺陷 ——
 * 标签被纵向推开后压在环上（文字陷进图里）、以及同侧每条标签各算各的引线折点
 * 导致竖直引线横穿别的标签文字（截图里「43元」被一条竖线穿过）。两者都是纯几何，
 * 混在渲染器里就只能靠真机看。</p>
 *
 * <p>渲染器只负责把画布度量（文字高度、dp 间距、视图范围）算成这里的入参，然后按返回值画。</p>
 */
public final class PieLabelGeometry {

    private PieLabelGeometry() {
    }

    /**
     * 圆环在 {@code [spanTop, spanBottom]} 这段纵向范围里，最靠某一侧的外沿 x。
     *
     * <p>圆的左右边缘在圆心那一行最靠外、离圆心越远越往里收，所以只取区间内离圆心
     * 最近的那一行来算。区间整个落在圆外时按圆心算，等价于「这一行没有环」。</p>
     *
     * @param right true 取右边缘，false 取左边缘
     */
    public static float ringEdgeX(float centerX, float centerY, float radius,
                                  float spanTop, float spanBottom, boolean right) {
        float y = Math.max(spanTop, Math.min(spanBottom, centerY));
        float dy = y - centerY;
        float squared = radius * radius - dy * dy;
        float reach = squared > 0f ? (float) Math.sqrt(squared) : 0f;
        return right ? centerX + reach : centerX - reach;
    }

    /**
     * 同一侧标签的纵向排布：算每块文字块的顶部 y，保证相邻两块至少隔开 {@code blockH + gapY}。
     *
     * <p>输入必须按折点 y **升序**排好（渲染器先排序）。只向下推：某一块被夹到视图顶部时
     * 变得更靠上，下一块的紧邻下界是在它**最终**位置的基础上算的，间距仍然成立。</p>
     *
     * @param foldY     每块标签引线折点的 y（升序）
     * @param pointsUp  该标签是否在上半圆（折点高于扇区锚点，文字块向上长）
     * @param blockH    两行文字块的高度
     * @param gapY      相邻两块之间的最小空隙
     * @param minY/maxY 视图上下边界
     * @return 每块文字块的顶部 y，与入参同序
     */
    public static float[] textTops(float[] foldY, boolean[] pointsUp,
                                   float blockH, float gapY, float minY, float maxY) {
        float[] tops = new float[foldY.length];
        for (int i = 0; i < foldY.length; i++) {
            float ideal;
            if (pointsUp[i]) {
                ideal = foldY[i] - blockH;                 // 上半圆：块底贴着折点，向上长
            } else {
                // 下半圆默认向下长；底部放不下就翻到折点上方 —— 与其画出图外，不如换个方向
                ideal = (foldY[i] + blockH <= maxY) ? foldY[i] : foldY[i] - blockH;
            }
            if (i > 0) {
                ideal = Math.max(ideal, tops[i - 1] + blockH + gapY);
            }
            tops[i] = Math.max(ideal, minY);
        }
        return tops;
    }

    /**
     * 同一侧共用的引线走廊 x。
     *
     * <p>每侧只留一条：所有竖段共线、文字一律在走廊外侧，引线就不可能穿过任何文字。
     * 各算各的时候，靠圆心高度的标签被推得更外，它的竖段会横穿另一条标签的字。</p>
     *
     * @param requiredEdgeX 该侧每块标签要求的最小外沿（环边 ± 间距），非空
     * @param right         true 为右侧
     * @param widestText    该侧最宽的文字宽度，用于保证文字仍在视图内
     */
    public static float corridorX(float[] requiredEdgeX, boolean right,
                                  float gap, float widestText,
                                  float viewLeft, float viewRight) {
        if (requiredEdgeX.length == 0) return Float.NaN;
        if (right) {
            float edge = requiredEdgeX[0];
            for (float v : requiredEdgeX) edge = Math.max(edge, v);
            // 文字必须在视图内：冲突（视图太窄）时宁可让文字贴住环，也不把字画到图外
            return Math.min(edge, viewRight - gap - widestText);
        }
        float edge = requiredEdgeX[0];
        for (float v : requiredEdgeX) edge = Math.min(edge, v);
        return Math.max(edge, viewLeft + gap + widestText);
    }
}
