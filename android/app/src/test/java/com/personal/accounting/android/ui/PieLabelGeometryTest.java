package com.personal.accounting.android.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 环形图环外标签的布点几何。
 *
 * <p>钉住两条已经发出去过的缺陷：</p>
 * <ul>
 *   <li>标签被纵向推开后压在环上（文字陷进图里、看不清）；</li>
 *   <li>同一侧每条标签各算各的折点 x，导致某条的竖段横穿另一条的文字（截图里「43元」被竖线穿过）。</li>
 * </ul>
 *
 * <p>用的是界面里真实出现过的那组比例：7 个分类里 4 个小扇区的标签走到环外，挤在左上角。</p>
 */
class PieLabelGeometryTest {

    /** 与截图同一套比例：画布 720 宽，圆心 (360,400)，半径 180。 */
    private static final float CENTER_X = 360f;
    private static final float CENTER_Y = 400f;
    private static final float RADIUS = 180f;
    private static final float VIEW_LEFT = 2f;
    private static final float VIEW_RIGHT = 718f;
    private static final float BLOCK_H = 34f;      // 两行文字块高度
    private static final float GAP_Y = 3f;         // 相邻两块最小空隙
    private static final float GAP = 4f;           // 引线与文字之间
    private static final float RING_GAP = 6f;      // 环与折点之间
    private static final float WIDEST_TEXT = 70f;  // 「406元」这类文字的宽度

    /**
     * 四个环外标签的引线方向（cos, sin），取自真实数据里那四个小扇区的中角：
     * 住房 317.9°、交通 338.8°、通讯 350.4°、医疗 357.6°（自 12 点顺时针）。
     */
    private static final float[][] DIRECTIONS = {
            {-0.670f, -0.742f},   // 住房
            {-0.363f, -0.932f},   // 交通
            {-0.167f, -0.986f},   // 通讯
            {-0.042f, -0.999f},   // 医疗
    };

    /** 按渲染器的方式算出折点：锚点 = 圆心 + 半径*方向，折点再沿方向外推 12dp。 */
    private static float[][] foldPoints() {
        float[][] folds = new float[DIRECTIONS.length][2];
        for (int i = 0; i < DIRECTIONS.length; i++) {
            float cos = DIRECTIONS[i][0];
            float sin = DIRECTIONS[i][1];
            float anchorX = CENTER_X + RADIUS * cos;
            float anchorY = CENTER_Y + RADIUS * sin;
            folds[i][0] = anchorX + 12f * cos;
            folds[i][1] = anchorY + 12f * sin;
        }
        return folds;
    }

    /** 按折点 y 升序排（渲染器就是这么排的），返回 [foldX, foldY, anchorY] 三条并行数组。 */
    private static float[][] sortedFolds() {
        float[][] folds = foldPoints();
        java.util.Arrays.sort(folds, (a, b) -> Float.compare(a[1], b[1]));
        return folds;
    }

    @Test
    void textBlocksNeverOverlapTheRing() {
        float[][] folds = sortedFolds();
        float[] foldY = new float[folds.length];
        boolean[] pointsUp = new boolean[folds.length];
        for (int i = 0; i < folds.length; i++) {
            foldY[i] = folds[i][1];
            // 上半圆：折点比扇区锚点更靠上（DIRECTIONS 里 4 条都如此）
            pointsUp[i] = true;
        }
        float[] tops = PieLabelGeometry.textTops(foldY, pointsUp, BLOCK_H, GAP_Y, 2f, 798f);

        // 同侧共用走廊：取所有标签要求的环外沿里最外侧的那个
        float[] required = new float[folds.length];
        for (int i = 0; i < folds.length; i++) {
            float spanTop = Math.min(foldY[i], tops[i]);
            float spanBottom = Math.max(foldY[i], tops[i] + BLOCK_H);
            required[i] = PieLabelGeometry.ringEdgeX(CENTER_X, CENTER_Y, RADIUS,
                    spanTop, spanBottom, false) - RING_GAP;
        }
        float corridor = PieLabelGeometry.corridorX(required, false, GAP, WIDEST_TEXT, VIEW_LEFT, VIEW_RIGHT);
        float textRight = corridor - GAP;                  // 左侧标签右对齐于走廊内侧

        for (int i = 0; i < folds.length; i++) {
            // 文字块自己那一行上的环外沿：文字右边缘必须落在它左边，否则文字压在环上
            float ringEdgeAtText = PieLabelGeometry.ringEdgeX(CENTER_X, CENTER_Y, RADIUS,
                    tops[i], tops[i] + BLOCK_H, false);
            assertTrue(textRight < ringEdgeAtText,
                    "第 " + i + " 块文字右边缘 " + textRight + " 压在环上（该行环外沿 " + ringEdgeAtText + "）");
        }
    }

