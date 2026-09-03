package com.personal.accounting.dto.report;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class MonthSummaryDto {
    private int year;
    private int month;
    private BigDecimal income = BigDecimal.ZERO;
    private BigDecimal expense = BigDecimal.ZERO;
    private BigDecimal balance = BigDecimal.ZERO;
    /** 本月支出 vs 上月支出的增减百分比，正数表示增加。 */
    private BigDecimal expenseMomChangePct = BigDecimal.ZERO;
    private BigDecimal prevMonthExpense = BigDecimal.ZERO;
}
