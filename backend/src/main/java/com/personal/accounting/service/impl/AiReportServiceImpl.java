package com.personal.accounting.service.impl;

import com.personal.accounting.config.AiProperties;
import com.personal.accounting.dto.report.AiReportRequest;
import com.personal.accounting.dto.report.AiReportResponse;
import com.personal.accounting.dto.report.BudgetProgressDto;
import com.personal.accounting.dto.report.MonthSummaryDto;
import com.personal.accounting.service.AiReportService;
import com.personal.accounting.service.ReportService;
import com.personal.accounting.service.ai.LlmClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AI 消费分析：仅在报表层产出自然语言解读，不参与自动分类、不自动同步账单。
 * 优先规则引擎（离线可用）；配置了 API Key 时可用 LLM 增强。
 */
@Service
@RequiredArgsConstructor
public class AiReportServiceImpl implements AiReportService {

    private static final DateTimeFormatter CN = DateTimeFormatter.ofPattern("yyyy年M月");

    private final ReportService reportService;
    private final AiProperties props;
    private final LlmClient llmClient;

    @Override
    public AiReportResponse generate(AiReportRequest request) {
        int year = request.getYear() != null ? request.getYear() : LocalDate.now().getYear();
        int month = request.getMonth() != null ? request.getMonth() : LocalDate.now().getMonthValue();
        boolean wantsLlm = "LLM".equalsIgnoreCase(request.getEngine());

        // 有效配置：优先客户端传入的 Key / 接口 / 模型，否则回退服务端默认
        String apiKey = firstNonBlank(request.getApiKey(), props.getApiKey());
        String baseUrl = firstNonBlank(request.getBaseUrl(), props.getBaseUrl());
        String model = firstNonBlank(request.getModel(), props.getModel());

        String report;
        String engine;
        if (wantsLlm && apiKey != null && !apiKey.isBlank()) {
            // 大模型失败时返回 null，据此回退并用真正生效的引擎标注
            String llmReport = generateWithLlm(year, month, apiKey, baseUrl, model);
            if (llmReport != null) {
                report = llmReport;
                engine = "LLM";
            } else {
                report = generateWithRules(year, month);
                engine = "RULE";
            }
        } else {
            report = generateWithRules(year, month);
            engine = "RULE";
        }
        return new AiReportResponse(report, engine, System.currentTimeMillis());
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    /** 规则引擎：离线可用，无需外部服务。 */
    private String generateWithRules(int year, int month) {
        MonthSummaryDto summary = reportService.monthSummary(year, month);
        List<BudgetProgressDto> budgets = reportService.budgetProgress(year, month);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【%s 消费分析】", LocalDate.of(year, month, 1).format(CN)));
        sb.append(String.format("本月总支出 %.2f 元，总收入 %.2f 元，结余 %.2f 元。",
                summary.getExpense(), summary.getIncome(), summary.getBalance()));

        double mom = summary.getExpenseMomChangePct() == null ? 0 : summary.getExpenseMomChangePct().doubleValue();
        if (mom > 0) {
            sb.append(String.format("支出较上月上涨 %.1f%%，注意控制开销。", mom));
        } else if (mom < 0) {
            sb.append(String.format("支出较上月下降 %.1f%%，节流有成效。", Math.abs(mom)));
        } else {
            sb.append("支出与上月基本持平。");
        }

        if (budgets.isEmpty()) {
            sb.append("尚未设置分类预算。");
        } else {
            List<String> triggers = budgets.stream()
                    .filter(b -> "RED".equals(b.getAlertLevel()))
                    .map(b -> b.getCategoryName() + "（已用 " + b.getPercent() + "%）")
                    .collect(Collectors.toList());
            if (!triggers.isEmpty()) {
                sb.append("预算超支：").append(String.join("、", triggers)).append("。");
            }
        }

        List<com.personal.accounting.dto.report.CategoryAmountDto> top =
                reportService.categoryBreakdown(year, month, "EXPENSE");
        if (!top.isEmpty()) {
            var first = top.get(0);
            sb.append(String.format("支出最多的是「%s」占支出大头，可重点关注。", first.getCategoryName()));
        }
        return sb.toString();
    }

    /** 大模型增强：把报表数据拼成上下文后调用占位接口。失败返回 null，由调用方回退规则引擎。 */
    private String generateWithLlm(int year, int month, String apiKey, String baseUrl, String model) {
        try {
            String data = buildContext(year, month);
            return llmClient.complete(
                    "你是一位个人理财助手，请基于下面的结构化消费数据，用中文写一段简洁、可执行的消费分析（不超过 150 字）。",
                    data, apiKey, baseUrl, model);
        } catch (Exception e) {
            return null;
        }
    }

    private String buildContext(int year, int month) {
        MonthSummaryDto s = reportService.monthSummary(year, month);
        var cats = reportService.categoryBreakdown(year, month, "EXPENSE");
        var budgets = reportService.budgetProgress(year, month);
        return "summary=" + s.getExpense() + "元支出," + s.getIncome() + "元收入,环比" + s.getExpenseMomChangePct()
                + "%; categories=" + cats.stream().map(c -> c.getCategoryName() + ":" + c.getAmount())
                .collect(Collectors.joining(","))
                + "; budgets=" + budgets.stream().map(b -> b.getCategoryName() + ":" + b.getUsed() + "/" + b.getBudget())
                .collect(Collectors.joining(","));
    }
}
