package com.personal.accounting.dto.report;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class HeatmapCellDto {
    private LocalDate date;
    private BigDecimal amount = BigDecimal.ZERO;
}
