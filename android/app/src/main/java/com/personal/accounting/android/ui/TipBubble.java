package com.personal.accounting.android.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.github.mikephil.charting.utils.ViewPortHandler;

/**
 * 图表上「点选数据点后弹出的数值气泡」的绘制。
 *
 * <p>折线图和柱状图共用这一套，所以位置规则只有一份实现：
 * 水平居中在数据点上方、留 6dp 间距；左右越界时整体平移进绘图区；
 * 图顶确实放不下时翻到数据点下方。</p>
 *
 * <p>不依赖 {@code MarkerView}：那套定位要经过「View 测量/布局 → getOffset() →
 * getOffsetForDrawingAtPoint() → 基类边界收敛」四层，而气泡宽度随文字变化，
 * 任一层拿到旧宽度就会算错、气泡边缘伸到绘图区外被裁。这里一次算完，没有中间状态。</p>
 */
class TipBubble {

    private static final float GAP_DP = 6f;
    private static final float PAD_H_DP = 8f;
    private static final float PAD_V_DP = 4f;
    private static final float TEXT_SIZE_DP = 12f;

    private final Paint background = new Paint();
    private final Paint text = new Paint();
    private final Paint.FontMetrics metrics = new Paint.FontMetrics();
    private final float density;

    TipBubble(float density) {
        this.density = density;
        // 深色半透明底 + 白字：深浅两个基底上都成立，所以不跟随主题
        background.setColor(0xDD000000);
        text.setColor(0xFFFFFFFF);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(TEXT_SIZE_DP * density);
    }

    /**
     * 在数据点上方画气泡。
     *
     * @param posX 数据点的画布 x（来自 {@code Chart.getMarkerPosition()}）
     * @param posY 数据点的画布 y
     */
    void draw(Canvas canvas, ViewPortHandler viewPort, String label, float posX, float posY) {
        float gap = GAP_DP * density;
        float padH = PAD_H_DP * density;
        float padV = PAD_V_DP * density;

        // 基线用 FontMetrics 算，不依赖 getTextBounds：
        // 后者在不同字体/缩放下给出的 top 不直观，容易把文字推到气泡外。
        text.getFontMetrics(metrics);
        float boxW = text.measureText(label) + padH * 2f;
        float boxH = (metrics.descent - metrics.ascent) + padV * 2f;

        float left = Math.round(posX - boxW / 2f);
        float top = Math.round(posY - gap - boxH);

        // 水平：夹进绘图区，左右都不越界
        if (left + boxW > viewPort.contentRight()) left = viewPort.contentRight() - boxW;
        if (left < viewPort.contentLeft()) left = viewPort.contentLeft();

        // 垂直：上方放不下就翻到数据点下方
        if (top < 0f) top = Math.round(posY + gap);

        int save = canvas.save();
        canvas.drawRect(left, top, left + boxW, top + boxH, background);
        canvas.drawText(label, left + padH, top + padV - metrics.ascent, text);
        canvas.restoreToCount(save);
    }
}
