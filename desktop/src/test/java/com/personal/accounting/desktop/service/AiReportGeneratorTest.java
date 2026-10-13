package com.personal.accounting.desktop.service;

import com.personal.accounting.desktop.data.DataManager;
import com.personal.accounting.desktop.model.Transaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端：真实 H2 建库 + 写入几笔支出，跑完整的规则引擎，检查日口径的统计文案真的出现在分析正文里。
 *
 * <p>这一层要覆盖的是「接线」而不是算术（算术由 {@link ExpenseStatsTest} 管）：
 * 统计值有没有被拼进正文、有没有被后面的步骤覆盖掉。</p>
 *
 * <p>断言取值只依赖「总支出」与「本月第一天」，与今天是几号无关：
 * 本月的统计天数就是到今天的自然天数，而单笔落在 1 号时，
 * 日平均 = 总支出 ÷ 天数、中位数 = 0 都成立。</p>
 *
 * <p>依赖本机未配置大模型（{@code ~/accounting/ai.properties} 不存在或未启用）——
 * 配置了可用的大模型时 {@link AiReportGenerator} 会把正文交给模型改写，断言就不再成立。</p>
 */
class AiReportGeneratorTest {

    private static Transaction expense(String clientId, LocalDate date, String amount) {
        Transaction t = new Transaction();
        t.clientId = clientId;
        t.date = date;
        t.amount = new BigDecimal(amount);
        t.type = "EXPENSE";
        t.categoryClientId = "cat-food";
        t.lastModified = System.currentTimeMillis();
        return t;
    }

    private static Transaction income(String clientId, LocalDate date, String amount) {
        Transaction t = expense(clientId, date, amount);
        t.type = "INCOME";
        t.categoryClientId = "cat-salary";
        return t;
    }

    @Test
    void reportContainsDailyAverageStdDevAndMedian(@TempDir Path dir) {
        try (DataManager db = new DataManager(dir.resolve("testdb"))) {
            LocalDate today = LocalDate.now();
            int days = today.getDayOfMonth();

            // 第一天两笔（1000 + 2000）合计 3000；今天一笔 160 → 总计 3160 元
            db.upsertTransaction(expense("t1", today.withDayOfMonth(1), "1000"));
            db.upsertTransaction(expense("t2", today.withDayOfMonth(1), "2000"));
            db.upsertTransaction(expense("t3", today, "160"));
            // 收入不进统计：加了它金额不该变
            db.upsertTransaction(income("i1", today.withDayOfMonth(1), "5000"));

            String report = new AiReportGenerator(db).generate(today.getYear(), today.getMonthValue());

            assertTrue(report.contains("本月 " + days + " 天共支出 3160.00 元"), report);
            // 日平均 = 3160 / 天数，与今天是几号无关
            String expectedAverage = String.format("%.2f",
                    new BigDecimal("3160").divide(BigDecimal.valueOf(days), 2, java.math.RoundingMode.HALF_UP)
                            .doubleValue());
            assertTrue(report.contains("日平均支出 " + expectedAverage + " 元"), report);
            assertTrue(report.contains("日支出标准差"), report);
            assertTrue(report.contains("日支出中位数"), report);
        }
    }

    @Test
    void singleExpenseDayGivesZeroMedian(@TempDir Path dir) {
        try (DataManager db = new DataManager(dir.resolve("oneday"))) {
            LocalDate today = LocalDate.now();
            int days = today.getDayOfMonth();

            // 只有第一天有支出：中位数必为 0，日均 = 3100 / 天数
            db.upsertTransaction(expense("t1", today.withDayOfMonth(1), "3100"));

            String report = new AiReportGenerator(db).generate(today.getYear(), today.getMonthValue());

            String expectedAverage = String.format("%.2f",
                    new BigDecimal("3100").divide(BigDecimal.valueOf(days), 2, java.math.RoundingMode.HALF_UP)
                            .doubleValue());
            assertTrue(report.contains("日平均支出 " + expectedAverage + " 元"), report);
            assertTrue(report.contains("日支出中位数 0.00 元"), report);
            assertTrue(report.contains("半数以上的日子没有支出"), report);
        }
    }

    @Test
    void reportWithoutExpensesSaysSo(@TempDir Path dir) {
        try (DataManager db = new DataManager(dir.resolve("emptydb"))) {
            LocalDate today = LocalDate.now();
            String report = new AiReportGenerator(db).generate(today.getYear(), today.getMonthValue());
            assertTrue(report.contains("还没有支出记录"), report);
        }
    }

    @Test
    void pastMonthIsLabelledWithItsOwnYearAndMonth(@TempDir Path dir) {
        try (DataManager db = new DataManager(dir.resolve("pastdb"))) {
            LocalDate past = LocalDate.now().minusMonths(2);
            db.upsertTransaction(expense("p1", past.withDayOfMonth(1), "100"));

            String report = new AiReportGenerator(db).generate(past.getYear(), past.getMonthValue());

            // 已结束的月份不能说成「本月」，且整月天数都参与
            String label = String.format("%d年%d月", past.getYear(), past.getMonthValue());
            assertTrue(report.contains(label + " " + past.lengthOfMonth() + " 天共支出 100.00 元"), report);
        }
    }

    @Test
    void currentMonthUsesDayOfMonthAsDivisor(@TempDir Path dir) {
        try (DataManager db = new DataManager(dir.resolve("divisordb"))) {
            LocalDate today = LocalDate.now();
            db.upsertTransaction(expense("t1", today.withDayOfMonth(1), "100"));
            String report = new AiReportGenerator(db).generate(today.getYear(), today.getMonthValue());
            assertTrue(report.contains("本月 " + today.getDayOfMonth() + " 天"), report);
        }
    }
}
