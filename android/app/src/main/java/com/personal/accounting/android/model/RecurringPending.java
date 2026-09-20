package com.personal.accounting.android.model;

import java.time.LocalDate;

/**
 * 周期记账的「待确认项」：规则到期后生成，用户确认才真正变成流水。
 * 只保存在本机，不参与同步（同步的只有规则本身和确认后生成的流水）。
 */
public class RecurringPending {

    /** 同时作为确认后生成的流水 clientId，保证多设备重复确认也不会产生重复流水。 */
    public String clientId;
    public String ruleClientId;
    /** 应记日期。 */
    public LocalDate date;
    public double amount;
    public String type;
    public String categoryClientId;
    public String accountClientId;
    public String note;
    /** PENDING / ACCEPTED / SKIPPED */
    public String kind = "PENDING";
    public long lastModified;
}
