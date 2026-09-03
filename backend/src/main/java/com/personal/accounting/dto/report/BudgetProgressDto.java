package com.personal.accounting.dto.report;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class BudgetProgressDto {
    private String categoryClientId;
    private String categoryName;
    private String categoryIcon;
    private BigDecimal budget = BigDecimal.ZERO;
    private BigDecimal used = BigDecimal.ZERO;
    private double percent; // 0..100
    /** NORMAL / YELLOW（≥90%）/ RED（≥100%） */
    private String alertLevel = "NORMAL";
}
