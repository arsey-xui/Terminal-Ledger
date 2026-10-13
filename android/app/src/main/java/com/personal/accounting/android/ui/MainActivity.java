package com.personal.accounting.android.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.personal.accounting.android.R;
import com.personal.accounting.android.data.RecurringRepo;
import com.personal.accounting.android.sync.SyncConfig;

import java.time.LocalDate;

/**
 * 主界面：底部 Tab = 记账 / 报表 / 历史 / 预算 / 设置，默认进入记账。
 *
 * <p>同时是系统「分享」的接收方：别的应用把图片分享过来（微信/支付宝截图、相册），
 * 直接切到记账页并跑截图记账的识别流程，省掉「开应用 → 点截图 → 翻相册」三步。</p>
 */
public class MainActivity extends AppCompatActivity {

    /** 待处理的分享图片，只交给下一次创建的记账页，用完即清。 */
    private Uri pendingImage;

    private BottomNavigationView nav;

    /** 底部导航下方的滑动指示条。 */
    private View navIndicator;

    /** 当前已处理的导航项下标，用来识别「重复点击同一个标签」。 */
    private int selectedNavIndex = 0;

    // 各页面的 Fragment 标签：用于 findFragmentByTag 取回已缓存的实例
    private static final String TAG_RECORD = "tab_record";
    private static final String TAG_REPORT = "tab_report";
    private static final String TAG_BUDGET = "tab_budget";
    private static final String TAG_HISTORY = "tab_history";
    private static final String TAG_SETTINGS = "tab_settings";
    private static final String[] FRAGMENT_TAGS = {
            TAG_RECORD, TAG_REPORT, TAG_BUDGET, TAG_HISTORY, TAG_SETTINGS};

