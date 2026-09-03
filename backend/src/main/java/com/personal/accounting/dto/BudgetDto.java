package com.personal.accounting.dto;

import com.personal.accounting.entity.Budget;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class BudgetDto {
    private String clientId;
    private String categoryClientId;
    private Integer year;
    private Integer month;
    private BigDecimal amountLimit;
    private Long lastModified;
    private Boolean deleted;

    public static BudgetDto from(Budget b) {
        BudgetDto d = new BudgetDto();
        d.clientId = b.getClientId();
        d.categoryClientId = b.getCategoryClientId();
        d.year = b.getYear();
        d.month = b.getMonth();
        d.amountLimit = b.getAmountLimit();
        d.lastModified = b.getLastModified();
        d.deleted = b.getDeleted();
        return d;
    }
}
