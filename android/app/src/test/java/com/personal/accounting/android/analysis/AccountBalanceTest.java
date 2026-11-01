package com.personal.accounting.android.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountBalanceTest {

    @Test
    @DisplayName("单账户余额 = 初始余额 + 收入 − 支出")
    void balanceAddsInitialAndFlows() {
        assertEquals(1300.0, AccountBalance.balance(1000.0, 500.0, 200.0), 0.0001);
    }

    @Test
    @DisplayName("没有初始余额时，余额就是净流水")
    void balanceWithoutInitialIsJustNetFlow() {
        assertEquals(-120.5, AccountBalance.balance(0, 79.5, 200.0), 0.0001);
    }

    @Test
    @DisplayName("净资产 = 初始余额之和 + 全部流水净额")
    void netWorthAddsEverythingUp() {
        assertEquals(1800.0, AccountBalance.netWorth(2000.0, 800.0, 1000.0), 0.0001);
    }

    @Test
    @DisplayName("净资产与逐账户求和恒等，和流水挂在哪个账户无关")
    void netWorthEqualsSumOfAccountBalances() {
        // 两个账户：A 初始 1000、流水 +500/−200；B 初始 300、流水 +50/−400
        double a = AccountBalance.balance(1000.0, 500.0, 200.0);
        double b = AccountBalance.balance(300.0, 50.0, 400.0);
        // 另有一笔流水指向已被删除的账户（未归属），只体现在总收入/总支出的口径里
        double netWorth = AccountBalance.netWorth(1000.0 + 300.0, 500.0 + 50.0 + 20.0, 200.0 + 400.0);
        assertEquals(a + b + (20.0 - 0.0), netWorth, 0.0001);
    }

    @Test
    @DisplayName("金额显示两位小数并带符号")
    void formatKeepsTwoDecimals() {
        assertEquals("¥1234.50", AccountBalance.format(1234.5));
        assertEquals("¥-0.10", AccountBalance.format(-0.1));
        assertEquals("¥0.00", AccountBalance.format(0));
    }
}