    /** 当前显示的页面标签；null 表示还没显示过任何页面（首次不加动画）。 */
    @Nullable
    private String currentTag;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 恢复用户在“设置”里填写的后端地址，保证 release 包可改服务器
        SharedPreferences sp = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE);
        SyncConfig.BASE_URL = sp.getString("base_url", SyncConfig.BASE_URL);

        // 周期记账：每次打开应用把到期的规则生成「待确认项」（幂等，不会重复生成）
        new Thread(() -> {
            try {
                new RecurringRepo(MainActivity.this).materialize(LocalDate.now());
            } catch (Exception ignore) {
                // 生成失败不影响记账主流程
            }
        }).start();

        nav = findViewById(R.id.bottom_nav);
        navIndicator = findViewById(R.id.nav_indicator);

        // 指示条宽度要按导航项等分算，必须等布局完成后再定位置：
        // 此刻 nav.getWidth() 还是 0，算出来会是 0 宽、贴在屏幕左端。
        nav.post(() -> UiMotion.moveIndicator(nav, navIndicator, 0, nav.getMenu().size()));

        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            int index = indexOfNavId(id);
            if (index < 0) return false;

            // 重复点击同一个标签：不重放指示条滑动、选中脉冲与页面切换。
            // currentTag == null 这一项不能少：首次进入时 selectedNavIndex 已经是 0，
            // 但那一次必须真的把页面加进容器，否则启动后只有底部导航、没有内容。
            if (index == selectedNavIndex && currentTag != null) return true;

            UiMotion.moveIndicator(nav, navIndicator, index, nav.getMenu().size());
            UiMotion.pulseNavSelect(nav, id);
            switchTo(index);
            return true;
        });

        // 按压的即时反馈：手指按到哪个标签，那个标签整块压暗一下。
        // 触摸监听消费掉事件（返回 true）不会影响点击 —— 点击由触摸事件派生，this.onTouchEvent 会照常走到。
        nav.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                UiMotion.pulseNavPress(nav, event.getX(), event.getY());
            }
            return false;
        });

        // savedInstanceState 不为 null 说明是旋转/重建：getIntent() 仍是原来那个分享意图，
        // 再处理一次会重复识别（AI 那条路还会重复计费），所以只在首次创建时处理。
        if (savedInstanceState == null) {
            Uri shared = sharedImageOf(getIntent());
            if (shared != null) {
                openSharedImage(shared);
            } else {
                switchTo(indexOfNavId(R.id.nav_record));
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Uri shared = sharedImageOf(intent);
        if (shared != null) openSharedImage(shared);
    }

    /**
     * 按钮按下时补一段下沉缩放。
     *
     * <p>填充反转本身是瞬时的，看起来像「闪了一下」—— 加上下降沿的加速度之后才像「被按下去」。
     * 在这里统一处理而不是给每个按钮加监听：现有的 24 个按钮不用改布局。</p>
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            UiMotion.animateButtonPress(findViewById(android.R.id.content), event.getRawX(), event.getRawY());
        }
        return super.dispatchTouchEvent(event);
    }

    /** 记账页：手里有刚分享进来的图片就一并带过去（一次性）。 */
    private Fragment recordFragment() {
        RecordFragment f = new RecordFragment();
        if (pendingImage != null) {
            f.setSharedImageUri(pendingImage);
            pendingImage = null;
        }
        return f;
    }

    /**
     * 切到记账页并带上分享进来的图片。
     * 已经停在记账页时底部导航的监听器不会触发，所以这里显式替换一次。
     */
    /**
     * 切到记账页并带上分享进来的图片。
     *
     * <p>两条路径都要显式处理：记账页可能已被缓存（那就没必要新建，直接把图片交给现有实例），
     * 也可能还没创建（那就造一个带图片的新实例）。不能只依赖 {@code setSelectedItemId} ——
     * 选中项本来就是记账页时，那条路径不会触发监听器。</p>
     */
    private void openSharedImage(Uri uri) {
        if (nav.getSelectedItemId() != R.id.nav_record) {
            nav.setSelectedItemId(R.id.nav_record);
        }
        int index = indexOfNavId(R.id.nav_record);
        Fragment existing = getSupportFragmentManager().findFragmentByTag(TAG_RECORD);
        if (existing instanceof RecordFragment) {
            // 已缓存：交给现有实例处理，不重建页面
            ((RecordFragment) existing).onSharedImage(uri);
            return;
        }
        pendingImage = uri;
        switchTo(index, recordFragment());
    }

    /** 系统分享过来的图片：ACTION_SEND + image/*。 */
    @Nullable
    private static Uri sharedImageOf(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return null;
        String type = intent.getType();
        if (type == null || !type.startsWith("image/")) return null;
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
                : intent.getParcelableExtra(Intent.EXTRA_STREAM);
    }

    /** 底部导航项对应的 Fragment 标签。 */
    private static String tagOfNavId(int id) {
        if (id == R.id.nav_budget) return TAG_BUDGET;
        if (id == R.id.nav_history) return TAG_HISTORY;
        if (id == R.id.nav_settings) return TAG_SETTINGS;
        if (id == R.id.nav_report) return TAG_REPORT;
        return TAG_RECORD;
    }

    /** 底部导航项在菜单里的下标，用于算指示条位置；未知 id 返回 -1。 */
    private int indexOfNavId(int id) {
        int[] ids = {R.id.nav_record, R.id.nav_report, R.id.nav_budget,
                R.id.nav_history, R.id.nav_settings};
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] == id) return i;
        }
        return -1;
    }

    /**
     * 切换页面：**推拉式**，新旧两页同时并排位移。
     *
     * <p>要做到「两页同时动」就不能用 {@code replace()} —— 它会立刻销毁旧 Fragment 的视图，
     * 旧页根本没机会播退场。所以改成 {@code add + hide/show}：新旧两页同时存活在容器里，
     * 事务动画分别作用在两者上，合起来就是「整体推过去一格」的胶片感。</p>
     *
     * <p><b>方向取决于索引往哪变</b>（不是一律往右）：
     * 新标签更靠右 → 旧页向左推出、新页自右推入；
     * 新标签更靠左 → 旧页向右推出、新页自左推入。
     * 早先假设「任何切换都是往右走」，于是从右边的页面切回左边时方向是反的。</p>
     *
     * <p>代价：每个标签的 Fragment 会常驻（被 hide 而不是销毁），切回来时保持上次的状态，
     * 但图表不会重播生长动画。</p>
     */
    private void switchTo(int index) {
        switchTo(index, null);
    }

    /**
     * 切换页面，可顺带强制用一个新实例替换目标页。
     *
     * @param forceNew 非 null 时用它替换目标标签的页面 —— 分享图片进来时要让记账页带上图片，
     *                 但记账页可能已被缓存，需要一个新实例才不会丢掉之前的参数
     */
    private void switchTo(int index, @Nullable Fragment forceNew) {
        if (index < 0 || index >= FRAGMENT_TAGS.length) return;
        String tag = FRAGMENT_TAGS[index];

        // 幂等条件必须带上 currentTag：**首次进入时 selectedNavIndex 已经是目标下标**，
        // 只看「下标相同」会把启动那次也拦掉，结果应用打开后只有底部导航、没有内容。
        // forceNew 非 null 时永远放行（分享图片要强制重建记账页）。
        if (forceNew == null && currentTag != null && index == selectedNavIndex) return;

        FragmentManager fm = getSupportFragmentManager();
        Fragment target = forceNew != null ? forceNew : fm.findFragmentByTag(tag);
        if (target == null) {
            target = createFragment(tag);
            if (target == null) return;
        }

        // 方向由「索引往哪变」决定：往右切则旧页向左推出、新页自右推入；往左切反过来。
        // 用局部变量记住切换前的下标，避免在下面改字段之后再读它。
        boolean forward = index > selectedNavIndex;
        FragmentTransaction tx = fm.beginTransaction();
        // 首次显示不播动画：启动时滑一下反而显得多余
        if (currentTag != null) {
            tx.setCustomAnimations(
                    forward ? R.anim.page_in_from_right : R.anim.page_in_from_left,
                    forward ? R.anim.page_out_to_left : R.anim.page_out_to_right);
        }
        for (String t : FRAGMENT_TAGS) {
            if (t.equals(tag)) continue;
            Fragment f = fm.findFragmentByTag(t);
            if (f != null && !f.isHidden()) tx.hide(f);
        }
        // 强制替换时先移除同标签的旧实例，否则会留下两个同 tag 的 Fragment
        Fragment previous = fm.findFragmentByTag(tag);
        if (forceNew != null && previous != null && previous != forceNew) {
            tx.remove(previous);
        }
        if (target.isAdded()) {
            tx.show(target);
        } else {
            tx.add(R.id.fragment_container, target, tag);
        }
        // 用 commitNow 而不是 commit：commit 是异步的（post 到主线程队列），
        // 而 hide/show 触发的 onHiddenChanged 回调依赖事务真正生效；
        // 同步提交能让「切走 → 切回」这条链路上的状态在同一帧内确定下来。
        tx.commitNow();

        currentTag = tag;
        selectedNavIndex = index;
    }

    /** 按标签建对应的页面；记账页要带上待处理的分享图片。 */
    @Nullable
    private Fragment createFragment(String tag) {
        if (TAG_RECORD.equals(tag)) return recordFragment();
        if (TAG_REPORT.equals(tag)) return new ReportFragment();
        if (TAG_BUDGET.equals(tag)) return new BudgetFragment();
        if (TAG_HISTORY.equals(tag)) return new HistoryFragment();
        if (TAG_SETTINGS.equals(tag)) return new SettingsFragment();
        return null;
    }
}
