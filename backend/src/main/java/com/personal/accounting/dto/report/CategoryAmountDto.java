package com.personal.accounting.dto.report;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class CategoryAmountDto {
    private String categoryClientId;
    private String categoryName;
    private String categoryIcon;
    private BigDecimal amount = BigDecimal.ZERO;
    private long count;
}
