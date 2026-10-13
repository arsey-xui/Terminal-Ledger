package com.personal.accounting.desktop.service;

import com.personal.accounting.desktop.model.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 饼图口径：只算支出、按分类合并、收入与无分类流水不计入。 */
class ReportMathTest {

    private static Transaction txn(String type, String categoryClientId, String amount) {
        Transaction t = new Transaction();
        t.type = type;
        t.categoryClientId = categoryClientId;
        t.amount = amount == null ? null : new BigDecimal(amount);
        return t;
    }

    @Test
    void sumsExpensePerCategoryAndIgnoresIncome() {
        List<Transaction> txns = Arrays.asList(
                txn("EXPENSE", "cat-food", "10"),
                txn("EXPENSE", "cat-food", "20"),
                txn("EXPENSE", "cat-traffic", "5"),
                txn("INCOME", "cat-salary", "5000"));
        Map<String, BigDecimal> byCategory = ReportMath.expenseByCategory(txns);

        assertEquals(2, byCategory.size());
        assertEquals(0, new BigDecimal("30").compareTo(byCategory.get("cat-food")));
        assertEquals(0, new BigDecimal("5").compareTo(byCategory.get("cat-traffic")));
        assertTrue(!byCategory.containsKey("cat-salary"), "收入不该进分类占比");
        assertEquals(0, new BigDecimal("35").compareTo(ReportMath.totalOf(byCategory)));
    }

    @Test
    void skipsUncategorisedAndNullAmounts() {
        List<Transaction> txns = Arrays.asList(
                txn("EXPENSE", null, "99"),
                txn("EXPENSE", "cat-food", null),
                txn("EXPENSE", "cat-food", "10"));
        Map<String, BigDecimal> byCategory = ReportMath.expenseByCategory(txns);

        assertEquals(1, byCategory.size());
        assertEquals(0, new BigDecimal("10").compareTo(byCategory.get("cat-food")));
    }

    @Test
    void emptyInputGivesEmptyTotals() {
        assertTrue(ReportMath.expenseByCategory(null).isEmpty());
        assertTrue(ReportMath.expenseByCategory(List.of()).isEmpty());
        assertEquals(0, BigDecimal.ZERO.compareTo(ReportMath.totalOf(null)));
    }
}
