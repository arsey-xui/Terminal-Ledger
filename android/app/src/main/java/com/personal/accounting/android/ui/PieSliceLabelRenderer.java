package com.personal.accounting.android.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import com.github.mikephil.charting.animation.ChartAnimator;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.interfaces.datasets.IPieDataSet;
import com.github.mikephil.charting.renderer.PieChartRenderer;
import com.github.mikephil.charting.utils.MPPointF;
import com.github.mikephil.charting.utils.Utils;
import com.github.mikephil.charting.utils.ViewPortHandler;

import java.util.List;
import java.util.Locale;

/**
 * 环形图标签渲染器：标签画在**每个扇区的正中间、水平不旋转**，两行文字。
 *
 * <p>第一行金额：黑色、加粗、带「元」；第二行分类名：白色。扇区可能很窄，
 * 所以会按扇区在标签半径处的可用宽度自动缩字号（有下限），仍然放不下时才退回环外，
 * 以保证文字始终压在扇区里而不是被洞/外缘裁掉。MPAndroidChart 默认实现只能给整个
 * 数据集设一个统一位置，所以这里接管标签绘制。</p>
 */
public class PieSliceLabelRenderer extends PieChartRenderer {

    /** 占比大于该值的扇区，标签默认画在环内；以下的小扇区直接画在环外。 */
    private final float minPercentInside;

    /** 整个标签块的缩放下限：再挤也不小于基准字号的这个比例，避免出现看不清的小字。 */
    private static final float MIN_TEXT_SCALE = 0.72f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 白色分类名压在浅色扇区上会看不清，用一圈深色描边兜底。 */
    private final Paint nameHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float amountSizePx;
    private final float nameSizePx;
    private final int amountColor;
    private final int nameColor;

    public PieSliceLabelRenderer(PieChart chart, ViewPortHandler viewPortHandler, ChartAnimator animator,
                                 float minPercentInside, float amountSizePx, float nameSizePx,
                                 int amountColor, int nameColor, int lineColor) {
        super(chart, animator, viewPortHandler);
        this.minPercentInside = minPercentInside;
        this.amountSizePx = amountSizePx;
        this.nameSizePx = nameSizePx;
        this.amountColor = amountColor;
        this.nameColor = nameColor;
        paint.setTextAlign(Paint.Align.CENTER);
        nameHaloPaint.setTextAlign(Paint.Align.CENTER);
        nameHaloPaint.setStyle(Paint.Style.STROKE);
        nameHaloPaint.setStrokeWidth(Utils.convertDpToPixel(2.5f));
        nameHaloPaint.setColor(Color.parseColor("#66000000"));
        linePaint.setColor(lineColor);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(Utils.convertDpToPixel(1f));
    }

    @Override
    public void drawValues(Canvas c) {
        // 默认的数值/标签绘制全部跳过，改由本类按扇区分别处理
    }

    @Override
    public void drawExtras(Canvas c) {
        super.drawExtras(c);
        drawSliceLabels(c);
    }

