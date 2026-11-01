package com.personal.accounting.desktop.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountBalanceTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("单账户余额 = 初始余额 + 收入 − 支出")
    void balanceAddsInitialAndFlows() {
        assertEquals(0, d("1300.00").compareTo(AccountBalance.balance(d("1000"), d("500"), d("200"))));
    }

    @Test
    @DisplayName("空值按 0 处理，不抛异常")
    void nullsCountAsZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(AccountBalance.balance(null, null, null)));
        assertEquals(0, d("-50").compareTo(AccountBalance.netWorth(null, null, d("50"))));
    }

    @Test
    @DisplayName("净资产 = 初始余额之和 + 全部流水净额")
    void netWorthAddsEverythingUp() {
        assertEquals(0, d("1800").compareTo(AccountBalance.netWorth(d("2000"), d("800"), d("1000"))));
    }

    @Test
    @DisplayName("日期晚于今天的流水不计入，今天和过去计入")
    void countsOnlyUpToToday() {
        LocalDate today = LocalDate.of(2026, 8, 22);
        assertTrue(AccountBalance.counts(today, today));
        assertTrue(AccountBalance.counts(today.minusDays(1), today));
        assertTrue(AccountBalance.counts(null, today));
        assertFalse(AccountBalance.counts(today.plusDays(1), today));
    }

    @Test
    @DisplayName("金额显示两位小数并带符号")
    void formatKeepsTwoDecimals() {
        assertEquals("¥1234.50", AccountBalance.format(d("1234.5")));
        assertEquals("¥-0.10", AccountBalance.format(d("-0.1")));
        assertEquals("¥0.00", AccountBalance.format(null));
    }
}
