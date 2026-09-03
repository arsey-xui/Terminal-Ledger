package com.personal.accounting.controller;

import com.personal.accounting.dto.ApiResponse;
import com.personal.accounting.dto.report.BudgetProgressDto;
import com.personal.accounting.dto.report.CategoryAmountDto;
import com.personal.accounting.dto.report.HeatmapCellDto;
import com.personal.accounting.dto.report.MonthSummaryDto;
import com.personal.accounting.dto.report.TrendPointDto;
import com.personal.accounting.entity.Transaction;
import com.personal.accounting.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @GetMapping("/month")
    public ApiResponse<MonthSummaryDto> month(
            @RequestParam int year, @RequestParam int month) {
        return ApiResponse.ok(reportService.monthSummary(year, month));
    }

    @GetMapping("/category-breakdown")
    public ApiResponse<List<CategoryAmountDto>> breakdown(
            @RequestParam int year, @RequestParam int month,
            @RequestParam(defaultValue = "EXPENSE") String type) {
        return ApiResponse.ok(reportService.categoryBreakdown(year, month, type));
    }

    @GetMapping("/trend")
    public ApiResponse<List<TrendPointDto>> trend(@RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(reportService.dailyTrend(days));
    }

    @GetMapping("/budget-progress")
    public ApiResponse<List<BudgetProgressDto>> budgetProgress(
            @RequestParam int year, @RequestParam int month) {
        return ApiResponse.ok(reportService.budgetProgress(year, month));
    }

    @GetMapping("/heatmap")
    public ApiResponse<List<HeatmapCellDto>> heatmap(@RequestParam int year) {
        return ApiResponse.ok(reportService.yearlyHeatmap(year));
    }

    @GetMapping("/transactions")
    public ApiResponse<List<Transaction>> transactions(
            @RequestParam int year, @RequestParam int month) {
        return ApiResponse.ok(reportService.monthlyTransactions(year, month));
    }

    @GetMapping("/category-transactions")
    public ApiResponse<List<Transaction>> categoryTransactions(
            @RequestParam int year, @RequestParam int month,
            @RequestParam String categoryClientId) {
        return ApiResponse.ok(reportService.categoryTransactions(year, month, categoryClientId));
    }
}