    @Test
    void neighbouringBlocksKeepAMinimumGap() {
        float[][] folds = sortedFolds();
        float[] foldY = new float[folds.length];
        boolean[] pointsUp = new boolean[folds.length];
        java.util.Arrays.fill(pointsUp, true);
        for (int i = 0; i < folds.length; i++) foldY[i] = folds[i][1];

        float[] tops = PieLabelGeometry.textTops(foldY, pointsUp, BLOCK_H, GAP_Y, 2f, 798f);
        for (int i = 1; i < tops.length; i++) {
            float gap = tops[i] - (tops[i - 1] + BLOCK_H);
            assertTrue(gap >= GAP_Y,
                    "第 " + i + " 块与上一块的行距 " + gap + " 小于最小空隙 " + GAP_Y);
        }
    }

    @Test
    void leadersStayOnTheRingSideOfTheSharedCorridor() {
        float[][] folds = sortedFolds();
        float[] foldY = new float[folds.length];
        boolean[] pointsUp = new boolean[folds.length];
        java.util.Arrays.fill(pointsUp, true);
        for (int i = 0; i < folds.length; i++) foldY[i] = folds[i][1];
        float[] tops = PieLabelGeometry.textTops(foldY, pointsUp, BLOCK_H, GAP_Y, 2f, 798f);

        // 与渲染器一致：要求的外沿按「折点 + 文字块」这段纵向范围里环的最外沿算，
        // 只看折点那一行会漏掉文字块更靠近圆心高度的那几行。
        float[] required = new float[folds.length];
        for (int i = 0; i < folds.length; i++) {
            float spanTop = Math.min(foldY[i], tops[i]);
            float spanBottom = Math.max(foldY[i], tops[i] + BLOCK_H);
            required[i] = PieLabelGeometry.ringEdgeX(CENTER_X, CENTER_Y, RADIUS,
                    spanTop, spanBottom, false) - RING_GAP;
        }
        float corridor = PieLabelGeometry.corridorX(required, false, GAP, WIDEST_TEXT, VIEW_LEFT, VIEW_RIGHT);

        // 斜段从扇区锚点收到走廊上：只要每个锚点都在走廊右侧，斜段就永远不会越到文字那一侧，
        // 也就不可能穿过任何一块文字 —— 这正是「竖段横穿 43元」那个缺陷的判据。
        for (int i = 0; i < DIRECTIONS.length; i++) {
            float anchorX = CENTER_X + RADIUS * DIRECTIONS[i][0];
            assertTrue(anchorX > corridor,
                    "第 " + i + " 条引线的锚点 " + anchorX + " 已在走廊 " + corridor + " 左侧");
        }
    }

    @Test
    void corridorIsAtLeastAsFarOutAsEveryLabelAsks() {
        // 左侧：x 越小越靠外。三个差异很大的要求，走廊必须取最外侧（最小）的那个。
        float[] required = {120f, 40f, 95f};
        float corridor = PieLabelGeometry.corridorX(required, false, GAP, 10f, VIEW_LEFT, VIEW_RIGHT);
        for (float r : required) {
            assertTrue(corridor <= r, "走廊 " + corridor + " 比某个标签要求的 " + r + " 更靠内");
        }
        assertEquals(40f, corridor, 0.001f);
    }

    @Test
    void narrowViewPushesTheCorridorBackInsideInsteadOfDrawingOffScreen() {
        // 文字比可用空白还宽：宁可让文字贴住环，也不能画到视图外
        float corridor = PieLabelGeometry.corridorX(new float[]{-200f}, false, GAP, 300f, VIEW_LEFT, VIEW_RIGHT);
        assertEquals(VIEW_LEFT + GAP + 300f, corridor, 0.001f);
    }
}
