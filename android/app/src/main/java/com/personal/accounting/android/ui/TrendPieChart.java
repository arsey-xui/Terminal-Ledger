package com.personal.accounting.android.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;

import com.github.mikephil.charting.charts.PieChart;

/**
 * 支出构成环形图：空状态占位支持多行（见 {@link NoDataTip}）。
 *
 * <p>库的 {@code Chart} 用单次 {@code drawText} 画占位文字，不解析换行符，
 * 所以文案里的换行必须由我们自己逐行画。</p>
 */
public class TrendPieChart extends PieChart {

    private final NoDataTip noDataTip = new NoDataTip(this);

    public TrendPieChart(Context context) {
        super(context);
    }

    public TrendPieChart(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public TrendPieChart(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    /**
     * 空状态占位文案，支持 {@code '\n'} 换行。
     *
     * <p>库的 {@code Chart} 是单次 {@code drawText}，不解析换行符，所以文案交给
     * {@link NoDataTip} 逐行画；同时把库那份置空，免得它再画一遍单行文字叠在上面。
     * 置空必须走 {@code super}：走 {@code setNoDataText} 会回到本方法，形成无限递归。</p>
     */
    @Override
    public void setNoDataText(String text) {
        super.setNoDataText("");
        noDataTip.setText(text);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        noDataTip.draw(canvas);
    }
}
