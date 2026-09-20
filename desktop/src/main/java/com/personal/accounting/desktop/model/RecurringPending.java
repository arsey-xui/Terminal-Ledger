package com.personal.accounting.desktop.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 周期记账「待确认项」：规则到期后生成，用户确认才写入流水。
 * 只存本机，不参与同步（同步的是规则，以及确认后生成的那条流水）。
 */
public class RecurringPending {

    /** 同时作为确认后生成的流水 clientId，保证多设备重复确认不会产生重复流水。 */
    public String clientId;
    public String ruleClientId;
    public LocalDate date;
    public BigDecimal amount = BigDecimal.ZERO;
    public String type = "EXPENSE";
    public String categoryClientId;
    public String accountClientId;
    public String note;
    /** PENDING / ACCEPTED / SKIPPED */
    public String kind = "PENDING";
    public long lastModified;
}
