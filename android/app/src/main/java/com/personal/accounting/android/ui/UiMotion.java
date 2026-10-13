package com.personal.accounting.android.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.navigation.NavigationBarMenuView;
import com.personal.accounting.android.R;

/**
 * 这套界面语言里的动效。原则是**机械、利落、可预期**，不做弹性回弹和柔和缓动。
 *
 * <ul>
 *   <li><b>指示条</b>：2dp 酸橙绿细线在选中的标签下方平移，180ms。
 *       比页面切换（300ms）快 —— 指针先到位、内容随后展开。</li>
 *   <li><b>导航按压 / 选中</b>：只动画导航项的**图标**，见下面两个方法。</li>
 *   <li><b>按钮按压</b>：下沉缩放，给填充反转一个下降沿的加速度。</li>
 * </ul>
 *
 * <p>页面切换（推拉式）不在这里 —— 它由 {@code MainActivity.show()} 用
 * {@code FragmentTransaction.setCustomAnimations} 配 {@code res/anim/page_push_in.xml}
 * 与 {@code page_push_out.xml} 完成，需要事务层面同时驱动新旧两页。</p>
 */
final class UiMotion {

    /** 指示条平移时长。比页面切换（300ms）快，符合「指针先到位」的手感。 */
    private static final long INDICATOR_MS = 180L;

    /** 指示条的位置与动画，同一时刻只维护一条。 */
    @Nullable
    private static ValueAnimator indicatorAnimator;
    private static float indicatorLeft = Float.NaN;

    private UiMotion() {
    }

    /**
     * 按钮按下：补一段下沉缩放，让填充反转有加速度（否则看着像「闪了一下」）。
     *
     * <p>通过命中测试找被按到的按钮，找不到就什么都不做。</p>
     */
    static void animateButtonPress(@Nullable View root, float rawX, float rawY) {
        View button = findButtonAt(root, rawX, rawY);
        if (button == null) return;
        button.startAnimation(AnimationUtils.loadAnimation(button.getContext(), R.anim.btn_press));
    }

    /** 在视图树里找「坐标所在的、可见的 MaterialButton」。 */
    @Nullable
    private static View findButtonAt(@Nullable View node, float rawX, float rawY) {
        if (node == null || node.getVisibility() != View.VISIBLE) return null;

        final int[] loc = new int[2];
        node.getLocationOnScreen(loc);
        boolean inside = rawX >= loc[0] && rawX < loc[0] + node.getWidth()
                && rawY >= loc[1] && rawY < loc[1] + node.getHeight();
        if (inside && node instanceof MaterialButton) return node;

        if (!(node instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) node;
        // 逆序：后添加的子视图在上层，优先命中它
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View found = findButtonAt(group.getChildAt(i), rawX, rawY);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * 导航项按压：图标压暗再恢复。
     *
     * <p>只动画**图标**。整项一起压暗太显眼，也会让标签文字跟着变淡。</p>
     */
    static void pulseNavPress(@NonNull BottomNavigationView nav, float touchX, float touchY) {
        View icon = iconAt(nav, touchX, touchY);
        if (icon == null) return;
        icon.startAnimation(AnimationUtils.loadAnimation(icon.getContext(), R.anim.nav_item_press));
    }

    /** 导航项选中：图标放大再回落。 */
    static void pulseNavSelect(@NonNull BottomNavigationView nav, int itemId) {
        View icon = iconOf(nav, itemId);
        if (icon == null) return;
        icon.startAnimation(AnimationUtils.loadAnimation(icon.getContext(), R.anim.nav_icon_select));
    }

    /**
     * 按菜单 id 取该项的图标视图。
     *
     * <p>用 {@code NavigationBarMenuView.findItemView(id)} 而不是「菜单顺序 = 子 View 顺序」——
     * 后者是错的（按它取到别的项，表现是「点一个标签、几个标签一起动」）。</p>
     */
    @Nullable
    private static View iconOf(@NonNull BottomNavigationView nav, int itemId) {
        View itemView = itemViewOf(nav, itemId);
        return itemView == null ? null : findIcon(itemView);
    }

    @Nullable
    private static View iconAt(@NonNull BottomNavigationView nav, float x, float y) {
        // 先在导航项层级做命中测试，再往里找图标
        View hit = null;
        for (int i = 0; i < nav.getChildCount(); i++) {
            View child = nav.getChildAt(i);
            if (x >= child.getLeft() && x < child.getRight()
                    && y >= child.getTop() && y < child.getBottom()) {
                hit = child;
                break;
            }
        }
        if (hit == null) return null;
        // 命中的可能已经是图标，也可能还是项视图
        return hit instanceof ImageView ? hit : findIcon(hit);
    }

    /** 在导航项里找图标。Material 的项结构是「图标 + 文字」，图标是唯一的 ImageView。 */
    @Nullable
    private static View findIcon(@NonNull View root) {
        if (root instanceof ImageView) return root;
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findIcon(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    @Nullable
    private static View itemViewOf(@NonNull BottomNavigationView nav, int itemId) {
        try {
            NavigationBarMenuView menuView = (NavigationBarMenuView) nav.getMenuView();
            return menuView == null ? null : menuView.findItemView(itemId);
        } catch (ClassCastException e) {
            // 不是预期的实现：跳过动画即可，不影响切换本身
            return null;
        }
    }

    /**
     * 把指示条移到第 {@code index} 个标签下方。
     *
     * <p>首次调用直接落位、不做动画（否则启动时会看到指示条从屏幕左端滑过来）。</p>
     */
    static void moveIndicator(@NonNull BottomNavigationView nav, @NonNull View indicator, int index,
                              int itemCount) {
        if (itemCount <= 0 || nav.getWidth() == 0) return;
        float itemWidth = nav.getWidth() / (float) itemCount;
        // 指示条比标签窄一些（60%），两端各留 20%，看起来像「刻度」而不是「下划线」
        float indicatorWidth = itemWidth * 0.6f;
        float targetLeft = index * itemWidth + itemWidth * 0.2f;

        ViewGroup.LayoutParams lp = indicator.getLayoutParams();
        if (lp != null && lp.width != (int) indicatorWidth) {
            lp.width = (int) indicatorWidth;
            indicator.setLayoutParams(lp);
        }

        if (Float.isNaN(indicatorLeft)) {
            indicatorLeft = targetLeft;
            indicator.setTranslationX(targetLeft);
            return;
        }
        if (indicatorAnimator != null) indicatorAnimator.cancel();
        indicatorAnimator = ValueAnimator.ofFloat(indicatorLeft, targetLeft);
        indicatorAnimator.setDuration(INDICATOR_MS);
        indicatorAnimator.setInterpolator(new DecelerateInterpolator());
        indicatorAnimator.addUpdateListener(a -> {
            float x = (float) a.getAnimatedValue();
            indicator.setTranslationX(x);
            indicatorLeft = x;
        });
        indicatorAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                indicatorLeft = targetLeft;
                indicator.setTranslationX(targetLeft);
            }
        });
        indicatorAnimator.start();
    }
}
