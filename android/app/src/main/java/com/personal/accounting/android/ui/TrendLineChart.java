package com.personal.accounting.android.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.highlight.Highlight;

/**
 * 近 30 天折线图：数值气泡自己画在画布上，空状态占位支持多行。
 *
 * <p>气泡的定位与绘制在 {@link TipBubble}（与柱状图共用一套规则），
 * 空状态多行文案在 {@link NoDataTip}。</p>
 */
public class TrendLineChart extends LineChart {

    private final NoDataTip noDataTip = new NoDataTip(this);
    private TipBubble tipBubble;

    /** 气泡要显示的文字；为空表示当前没有选中点。 */
    private String tipText;

    public TrendLineChart(Context context) {
        super(context);
        initTipBubble();
    }

    public TrendLineChart(Context context, AttributeSet attrs) {
        super(context, attrs);
        initTipBubble();
    }

    public TrendLineChart(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initTipBubble();
    }

    /** 放在构造器里而不是字段初始化：这时 context 一定已挂上，取密度最稳。 */
    private void initTipBubble() {
        tipBubble = new TipBubble(getResources().getDisplayMetrics().density);
    }

    /**
     * 空状态占位文案，支持 {@code '\n'} 换行。
     *
     * <p>必须重写：库的 {@code Chart} 是单次 {@code drawText}，不解析换行符，
     * 所以把文案交给 {@link NoDataTip} 逐行画；同时把库那份置空，免得叠一遍单行文字。
     * 置空走 {@code super}：走 {@code setNoDataText} 会回到本方法，形成无限递归。</p>
     */
    @Override
    public void setNoDataText(String text) {
        super.setNoDataText("");
        noDataTip.setText(text);
    }

    /** 设置要显示的文字；传 null 或空串表示隐藏气泡。 */
    public void setTipText(String text) {
        this.tipText = text;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        noDataTip.draw(canvas);
        if (tipText == null || tipText.isEmpty()) return;

        Highlight[] highlighted = getHighlighted();
        if (highlighted == null || highlighted.length == 0) return;
        // 与库里定位 marker 用的是同一个方法：返回画布坐标
        float[] pos = getMarkerPosition(highlighted[0]);
        if (pos == null || pos.length < 2) return;

        tipBubble.draw(canvas, getViewPortHandler(), tipText, pos[0], pos[1]);
    }
}
