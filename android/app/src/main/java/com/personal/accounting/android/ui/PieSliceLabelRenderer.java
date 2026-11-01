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
import java.util.Arrays;
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

    /**
     * 相位到这个值之后标签才开始淡入，到 1.0 完全不透明。
     *
     * <p>扇区是按相位生长的（见 {@code PieChartRenderer}：起点与扫角都乘 phaseY）。相位很小时
     * 所有扇区都挤在 12 点方向，此时把标签画上去就是一团叠字；等扇区基本长到位置再让标签出现，
     * 淡入期间位置仍然跟着扇区走，就不会有「图形还没到、标签先到」的悬空感。</p>
     */
    private static final float LABEL_FADE_FROM = 0.85f;

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

    /**
     * 每一侧**共用**的引线走廊 x（见 {@link #computeCorridors}）。
     * 文字放在走廊外侧，所以同侧引线不可能穿过任何一条标签的文字。
     */
    private float leftCorridorX = Float.NaN;
    private float rightCorridorX = Float.NaN;

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

        // 扇区在动画期间按相位缩放（PieChartRenderer：起点 = 旋转角 + 累积角*phaseY，
        // 扫角 = 扇区角*phaseY），所以标签必须用同一个相位定位。此前这里按**最终**角度算位置，
        // 动画还没扫到、标签已经画在终点上 —— 就是「标签悬在空中、和扇区对不上」。
        float phase = mAnimator.getPhaseY();
        float appear = (phase - LABEL_FADE_FROM) / (1f - LABEL_FADE_FROM);
        if (appear <= 0f) return;
        if (appear > 1f) appear = 1f;
        int labelAlpha = Math.round(appear * 255f);

        MPPointF center = mChart.getCenterCircleBox();
        float radius = mChart.getRadius();
        float holeRadius = radius / 100f * mChart.getHoleRadius();
        float sliceAngle = 360f / total;
        float rotationAngle = mChart.getRotationAngle();
        // 两个累积角：cur 按相位缩放（这一帧画到哪），full 不缩放（最终画到哪）
        float curAngle = rotationAngle;
        float fullAngle = rotationAngle;
        float safeRadius = radius - Utils.convertDpToPixel(4f);

        // 外置标签先收集、再统一排布、最后绘制。
        // 以前是「算一条画一条」，相邻小扇区的标签会挤在同一片区域互相盖住
        // （典型表现：只看得见分类名，金额被邻标签压掉）。
        List<OutsideLabel> outside = new ArrayList<>();

        // 环内 / 环外走同一个绘制方法：环内已经验证工作正常，两行文字（金额 + 分类名）
        // 的度量与绘制语句只有一份实现，环外不可能再出现「金额不显示」这类分歧。
        for (int i = 0; i < entries.size(); i++) {
            PieEntry entry = entries.get(i);
            float fullSweep = sliceAngle * entry.getValue();
            float sweep = fullSweep * phase;
            float midAngle = curAngle + sweep / 2f;
            curAngle += sweep;
            float fullMidAngle = fullAngle + fullSweep / 2f;
            fullAngle += fullSweep;

            double rad = Math.toRadians(midAngle);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);
            String amountText = formatAmount(entry.getValue());
            String nameText = labelOf(entry);
            boolean bigSlice = entry.getValue() / total * 100f > minPercentInside;
            float labelRadius = (radius + holeRadius) / 2f;

            // 先按这个扇区的颜色定好反色，再画两行字
            applySliceColors(set, i);

            float scale = 1f;
            boolean inside = false;
            if (bigSlice) {
                // 「放环内还是环外」以及字号都按**最终**扇区定：动画中途改摆法或改字号，
                // 标签会在环内/环外之间跳、文字大小也会抖。
                double fullRad = Math.toRadians(fullMidAngle);
                scale = fitScale(amountText, nameText, center, safeRadius, labelRadius, holeRadius,
                        (float) Math.cos(fullRad), (float) Math.sin(fullRad), fullSweep);
                if (scale > 0f) {
                    // 最终摆得下：这一帧扇区还没长到那个宽度就先不画。不能退到环外 ——
                    // 那会让同一枚标签在动画中途从环内跳到环外。
                    if (!fitsAtScale(amountText, nameText, center, safeRadius, labelRadius,
                            holeRadius, cos, sin, sweep, scale)) {
                        continue;
                    }
                    inside = true;
                }
            }

            if (inside) {
                float x = center.x + labelRadius * cos;
                float y = center.y + labelRadius * sin;
                // 环内：文字压在扇区上，用与文字相反的描边勾出轮廓
                drawTwoLines(c, amountText, nameText,
                        amountSizePx * scale, nameSizePx * scale,
                        x, y, Paint.Align.CENTER, currentLabelColor, true,
                        Paint.Style.STROKE, haloPaintFor(currentLabelColor), labelAlpha);
                continue;
            }
            outside.add(new OutsideLabel(center, radius, cos, sin, amountText, nameText));
        }

        layoutOutsideLabels(outside);
        computeCorridors(outside);
        for (OutsideLabel label : outside) {
            drawOutside(c, label, labelAlpha);
        }
        MPPointF.recycleInstance(center);
        // 把透明度还回去：这三支 Paint 是渲染器自己的，别把淡入用的透明度留给下一次绘制
        paint.setAlpha(255);
        nameHaloPaint.setAlpha(255);
        linePaint.setAlpha(255);
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
     * @param alpha      整体透明度（0-255）：动画淡入用，静态时传 255
     */
    private void drawTwoLines(Canvas c, String amount, String name,
                              float amountSize, float nameSize,
                              float x, float anchorY, Paint.Align align,
                              int color, boolean centerVertically,
                              Paint.Style haloStyle, int haloColor, int alpha) {
        // 行高按**本次实际字号**算：环内会缩字号，用基准字号算会让两行间距偏大
        float line = lineHeight(amountSize);

        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setColor(color);
        // setColor 会把透明度重置成颜色自带的值，所以透明度必须在它之后设
        paint.setAlpha(alpha);
        paint.setTextSize(amountSize);
        paint.setTextAlign(align);
        float firstBaseline = centerVertically ? anchorY - line / 2f : anchorY - paint.ascent();
        float nameBaseline = firstBaseline + line;

        // 垫底/描边只垫分类名那一行：金额与分类名分处不同基线，不会互相覆盖
        nameHaloPaint.setStyle(haloStyle);
        nameHaloPaint.setColor(haloColor);
        nameHaloPaint.setAlpha(alpha);
        nameHaloPaint.setTextSize(nameSize);
        nameHaloPaint.setTextAlign(align);
        c.drawText(name, x, nameBaseline, nameHaloPaint);

        // 金额不加描边：环内它压在纯色扇区上，环外压在纯色页面上，都不需要轮廓
        c.drawText(amount, x, firstBaseline, paint);

        paint.setTypeface(Typeface.DEFAULT);
        paint.setColor(color);
        paint.setAlpha(alpha);
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
            if (fitsAtScale(amount, name, center, safeRadius, labelRadius, holeRadius,
                    cos, sin, sweep, s)) {
                return s;
            }
        }
        return 0f;
    }

    /**
     * 按给定字号比例量出标签块尺寸，判断它在**给定扫角**的扇区里放不放得下。
     *
     * <p>扫角由调用方给：定字号时传最终扫角，判断「这一帧画不画」时传当前帧的扫角。</p>
     */
    private boolean fitsAtScale(String amount, String name, MPPointF center, float safeRadius,
                                float labelRadius, float holeRadius,
                                float cos, float sin, float sweep, float scale) {
        float aSize = amountSizePx * scale;
        float nSize = nameSizePx * scale;
        float width = Math.max(measure(amount, aSize), measure(name, nSize));
        float height = lineHeight(aSize) * 2f;
        return fitsAt(center, safeRadius, labelRadius, holeRadius, cos, sin, sweep, width, height);
    }

    /** 一个待绘制的环外标签：锚点方向与两行文字（颜色在绘制时按页面背景算）。 */
    private static final class OutsideLabel {
        final float x1, y1, x2, y2;
        final boolean rightSide;
        final String amount, name;
        /** 圆心与半径：标签被纵向推开后，要按它重算「文字那一行环有多宽」。 */
        final float centerX, centerY, radius;
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
            this.centerX = center.x;
            this.centerY = center.y;
            this.radius = radius;
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

        // 排布数学在 PieLabelGeometry（纯逻辑、不依赖 Android，可在 JVM 上单测）
        float[] foldY = new float[side.size()];
        boolean[] pointsUp = new boolean[side.size()];
        for (int i = 0; i < side.size(); i++) {
            OutsideLabel label = side.get(i);
            foldY[i] = label.y2;
            pointsUp[i] = label.y2 <= label.y1;
        }
        float[] tops = PieLabelGeometry.textTops(foldY, pointsUp, blockH, gapY, minY, maxY);
        for (int i = 0; i < side.size(); i++) {
            side.get(i).textTop = tops[i];
        }
    }

    /**
     * 每一侧只留**一条**引线走廊。
     *
     * <p>以前每条标签各算各的走廊：按自己那段纵向范围里环的外沿算，谁更靠近圆心的高度，
     * 谁就被推到更外侧。于是在同一侧，一条标签的竖直引线可能落在另一条标签文字的水平范围内，
     * 直接从字中间穿过去 —— 截图里「43元」被一条竖线穿过就是这样。</p>
     *
     * <p>同侧统一到一条走廊后，所有竖段共线、文字一律在走廊外侧，引线再也不可能穿过文字。
     * 代价是同侧几条引线的竖段会重叠成一段共线（看起来像一条总线），指向关系靠各自的斜段区分。</p>
     */
    private void computeCorridors(List<OutsideLabel> labels) {
        leftCorridorX = Float.NaN;
        rightCorridorX = Float.NaN;
        if (labels.isEmpty()) return;

        float viewLeft = Utils.convertDpToPixel(2f);
        float viewRight = mViewPortHandler.getChartWidth() - Utils.convertDpToPixel(2f);
        float gap = Utils.convertDpToPixel(4f);
        float ringGap = Utils.convertDpToPixel(6f);
        float blockH = lineHeight(amountSizePx) * 2f;

        float[] leftEdges = new float[labels.size()];
        float[] rightEdges = new float[labels.size()];
        int leftCount = 0;
        int rightCount = 0;
        float widestLeft = 0f;
        float widestRight = 0f;
        for (OutsideLabel label : labels) {
            float spanTop = Math.min(label.y2, label.textTop);
            float spanBottom = Math.max(label.y2, label.textTop + blockH);
            float edge = ringEdgeX(label, spanTop, spanBottom, label.rightSide);
            float textWidth = Math.max(measure(label.amount, amountSizePx), measure(label.name, nameSizePx));
            if (label.rightSide) {
                rightEdges[rightCount++] = edge + ringGap;
                widestRight = Math.max(widestRight, textWidth);
            } else {
                leftEdges[leftCount++] = edge - ringGap;
                widestLeft = Math.max(widestLeft, textWidth);
            }
        }

        // 走廊的合成（取最外侧 + 保证最宽文字仍在视图内）在 PieLabelGeometry 里
        if (leftCount > 0) {
            leftCorridorX = PieLabelGeometry.corridorX(
                    Arrays.copyOf(leftEdges, leftCount), false, gap, widestLeft, viewLeft, viewRight);
        }
        if (rightCount > 0) {
            rightCorridorX = PieLabelGeometry.corridorX(
                    Arrays.copyOf(rightEdges, rightCount), true, gap, widestRight, viewLeft, viewRight);
        }
    }

    /** 画一条已经排布好的环外标签：引线（环边 → 走廊 → 文字那一行）+ 两行文字。 */
    /**
     * 画一条已经排布好的环外标签：引线（环边 → 走廊 → 文字那一行）+ 两行文字。
     *
     * <p>折点落在**同侧共用的走廊**上（见 {@link #computeCorridors}），不再每条标签各算各的：
     * 各算各的时候，越接近圆心高度的标签走廊越靠外，它的竖直段正好横穿另一条标签的文字。</p>
     */
    private void drawOutside(Canvas c, OutsideLabel label, int alpha) {
        float amountSize = amountSizePx;
        float nameSize = nameSizePx;
        float line = lineHeight(amountSize);
        float blockH = line * 2f;
        float textCenterY = label.textTop + line;

        float gap = Utils.convertDpToPixel(4f);
        boolean right = label.rightSide;
        float outerX = right ? rightCorridorX : leftCorridorX;
        if (Float.isNaN(outerX)) {
            // 兜底：正常流程里 computeCorridors 已经给每一侧算好走廊，走不到这里；
            // 真走到了就贴着环外沿画，至少不会让整条标签消失。
            float spanTop = Math.min(label.y2, label.textTop);
            float spanBottom = Math.max(label.y2, label.textTop + blockH);
            float ringGap = Utils.convertDpToPixel(6f);
            float ringEdge = ringEdgeX(label, spanTop, spanBottom, right);
            outerX = right ? ringEdge + ringGap : ringEdge - ringGap;
        }
        float textInner = right ? outerX + gap : outerX - gap;
        Paint.Align align = right ? Paint.Align.LEFT : Paint.Align.RIGHT;

        linePaint.setAlpha(alpha);
        // 斜段直接落到**文字那一行**，再从走廊横一小段接到文字：没有竖段了。
        // 同侧各行按锚点顺序排列（distributeSide 排的），所以这些斜段按行序收敛、互不相交，
        // 也不可能压到别人的文字 —— 文字永远在走廊外侧。
        c.drawLine(label.x1, label.y1, outerX, textCenterY, linePaint);
        c.drawLine(outerX, textCenterY, textInner, textCenterY, linePaint);

        // 环外标签压在**页面背景**上，不是压在扇区上，所以文字颜色要按页面背景算：
        // 早先用扇区反色，浅色模式下深灰扇区会算成白字 —— 白字画在白页面上直接消失。
        int labelColor = ChartColors.contrastOn(labelBackgroundColor, lightLabelColor, darkLabelColor);

        // 与环内标签共用同一套绘制：两行文字的度量与绘制语句只有一份实现。
        // 区别只有两点：颜色按页面背景取；描边改成实心垫底，用背景色把引线压在文字下面
        // （描边在本例里等于底色，所以实心垫底看不出痕迹，还能顺便遮住穿过的引线）。
        drawTwoLines(c, label.amount, label.name, amountSize, nameSize,
                textInner, label.textTop, align, labelColor, false,
                Paint.Style.FILL, labelBackgroundColor, alpha);
    }

    /**
     * 圆环在 [spanTop, spanBottom] 这段纵向范围里最靠某一侧的外沿 x。
     *
     * <p>圆的左右边缘在圆心所在那一行最靠外、离圆心越远越往里收，所以只要取区间内离圆心
     * 最近的那一行来算。区间整个落在圆外时按圆心算，等价于「这一行没有环」。</p>
     */
    private static float ringEdgeX(OutsideLabel label, float spanTop, float spanBottom, boolean right) {
        return PieLabelGeometry.ringEdgeX(label.centerX, label.centerY, label.radius,
                spanTop, spanBottom, right);
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