    private void drawSliceLabels(Canvas c) {
        PieData data = mChart.getData();
        if (data == null) return;
        IPieDataSet rawSet = data.getDataSet();
        if (!(rawSet instanceof PieDataSet)) return;
        PieDataSet set = (PieDataSet) rawSet;
        if (!set.isDrawValuesEnabled()) return;

        List<PieEntry> entries = set.getValues();
        if (entries == null || entries.isEmpty()) return;
        float total = 0f;
        for (PieEntry e : entries) total += e.getValue();
        if (total <= 0) return;

        MPPointF center = mChart.getCenterCircleBox();
        float radius = mChart.getRadius();
        float holeRadius = radius / 100f * mChart.getHoleRadius();
        float sliceAngle = 360f / total;
        float currentAngle = mChart.getRotationAngle();
        float safeRadius = radius - Utils.convertDpToPixel(4f);

        for (int i = 0; i < entries.size(); i++) {
            PieEntry entry = entries.get(i);
            float sweep = sliceAngle * entry.getValue();
            float midAngle = currentAngle + sweep / 2f;
            currentAngle += sweep;

            double rad = Math.toRadians(midAngle);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);
            String amountText = formatAmount(entry.getValue());
            String nameText = labelOf(entry);
            boolean bigSlice = entry.getValue() / total * 100f > minPercentInside;

            if (bigSlice && drawInside(c, center, radius, holeRadius, safeRadius, sweep, cos, sin,
                    amountText, nameText)) {
                continue;
            }
            drawOutside(c, center, radius, cos, sin, midAngle, amountText, nameText);
        }
        MPPointF.recycleInstance(center);
    }

    /**
     * 环内两行标签：第一行金额（黑字带元）、第二行分类名（白字），
     * 整体在环带中间半径处水平居中；扇区窄时整块缩字号，仍放不下才返回 false。
     */
    private boolean drawInside(Canvas c, MPPointF center, float radius, float holeRadius, float safeRadius,
                               float sweep, float cos, float sin, String amount, String name) {
        float labelRadius = (radius + holeRadius) / 2f;
        float bandWidth = radius - holeRadius;
        float lineHeight = lineHeight(amountSizePx);
        float textHeight = lineHeight * 2f;
        if (textHeight > bandWidth) return false;                // 两行高度本身就超过环带宽度

        // 找到能塞进扇区的最大字号：不缩最好，缩到下限还不行就移出环外
        float scale = MIN_TEXT_SCALE;
        for (float s = 1f; s >= MIN_TEXT_SCALE - 0.001f; s -= 0.04f) {
            float aSize = amountSizePx * s;
            float nSize = nameSizePx * s;
            float width = Math.max(measure(amount, aSize), measure(name, nSize));
            if (fitsAt(center, safeRadius, labelRadius, holeRadius, cos, sin, sweep,
                    width, lineHeight(aSize) * 2f)) {
                scale = s;
                break;
            }
        }
        float amountSize = amountSizePx * scale;
        float nameSize = nameSizePx * scale;
        float width = Math.max(measure(amount, amountSize), measure(name, nameSize));
        if (!fitsAt(center, safeRadius, labelRadius, holeRadius, cos, sin, sweep,
                width, lineHeight(amountSize) * 2f)) {
            return false;
        }

        float x = center.x + labelRadius * cos;
        float y = center.y + labelRadius * sin;
        float line = lineHeight(amountSize);
        float firstBaseline = y - line / 2f;

        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setColor(amountColor);
        paint.setTextSize(amountSize);
        c.drawText(amount, x, firstBaseline, paint);

        nameHaloPaint.setTextSize(nameSize);
        c.drawText(name, x, firstBaseline + line, nameHaloPaint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setColor(nameColor);
        paint.setTextSize(nameSize);
        c.drawText(name, x, firstBaseline + line, paint);
        return true;
    }

    /**
     * 环内标签是否放得下：按**扇区在标签半径处的可用宽度**算（不按外接矩形，
     * 那对环形图过于乐观，会让文字压到洞或外缘被裁）。
     */
    private boolean fitsAt(MPPointF center, float safeRadius, float labelRadius, float holeRadius,
                           float cos, float sin, float sweep, float textWidth, float textHeight) {
        // 径向：文字块高度要落在环带内（内外各留 1dp 余量）
        float bandHalf = Math.min(labelRadius - holeRadius, safeRadius - labelRadius)
                - Utils.convertDpToPixel(1f);
        if (bandHalf <= 0f || textHeight / 2f > bandHalf) return false;

        // 切向：扇区在文字左右两端的弦长要容得下文字宽度
        double halfSweep = Math.toRadians(sweep / 2f);
        if (labelRadius * Math.sin(halfSweep) < textWidth / 2f * 1.15f) return false;

        // 文字四角都必须在扇区范围内：文字水平画，径向偏移来自上下边（sin），切向来自左右边（cos）
        float halfWidth = textWidth / 2f;
        float halfHeight = textHeight / 2f;
        float radialSpread = halfHeight * Math.abs(sin) + halfWidth * Math.abs(cos);
        float tangentialSpread = halfWidth * Math.abs(sin) + halfHeight * Math.abs(cos);
        if (labelRadius - radialSpread < holeRadius) return false;
        if (labelRadius + radialSpread > safeRadius) return false;
        if (Math.abs(Math.atan2(tangentialSpread, Math.max(labelRadius - radialSpread, 1f))) > halfSweep) {
            return false;
        }
        return true;
    }

    /** 兜底：极窄扇区真的放不下时，才用引线把同款两行标签画到环外。 */
    private void drawOutside(Canvas c, MPPointF center, float radius,
                             float cos, float sin, float midAngle,
                             String amount, String name) {
        float x1 = center.x + radius * cos;
        float y1 = center.y + radius * sin;
        float leader = Utils.convertDpToPixel(12f);
        float x2 = x1 + leader * cos;
        float y2 = y1 + leader * sin;
        c.drawLine(x1, y1, x2, y2, linePaint);

        float gap = Utils.convertDpToPixel(4f);
        float amountSize = amountSizePx;
        float nameSize = nameSizePx;
        float textWidth = Math.max(measure(amount, amountSize), measure(name, nameSize));
        float left = mViewPortHandler.contentLeft();
        float right = mViewPortHandler.contentRight();

        float x;
        if (cos >= 0) {
            paint.setTextAlign(Paint.Align.LEFT);
            nameHaloPaint.setTextAlign(Paint.Align.LEFT);
            x = x2 + gap;
            if (x + textWidth > right) {                    // 右侧放不下 -> 翻到引线左边
                paint.setTextAlign(Paint.Align.RIGHT);
                nameHaloPaint.setTextAlign(Paint.Align.RIGHT);
                x = x2 - gap;
            }
        } else {
            paint.setTextAlign(Paint.Align.RIGHT);
            nameHaloPaint.setTextAlign(Paint.Align.RIGHT);
            x = x2 - gap;
            if (x - textWidth < left) {                     // 左侧放不下 -> 翻到引线右边
                paint.setTextAlign(Paint.Align.LEFT);
                nameHaloPaint.setTextAlign(Paint.Align.LEFT);
                x = x2 + gap;
            }
        }

        float line = lineHeight(amountSize);
        // 上半圆文字放在锚点上方、下半圆放在下方，减少相邻标签互撞
        float firstBaseline = (midAngle > 180f) ? y2 - line : y2 + amountSize;

        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setColor(amountColor);
        paint.setTextSize(amountSize);
        c.drawText(amount, x, firstBaseline, paint);
        nameHaloPaint.setTextSize(nameSize);
        c.drawText(name, x, firstBaseline + line, nameHaloPaint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setColor(nameColor);
        paint.setTextSize(nameSize);
        c.drawText(name, x, firstBaseline + line, paint);

        paint.setTextAlign(Paint.Align.CENTER);
        nameHaloPaint.setTextAlign(Paint.Align.CENTER);
    }

    private float measure(String text, float textSize) {
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(textSize);
        return paint.measureText(text);
    }

    private float lineHeight(float textSize) {
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(textSize);
        return (paint.descent() - paint.ascent()) * 1.05f;
    }

    /** 金额文本：取整到元，带「元」后缀。 */
    private static String formatAmount(float value) {
        return String.format(Locale.CHINA, "%.0f元", value);
    }

    private static String labelOf(PieEntry entry) {
        String label = entry.getLabel();
        return (label == null || label.isEmpty()) ? "未分类" : label;
    }
}
