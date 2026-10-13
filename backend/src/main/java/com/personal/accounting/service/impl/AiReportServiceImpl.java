package com.personal.accounting.service.impl;

import com.personal.accounting.config.AiProperties;
import com.personal.accounting.dto.report.AiReportRequest;
import com.personal.accounting.dto.report.AiReportResponse;
import com.personal.accounting.dto.report.BudgetProgressDto;
import com.personal.accounting.dto.report.MonthSummaryDto;
import com.personal.accounting.service.AiReportService;
import com.personal.accounting.service.ReportService;
import com.personal.accounting.service.ai.ExpenseStats;
import com.personal.accounting.service.ai.LlmClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** 默认系统提示词。客户端传了 {@code prompt} 就用客户端那份，否则用这份。 */
    public static final String DEFAULT_SYSTEM_PROMPT =
            "你是一位个人理财助手。请基于下面的记账汇总数据，用中文写一段简洁、有条理的消费分析，"
                    + "指出值得关注的点并给出可执行建议；数据里已给出按天统计的日平均支出、日支出标准差与日支出中位数，"
                    + "请把它们纳入分析（标准差越大说明每天的花费起伏越大），不超过 200 字。";

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
            String llmReport = generateWithLlm(year, month, apiKey, baseUrl, model, request.getPrompt());
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

    /** 自定义提示词：空白视为「没填」，用内置默认那份。 */
    private static String systemPrompt(String customPrompt) {
        return (customPrompt != null && !customPrompt.isBlank()) ? customPrompt.trim() : DEFAULT_SYSTEM_PROMPT;
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

        sb.append(ExpenseStats.describeDaily(expenseStats(year, month), periodLabel(year, month)));

        List<com.personal.accounting.dto.report.CategoryAmountDto> top =
                reportService.categoryBreakdown(year, month, "EXPENSE");
        if (!top.isEmpty()) {
            var first = top.get(0);
            sb.append(String.format("支出最多的是「%s」占支出大头，可重点关注。", first.getCategoryName()));
        }
        return sb.toString();
    }

    /** 当月说「本月」，已结束的月份说具体年月 —— 免得 9 月看 8 月数据时被「本月」误导。 */
    private static String periodLabel(int year, int month) {
        LocalDate now = LocalDate.now();
        if (year == now.getYear() && month == now.getMonthValue()) {
            return "本月";
        }
        return String.format("%d年%d月", year, month);
    }

    /** 逐笔支出 → 日支出统计。收入不参与，口径与两端客户端一致。 */
    private ExpenseStats.Value expenseStats(int year, int month) {
        List<ExpenseStats.Entry> entries = reportService.monthlyTransactions(year, month).stream()
                .filter(t -> "EXPENSE".equals(t.getType()))
                .map(t -> new ExpenseStats.Entry(t.getDate(), t.getAmount()))
                .collect(Collectors.toList());
        return ExpenseStats.ofDaily(year, month, entries);
    }

    /** 大模型增强：把报表数据拼成上下文后调用占位接口。失败返回 null，由调用方回退规则引擎。 */
    private String generateWithLlm(int year, int month, String apiKey, String baseUrl, String model, String customPrompt) {
        try {
            String data = buildContext(year, month);
            return llmClient.complete(systemPrompt(customPrompt), data, apiKey, baseUrl, model);
        } catch (Exception e) {
            return null;
        }
    }

    /** 大模型看到的上下文：正文里已含日平均 / 标准差 / 中位数，模型不用自己算。 */
    @Transactional(readOnly = true)
    public String buildContext(int year, int month) {
        MonthSummaryDto s = reportService.monthSummary(year, month);
        var cats = reportService.categoryBreakdown(year, month, "EXPENSE");
        var budgets = reportService.budgetProgress(year, month);
        return String.format("【%d年%d月】", year, month)
                + "summary=" + s.getExpense() + "元支出," + s.getIncome() + "元收入,环比" + s.getExpenseMomChangePct()
                + "%; " + ExpenseStats.describeDaily(expenseStats(year, month), periodLabel(year, month))
                + "; categories=" + cats.stream().map(c -> c.getCategoryName() + ":" + c.getAmount())
                .collect(Collectors.joining(","))
                + "; budgets=" + budgets.stream().map(b -> b.getCategoryName() + ":" + b.getUsed() + "/" + b.getBudget())
                .collect(Collectors.joining(","));
    }
}
