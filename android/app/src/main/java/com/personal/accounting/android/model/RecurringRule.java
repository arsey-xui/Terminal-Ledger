package com.personal.accounting.android.model;

/** 周期记账规则（自动续费 / 固定收支）：每 N 天、周、月到期一次。 */
public class RecurringRule {

    public String clientId;
    public String name;
    public double amount;
    /** EXPENSE / INCOME */
    public String type;
    public String categoryClientId;
    public String accountClientId;
    public String note;
    /** DAY / WEEK / MONTH */
    public String intervalUnit = "MONTH";
    /** 每 N 个单位一次（如 30 + DAY 表示「每 30 天」）。 */
    public int intervalCount = 1;
    /** 首次（基准）应记日期，格式 yyyy-MM-dd。 */
    public String anchorDate;
    /** 已生成到哪一天（含），格式 yyyy-MM-dd；为空表示从未生成。 */
    public String lastGeneratedDate;
    public boolean enabled = true;
    public int sortOrder;
    public long lastModified;
    public boolean deleted;
}
