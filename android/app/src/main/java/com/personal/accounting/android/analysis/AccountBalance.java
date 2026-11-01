package com.personal.accounting.android.analysis;

import java.time.LocalDate;
import java.util.Locale;

/**
 * 账户余额与净资产的口径。
 *
 * <p>桌面端有一份逐字对应的实现（{@code desktop/.../service/AccountBalance.java}）：
 * 两端拿同一份数据必须算出同一个数字，所以这里全是纯函数，不碰数据库。</p>
 *
 * <p>口径：</p>
 * <ul>
 *   <li>只算未删除的流水，且日期不晚于今天（未来日期的流水还不该动余额）。</li>
 *   <li>单账户余额 = 账户初始余额 + 该账户名下流水收入 − 支出。</li>
 *   <li>净资产 = 各未删除账户初始余额之和 + 全部流水收入 − 支出。</li>
 * </ul>
 *
 * <p>净资产刻意不依赖「流水挂在哪个账户」：初始余额之和加上全部流水，与逐账户求和再相加恒等，
 * 即使有些流水指向已被删除的账户也一样。日期截断在手机端由 SQL 的 {@code date<=?} 完成
 * （ISO 日期字符串按字典序比较，等价于按时间比较），桌面端由 {@link #counts} 完成。</p>
 */
public final class AccountBalance {

    private AccountBalance() {
    }

    /** 单账户余额 = 初始余额 + 收入 − 支出。 */
    public static double balance(double initialBalance, double income, double expense) {
        return initialBalance + income - expense;
    }

    /** 净资产 = 各账户初始余额之和 + 全部流水收入 − 支出。 */
    public static double netWorth(double initialBalanceTotal, double income, double expense) {
        return initialBalanceTotal + income - expense;
    }

    /** 显示用：两位小数（四舍五入，走 {@code %.2f} 默认的 HALF_UP）。 */
    public static String format(double amount) {
        return String.format(Locale.CHINA, "¥%.2f", amount);
    }
}
