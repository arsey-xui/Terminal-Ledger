package com.personal.accounting.android.ui;

import android.graphics.Canvas;
import android.graphics.Paint;

import com.github.mikephil.charting.charts.Chart;
import com.github.mikephil.charting.utils.MPPointF;

/**
 * 让图表上的「空状态占位文字」支持多行。
 *
 * <p>为什么要自己做：MPAndroidChart v3.1.0 的 {@code Chart.onDraw()} 是用**单次
 * {@code drawText}** 画占位文字的，所以字符串里的换行符不会换行
 * （它不像 TextView / JavaFX Label 会解析换行）。把换行符换成逗号只会得到更长的一行。</p>
 *
 * <p>这里按换行符手动切成多行、逐行居中绘制。于是文案里写换行符就真的换行，
 * 断行位置由文案自己决定，不依赖自动折行。</p>
 *
 * <p>做成辅助类而不是基类：{@code Chart} 那条继承链上，折线图、柱状图、饼图各需要不同的
 * API（坐标轴 / 扇区），没法共用一个中间基类。三个图表各自继承原本的类，
 * 在这里共用同一份多行绘制逻辑。</p>
 *
 * <p>两个细节：{@code Chart.mNoDataText} 是**私有字段且没有 getter**，所以这里自己存一份；
 * 库里那个 {@code mInfoPaint} 是内部共享对象，改它会影响别的图表，所以这里用自己的 paint。</p>
 */
public class NoDataTip {

    /** 图表没数据时的兜底字号（sp），与 {@code shrinkNoDataText} 保持一致。 */
    private static final float FALLBACK_TEXT_SP = 11f;

    private final Chart<?> chart;
    private final Paint paint = new Paint();

    /** 自己记一份文案，因为 Chart 的 mNoDataText 拿不到。 */
    private String text;

    public NoDataTip(Chart<?> chart) {
        this.chart = chart;
    }

    /**
     * 设置占位文案，支持用 {@code '\n'} 换行。
     *
     * <p><b>注意</b>：调用方（图表子类）必须同时把库那份文案置空
     * （在重写的 {@code setNoDataText} 里调 {@code super.setNoDataText("")}），
     * 否则 {@code Chart.onDraw()} 会用库那份再画一遍单行文字，和我们画的多行文字叠在一起。</p>
     *
     * <p>这里**不能**替调用方去调 {@code setNoDataText("")}：那个方法在子类里被重写了，
     * 会转回本方法，形成无限递归（真机上表现为 {@code StackOverflowError} 闪退）。</p>
     */
    public void setText(String value) {
        // 幂等：重复设同一份文案直接返回。绘制期间会被反复调用，
        // 同时也能挡住「重写方法回调自己」这类意外递归。
        if (value == null ? this.text == null : value.equals(this.text)) return;
        this.text = value;
    }

    /** 画在 {@code super.onDraw()} 之后：数据为空时库不会走渲染器，这段文字由我们接管。 */
    public void draw(Canvas canvas) {
        if (text == null || text.isEmpty()) return;
        if (chart.getData() != null && chart.getData().getEntryCount() > 0) return;

        // 字号与颜色由调用方设置（setNoDataTextColor / shrinkNoDataText），沿用库那个 paint 的取值，
        // 保证这些设置仍然生效；拿不到时给一个兜底字号。
        Paint source = chart.getPaint(Chart.PAINT_INFO);
        if (source != null) {
            paint.set(source);
        } else {
            paint.setTextSize(FALLBACK_TEXT_SP * chart.getResources().getDisplayMetrics().scaledDensity);
        }
        paint.setTextAlign(Paint.Align.CENTER);

        Paint.FontMetrics fm = paint.getFontMetrics();
        float lineHeight = fm.descent - fm.ascent;
        String[] lines = text.split("\n", -1);

        // 居中点与库一致（Chart.onDraw 用的就是 getCenter()）
        MPPointF center = chart.getCenter();
        float blockTop = center.y - (lineHeight * lines.length) / 2f;
        float baseline = blockTop - fm.ascent;
        for (String line : lines) {
            canvas.drawText(line, center.x, baseline, paint);
            baseline += lineHeight;
        }
    }
}
