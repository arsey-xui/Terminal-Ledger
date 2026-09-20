package com.personal.accounting.dto;

import com.personal.accounting.entity.RecurringRule;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 周期记账规则的传输对象。 */
@Data
public class RecurringRuleDto {
    private String clientId;
    private String name;
    private BigDecimal amount;
    private String type;
    private String categoryClientId;
    private String accountClientId;
    private String note;
    private String intervalUnit;
    private Integer intervalCount;
    private LocalDate anchorDate;
    private LocalDate lastGeneratedDate;
    private Boolean enabled;
    private Integer sortOrder;
    private Long lastModified;
    private Boolean deleted;

    public static RecurringRuleDto from(RecurringRule r) {
        RecurringRuleDto d = new RecurringRuleDto();
        d.clientId = r.getClientId();
        d.name = r.getName();
        d.amount = r.getAmount();
        d.type = r.getType();
        d.categoryClientId = r.getCategoryClientId();
        d.accountClientId = r.getAccountClientId();
        d.note = r.getNote();
        d.intervalUnit = r.getIntervalUnit();
        d.intervalCount = r.getIntervalCount();
        d.anchorDate = r.getAnchorDate();
        d.lastGeneratedDate = r.getLastGeneratedDate();
        d.enabled = r.getEnabled();
        d.sortOrder = r.getSortOrder();
        d.lastModified = r.getLastModified();
        d.deleted = r.getDeleted();
        return d;
    }
}
