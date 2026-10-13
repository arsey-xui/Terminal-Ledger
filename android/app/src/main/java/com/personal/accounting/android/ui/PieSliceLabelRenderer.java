package com.personal.accounting.android.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 环形图标签渲染器：标签画在**每个扇区的正中间、水平不旋转**，两行文字。
 *
 * <p>第一行金额（加粗、带「元」）、第二行分类名。扇区可能很窄，所以会按扇区在标签半径处的
 * 可用宽度自动缩字号（有下限），仍然放不下时才退回环外，保证文字始终压在扇区里而不是被洞/外缘裁掉。
 * MPAndroidChart 默认实现只能给整个数据集设一个统一位置，所以这里接管标签绘制。</p>
 *
 * <p><b>标签颜色不写死</b>：浅色/深色两套饼图调色板共用本渲染器，每个扇区画之前按该扇区的
 * 实际颜色选反色（{@link ChartColors#contrastOn}）—— 浅色扇区用深字、深色扇区用浅字，
 * 金额与分类名两行取同一个反色，避免「一行黑一行白」的花脸。</p>
 */
public class PieSliceLabelRenderer extends PieChartRenderer {

    /** 占比大于该值的扇区，标签默认画在环内；以下的小扇区直接画在环外。 */
    private final float minPercentInside;

    /** 整个标签块的缩放下限：再挤也不小于基准字号的这个比例，避免出现看不清的小字。 */
    private static final float MIN_TEXT_SCALE = 0.72f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 分类名的描边：压在任意底色上都要有轮廓，颜色随反色切换。 */
    private final Paint nameHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float amountSizePx;
    private final float nameSizePx;
    /** 深色底上用的浅标签色（例如 #FFFFFF）。 */
    private final int lightLabelColor;
    /** 浅色底上用的深标签色（例如 #000000）。 */
    private final int darkLabelColor;
    private final int haloColorLight;
    private final int haloColorDark;
    /** 图表所处的页面底色：环外标签压在它上面，文字反色按它算。 */
    private final int labelBackgroundColor;
    /** 当前扇区选定的标签反色，每个扇区绘制前由 {@link #applySliceColors} 更新。 */
    private int currentLabelColor;

    public PieSliceLabelRenderer(PieChart chart, ViewPortHandler viewPortHandler, ChartAnimator animator,
                                 float minPercentInside, float amountSizePx, float nameSizePx,
                                 int lightLabelColor, int darkLabelColor,
                                 int haloColorLight, int haloColorDark, int lineColor,
                                 int labelBackgroundColor) {
        super(chart, animator, viewPortHandler);
        this.minPercentInside = minPercentInside;
        this.amountSizePx = amountSizePx;
        this.nameSizePx = nameSizePx;
        this.lightLabelColor = lightLabelColor;
        this.darkLabelColor = darkLabelColor;
        this.haloColorLight = haloColorLight;
        this.haloColorDark = haloColorDark;
        this.labelBackgroundColor = labelBackgroundColor;
        this.currentLabelColor = darkLabelColor;
        paint.setTextAlign(Paint.Align.CENTER);
        nameHaloPaint.setTextAlign(Paint.Align.CENTER);
        nameHaloPaint.setStyle(Paint.Style.STROKE);
        nameHaloPaint.setStrokeWidth(Utils.convertDpToPixel(2.5f));
        nameHaloPaint.setColor(haloColorDark);
        linePaint.setColor(lineColor);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(Utils.convertDpToPixel(1f));
    }

    /**
     * 按扇区颜色挑标签反色：在给定的深浅两个候选色里选对比度更高的那个。
     *
     * <p>两行标签用同一个反色 —— 金额与分类名是压在同一种底色上的两行字，
     * 分开取反色会出现「一行黑一行白」的花脸。</p>
     */
    private void applySliceColors(PieDataSet set, int index) {
        int sliceColor = set.getColor(index);
        // 直接比较两个候选的对比度，避免固定亮度阈值在中间调（如 #8C8C8C）上判错
        int labelColor = ChartColors.contrastOn(sliceColor, lightLabelColor, darkLabelColor);
        this.currentLabelColor = labelColor;
        nameHaloPaint.setColor(haloPaintFor(labelColor));
    }

    /**
     * 描边与文字取**相反**的一侧：深字配浅描边、浅字配深描边。
     *
     * <p>描边的作用是给文字勾一圈轮廓，所以它必须与文字本身形成反差。
     * 早先这里取反了（浅字也配浅描边），浅色扇区上白字加白描边就等于没有轮廓；
     * 不过两行文字分处不同基线，描边并不会把金额那一行盖掉。</p>
     */
    private int haloPaintFor(int labelColor) {
        return labelColor == darkLabelColor ? haloColorLight : haloColorDark;
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

        // 外置标签先收集、再统一排布、最后绘制。
        // 以前是「算一条画一条」，相邻小扇区的标签会挤在同一片区域互相盖住
        // （典型表现：只看得见分类名，金额被邻标签压掉）。
        List<OutsideLabel> outside = new ArrayList<>();

        // 环内 / 环外走同一个绘制方法：环内已经验证工作正常，两行文字（金额 + 分类名）
        // 的度量与绘制语句只有一份实现，环外不可能再出现「金额不显示」这类分歧。
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

            // 先按这个扇区的颜色定好反色，再画两行字
            applySliceColors(set, i);

            float scale = 1f;
            float labelRadius = (radius + holeRadius) / 2f;
            boolean inside = false;
            if (bigSlice) {
                scale = fitScale(amountText, nameText, center, safeRadius, labelRadius,
                        holeRadius, cos, sin, sweep);
                inside = scale > 0f;
            }

            if (inside) {
                float x = center.x + labelRadius * cos;
                float y = center.y + labelRadius * sin;
                // 环内：文字压在扇区上，用与文字相反的描边勾出轮廓
                drawTwoLines(c, amountText, nameText,
                        amountSizePx * scale, nameSizePx * scale,
                        x, y, Paint.Align.CENTER, currentLabelColor, true,
                        Paint.Style.STROKE, haloPaintFor(currentLabelColor));
                continue;
            }
            outside.add(new OutsideLabel(center, radius, cos, sin, amountText, nameText));
        }

        layoutOutsideLabels(outside);
        for (OutsideLabel label : outside) {
            drawOutside(c, label);
        }
        MPPointF.recycleInstance(center);
    }

    /**
     * 两行标签（第一行金额、第二行分类名）的唯一绘制实现，环内环外共用。
     *
     * @param x          {@code align} 对应的锚点 x：CENTER 时是块中心，LEFT/RIGHT 时是块的左/右边缘
     * @param anchorY    CENTER 时是块中心 y；LEFT/RIGHT 时是块的顶边 y
     * @param align      CENTER = 环内居中；LEFT/RIGHT = 环外靠引线一侧对齐
     * @param centerVertically true 时按块中心对齐，false 时按顶边对齐
     * @param haloStyle  描边样式：环内用 STROKE 勾轮廓；环外用 FILL 垫底
     * @param haloColor  描边/垫底色
     */
    private void drawTwoLines(Canvas c, String amount, String name,
                              float amountSize, float nameSize,
                              float x, float anchorY, Paint.Align align,
                              int color, boolean centerVertically,
                              Paint.Style haloStyle, int haloColor) {
        // 行高按**本次实际字号**算：环内会缩字号，用基准字号算会让两行间距偏大
        float line = lineHeight(amountSize);

        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setColor(color);
        paint.setTextSize(amountSize);
        paint.setTextAlign(align);
        float firstBaseline = centerVertically ? anchorY - line / 2f : anchorY - paint.ascent();
        float nameBaseline = firstBaseline + line;

        // 垫底/描边只垫分类名那一行：金额与分类名分处不同基线，不会互相覆盖
        nameHaloPaint.setStyle(haloStyle);
        nameHaloPaint.setColor(haloColor);
        nameHaloPaint.setTextSize(nameSize);
        nameHaloPaint.setTextAlign(align);
        c.drawText(name, x, nameBaseline, nameHaloPaint);

        // 金额不加描边：环内它压在纯色扇区上，环外压在纯色页面上，都不需要轮廓
        c.drawText(amount, x, firstBaseline, paint);

        paint.setTypeface(Typeface.DEFAULT);
        paint.setColor(color);
        paint.setTextSize(nameSize);
        c.drawText(name, x, nameBaseline, paint);

        paint.setTextAlign(Paint.Align.CENTER);
        nameHaloPaint.setTextAlign(Paint.Align.CENTER);
    }

    /**
     * 找出能塞进这个扇区的最大字号比例；放不下返回 0。
     *
     * <p>从 1.0 往下试到 {@link #MIN_TEXT_SCALE}，取第一个放得下的比例。</p>
     */
    private float fitScale(String amount, String name, MPPointF center, float safeRadius,
                           float labelRadius, float holeRadius,
                           float cos, float sin, float sweep) {
        for (float s = 1f; s >= MIN_TEXT_SCALE - 0.001f; s -= 0.04f) {
            float aSize = amountSizePx * s;
            float nSize = nameSizePx * s;
            float width = Math.max(measure(amount, aSize), measure(name, nSize));
            float height = lineHeight(aSize) * 2f;
            if (fitsAt(center, safeRadius, labelRadius, holeRadius, cos, sin, sweep, width, height)) {
                return s;
            }
        }
        return 0f;
    }

    /** 一个待绘制的环外标签：锚点方向与两行文字（颜色在绘制时按页面背景算）。 */
    private static final class OutsideLabel {
        final float x1, y1, x2, y2;
        final boolean rightSide;
        final String amount, name;
        /** 由 {@link #layoutOutsideLabels} 算出：文字块顶部 y。 */
        float textTop;

        OutsideLabel(MPPointF center, float radius, float cos, float sin,
                     String amount, String name) {
            this.x1 = center.x + radius * cos;
            this.y1 = center.y + radius * sin;
            float leader = Utils.convertDpToPixel(12f);
            this.x2 = x1 + leader * cos;
            this.y2 = y1 + leader * sin;
            this.rightSide = cos >= 0;
            this.amount = amount;
            this.name = name;
            this.textTop = 0f;
        }
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

    /**
     * 环外标签的避让：同一侧按锚点纵向排序后，强制相邻标签至少隔开一个行距，
     * 再整体夹回视图内。
     *
     * <p>不做这一步时，相邻小扇区（占比接近、角度相邻）的标签会落在同一片区域互相盖住，
     * 表现就是「只看得见分类名，金额被压掉」。引线的内段不动，只挪外侧端点与文字，
     * 所以标签被推开后引线会自然变成折线，指向关系不会丢。</p>
     */
    private void layoutOutsideLabels(List<OutsideLabel> labels) {
        if (labels.isEmpty()) return;
        float minY = Utils.convertDpToPixel(2f);
        float maxY = mViewPortHandler.getChartHeight() - Utils.convertDpToPixel(2f);
        if (maxY <= minY) return;

        List<OutsideLabel> right = new ArrayList<>();
        List<OutsideLabel> left = new ArrayList<>();
        for (OutsideLabel label : labels) {
            (label.rightSide ? right : left).add(label);
        }
        distributeSide(right, minY, maxY);
        distributeSide(left, minY, maxY);
    }

    private void distributeSide(List<OutsideLabel> side, float minY, float maxY) {
        if (side.isEmpty()) return;
        side.sort((a, b) -> Float.compare(a.y2, b.y2));
        float gapY = Utils.convertDpToPixel(3f);
        // 环外标签统一用基准字号，所以整块高度对每条都一样，算一次即可
        float blockH = lineHeight(amountSizePx) * 2f;

        // 自上而下推进一遍：每枚先取「贴着自己锚点」的理想位置，再保证不早于
        // 「前一枚 + 一个行距」。只向下推，所以相邻间距必然成立。
        //
        // 顶部越界时向上夹回是安全的：夹回只让这一枚更高，下一枚的紧邻下界是在
        // 这一枚**最终**位置的基础上算的，仍然落在它下方一个行距处。
        // （反方向的「自下而上再推一次」不行：那会把已推开的间距又推回去、恢复重叠。）
        for (int i = 0; i < side.size(); i++) {
            OutsideLabel label = side.get(i);
            float ideal;
            if (label.y2 <= label.y1) {
                ideal = label.y2 - blockH;                     // 上半圆：块底贴着锚点（向上长）
            } else {
                // 下半圆默认向下长；底部放不下就翻到锚点上方 —— 与其把文字画出图外，不如换个方向
                ideal = (label.y2 + blockH <= maxY) ? label.y2 : label.y2 - blockH;
            }
            if (i > 0) {
                OutsideLabel prev = side.get(i - 1);
                float min = prev.textTop + blockH + gapY;
                if (ideal < min) ideal = min;
            }
            label.textTop = Math.max(ideal, minY);
        }
    }

    /** 画一条已经排布好的环外标签：引线（内段到锚点、外段到文字中心）+ 两行文字。 */
    private void drawOutside(Canvas c, OutsideLabel label) {
        float amountSize = amountSizePx;
        float nameSize = nameSizePx;
        float line = lineHeight(amountSize);
        float centerY = label.textTop + line;

        c.drawLine(label.x1, label.y1, label.x2, label.y2, linePaint);
        c.drawLine(label.x2, label.y2, label.x2, centerY, linePaint);

        float gap = Utils.convertDpToPixel(4f);
        float textWidth = Math.max(measure(label.amount, amountSize), measure(label.name, nameSize));
        // 可用范围取视图本身，不能用 contentLeft()/contentRight()：PieChart 的 contentRect
        // 已经为标签预留过空间（圆环因此也变小），拿它当边界会让左右两侧的标签过早判定
        // 「放不下」而翻到引线另一侧；翻过去的方向正好是圆环所在的一侧，标签就压在环上了。
        float viewLeft = Utils.convertDpToPixel(2f);
        float viewRight = mViewPortHandler.getChartWidth() - Utils.convertDpToPixel(2f);

        float x;
        Paint.Align align;
        if (label.rightSide) {
            align = Paint.Align.LEFT;
            x = label.x2 + gap;
            if (x + textWidth > viewRight) {          // 右侧放不下 -> 翻到引线左边
                align = Paint.Align.RIGHT;
                x = label.x2 - gap;
            }
        } else {
            align = Paint.Align.RIGHT;
            x = label.x2 - gap;
            if (x - textWidth < viewLeft) {           // 左侧放不下 -> 翻到引线右边
                align = Paint.Align.LEFT;
                x = label.x2 + gap;
            }
        }

        // 环外标签压在**页面背景**上，不是压在扇区上，所以文字颜色要按页面背景算：
        // 早先用扇区反色，浅色模式下深灰扇区会算成白字 —— 白字画在白页面上直接消失。
        int labelColor = ChartColors.contrastOn(labelBackgroundColor, lightLabelColor, darkLabelColor);

        // 与环内标签共用同一套绘制：两行文字的度量与绘制语句只有一份实现。
        // 区别只有两点：颜色按页面背景取；描边改成实心垫底，用背景色把引线压在文字下面
        // （描边在本例里等于底色，所以实心垫底看不出痕迹，还能顺便遮住穿过的引线）。
        drawTwoLines(c, label.amount, label.name, amountSize, nameSize,
                x, label.textTop, align, labelColor, false,
                Paint.Style.FILL, labelBackgroundColor);
    }

    private float measure(String text, float textSize) {
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(textSize);
        return paint.measureText(text);
    }

    private float lineHeight(float textSize) {
        paint.setTypeface(Typeface.DEFAULT_BOLD);
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
