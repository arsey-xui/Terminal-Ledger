package com.personal.accounting.desktop.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 账户余额与净资产的口径。
 *
 * <p>与手机端 {@code analysis/AccountBalance.java} 逐字对应：两端拿同一份数据必须算出同一个数字，
 * 所以这里全是纯函数，不碰数据库。</p>
 *
 * <p>口径：</p>
 * <ul>
 *   <li>只算未删除的流水，且日期不晚于今天（未来日期的流水还不该动余额）。</li>
 *   <li>单账户余额 = 账户初始余额 + 该账户名下流水收入 − 支出。</li>
 *   <li>净资产 = 各未删除账户初始余额之和 + 全部流水收入 − 支出。</li>
 * </ul>
 */
public final class AccountBalance {

    private AccountBalance() {
    }

    /** 这条流水是否计入余额。日期缺失按计入处理（它不可能在未来）。 */
    public static boolean counts(LocalDate date, LocalDate today) {
        if (date == null || today == null) return true;
        return !date.isAfter(today);
    }

    /** 单账户余额 = 初始余额 + 收入 − 支出。 */
    public static BigDecimal balance(BigDecimal initialBalance, BigDecimal income, BigDecimal expense) {
        return nz(initialBalance).add(nz(income)).subtract(nz(expense));
    }

    /** 净资产 = 各账户初始余额之和 + 全部流水收入 − 支出。 */
    public static BigDecimal netWorth(BigDecimal initialBalanceTotal, BigDecimal income, BigDecimal expense) {
        return nz(initialBalanceTotal).add(nz(income)).subtract(nz(expense));
    }

    /** 显示用：两位小数（HALF_UP），与手机端 {@code %.2f} 一致。 */
    public static String format(BigDecimal amount) {
        return "¥" + nz(amount).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
