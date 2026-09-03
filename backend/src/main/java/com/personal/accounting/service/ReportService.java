package com.personal.accounting.service;

import com.personal.accounting.dto.report.BudgetProgressDto;
import com.personal.accounting.dto.report.CategoryAmountDto;
import com.personal.accounting.dto.report.HeatmapCellDto;
import com.personal.accounting.dto.report.MonthSummaryDto;
import com.personal.accounting.dto.report.TrendPointDto;
import com.personal.accounting.entity.Transaction;

import java.time.LocalDate;
import java.util.List;

public interface ReportService {

    MonthSummaryDto monthSummary(int year, int month);

    List<CategoryAmountDto> categoryBreakdown(int year, int month, String type);

    List<TrendPointDto> dailyTrend(int days);

    List<BudgetProgressDto> budgetProgress(int year, int month);

    List<HeatmapCellDto> yearlyHeatmap(int year);

    List<Transaction> monthlyTransactions(int year, int month);

    List<Transaction> categoryTransactions(int year, int month, String categoryClientId);
}
