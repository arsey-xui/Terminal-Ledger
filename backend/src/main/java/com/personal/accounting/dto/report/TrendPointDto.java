package com.personal.accounting.dto.report;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class TrendPointDto {
    private LocalDate date;
    private BigDecimal amount = BigDecimal.ZERO;

    public static TrendPointDto of(LocalDate d, BigDecimal amount) {
        TrendPointDto p = new TrendPointDto();
        p.date = d;
        p.amount = amount;
        return p;
    }
}
