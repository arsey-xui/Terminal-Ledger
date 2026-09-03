package com.personal.accounting.service.impl;

import com.personal.accounting.dto.report.BudgetProgressDto;
import com.personal.accounting.dto.report.CategoryAmountDto;
import com.personal.accounting.dto.report.HeatmapCellDto;
import com.personal.accounting.dto.report.MonthSummaryDto;
import com.personal.accounting.dto.report.TrendPointDto;
import com.personal.accounting.entity.Budget;
import com.personal.accounting.entity.Category;
import com.personal.accounting.entity.Transaction;
import com.personal.accounting.repository.BudgetRepository;
import com.personal.accounting.repository.CategoryRepository;
import com.personal.accounting.repository.TransactionRepository;
import com.personal.accounting.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final BudgetRepository budgetRepository;

    @Override
    public MonthSummaryDto monthSummary(int year, int month) {
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        List<Transaction> txns = transactionRepository.findByDeletedFalseAndDateBetween(start, end);

        BigDecimal income = sumByType(txns, "INCOME");
        BigDecimal expense = sumByType(txns, "EXPENSE");

        MonthSummaryDto dto = new MonthSummaryDto();
        dto.setYear(year);
        dto.setMonth(month);
        dto.setIncome(income);
        dto.setExpense(expense);
        dto.setBalance(income.subtract(expense));

        LocalDate prevStart = start.minusMonths(1);
        LocalDate prevEnd = prevStart.withDayOfMonth(prevStart.lengthOfMonth());
        BigDecimal prevExpense = sumByType(
                transactionRepository.findByDeletedFalseAndDateBetween(prevStart, prevEnd), "EXPENSE");
        dto.setPrevMonthExpense(prevExpense);
        if (prevExpense.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal pct = expense.subtract(prevExpense)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(prevExpense, 2, RoundingMode.HALF_UP);
            dto.setExpenseMomChangePct(pct);
        }
        return dto;
    }

    @Override
    public List<CategoryAmountDto> categoryBreakdown(int year, int month, String type) {
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        List<Transaction> txns = transactionRepository
                .findByDeletedFalseAndTypeAndDateBetween(type, start, end);
        Map<String, Category> catMap = categoryRepository.findByDeletedFalseOrderBySortOrderAsc()
                .stream().collect(Collectors.toMap(Category::getClientId, Function.identity(), (a, b) -> a));

        Map<String, List<Transaction>> grouped = txns.stream()
                .filter(t -> t.getCategoryClientId() != null)
                .collect(Collectors.groupingBy(Transaction::getCategoryClientId, LinkedHashMap::new, Collectors.toList()));

        List<CategoryAmountDto> result = new ArrayList<>();
        grouped.forEach((cid, list) -> {
            CategoryAmountDto d = new CategoryAmountDto();
            d.setCategoryClientId(cid);
            Category c = catMap.get(cid);
            d.setCategoryName(c != null ? c.getName() : "未分类");
            d.setCategoryIcon(c != null ? c.getIcon() : null);
            d.setAmount(list.stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
            d.setCount(list.size());
            result.add(d);
        });
        result.sort((a, b) -> b.getAmount().compareTo(a.getAmount()));
        return result;
    }

    @Override
    public List<TrendPointDto> dailyTrend(int days) {
        LocalDate start = LocalDate.now().minusDays(days - 1L);
        List<Transaction> txns = transactionRepository.findByDeletedFalseAndDateAfter(start);
        Map<LocalDate, BigDecimal> sums = new HashMap<>();
        txns.stream().filter(t -> "EXPENSE".equals(t.getType()))
                .forEach(t -> sums.merge(t.getDate(), t.getAmount(), BigDecimal::add));

        List<TrendPointDto> points = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            LocalDate d = start.plusDays(i);
            points.add(TrendPointDto.of(d, sums.getOrDefault(d, BigDecimal.ZERO)));
        }
        return points;
    }

    @Override
    public List<BudgetProgressDto> budgetProgress(int year, int month) {
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        List<Transaction> txns = transactionRepository
                .findByDeletedFalseAndDateBetween(start, end).stream()
                .filter(t -> "EXPENSE".equals(t.getType()))
                .toList();
        Map<String, BigDecimal> usedByCat = new HashMap<>();
        txns.forEach(t -> {
            if (t.getCategoryClientId() != null) {
                usedByCat.merge(t.getCategoryClientId(), t.getAmount(), BigDecimal::add);
            }
        });

        Map<String, Category> catMap = categoryRepository.findByDeletedFalseOrderBySortOrderAsc()
                .stream().collect(Collectors.toMap(Category::getClientId, Function.identity(), (a, b) -> a));

        List<BudgetProgressDto> result = new ArrayList<>();
        for (Budget b : budgetRepository.findByLastModifiedGreaterThan(Long.MIN_VALUE, org.springframework.data.domain.Pageable.unpaged())) {
            if (b.getYear().equals(year) && b.getMonth() == month && !Boolean.TRUE.equals(b.getDeleted())) {
                BudgetProgressDto d = new BudgetProgressDto();
                d.setCategoryClientId(b.getCategoryClientId());
                Category c = catMap.get(b.getCategoryClientId());
                d.setCategoryName(c != null ? c.getName() : "未分类");
                d.setCategoryIcon(c != null ? c.getIcon() : null);
                d.setBudget(b.getAmountLimit());
                BigDecimal used = usedByCat.getOrDefault(b.getCategoryClientId(), BigDecimal.ZERO);
                d.setUsed(used);
                long pct = b.getAmountLimit().compareTo(BigDecimal.ZERO) > 0
                        ? used.multiply(BigDecimal.valueOf(100)).divide(b.getAmountLimit(), 0, RoundingMode.HALF_UP).longValue()
                        : 0L;
                d.setPercent(pct);
                if (pct >= 100) d.setAlertLevel("RED");
                else if (pct >= 90) d.setAlertLevel("YELLOW");
                result.add(d);
            }
        }
        return result;
    }

    @Override
    public List<HeatmapCellDto> yearlyHeatmap(int year) {
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);
        List<Transaction> txns = transactionRepository.findByDeletedFalseAndDateBetween(start, end);
        Map<LocalDate, BigDecimal> perDay = new HashMap<>();
        txns.stream().filter(t -> "EXPENSE".equals(t.getType()))
                .forEach(t -> perDay.merge(t.getDate(), t.getAmount(), BigDecimal::add));

        List<HeatmapCellDto> cells = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            HeatmapCellDto cell = new HeatmapCellDto();
            cell.setDate(d);
            cell.setAmount(perDay.getOrDefault(d, BigDecimal.ZERO));
            cells.add(cell);
        }
        return cells;
    }

    @Override
    public List<Transaction> monthlyTransactions(int year, int month) {
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        return transactionRepository.findByDeletedFalseAndDateBetween(start, end);
    }

    @Override
    public List<Transaction> categoryTransactions(int year, int month, String categoryClientId) {
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        return transactionRepository.findByDeletedFalseAndDateBetween(start, end).stream()
                .filter(t -> categoryClientId.equals(t.getCategoryClientId()))
                .toList();
    }

    private BigDecimal sumByType(List<Transaction> txns, String type) {
        return txns.stream().filter(t -> type.equals(t.getType()))
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
