package com.personal.accounting.desktop.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 周期记账规则（自动续费 / 固定收支）：每 N 天、周、月到期一次。 */
public class RecurringRule {

    public String clientId;
    public String name;
    public BigDecimal amount = BigDecimal.ZERO;
    /** EXPENSE / INCOME */
    public String type = "EXPENSE";
    public String categoryClientId;
    public String accountClientId;
    public String note;
    /** DAY / WEEK / MONTH */
    public String intervalUnit = "MONTH";
    /** 每 N 个单位一次（如 30 + DAY 表示「每 30 天」）。 */
    public int intervalCount = 1;
    public LocalDate anchorDate = LocalDate.now();
    /** 已生成到哪一天（含）。 */
    public LocalDate lastGeneratedDate;
    public boolean enabled = true;
    public int sortOrder;
    public long lastModified;
    public boolean deleted;
}
