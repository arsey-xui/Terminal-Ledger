package com.personal.accounting.dto;

import com.personal.accounting.entity.Template;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class TemplateDto {
    private String clientId;
    private String name;
    private BigDecimal amount;
    private String type;
    private String categoryClientId;
    private String accountClientId;
    private String note;
    private Integer sortOrder;
    private Long lastModified;
    private Boolean deleted;

    public static TemplateDto from(Template t) {
        TemplateDto d = new TemplateDto();
        d.clientId = t.getClientId();
        d.name = t.getName();
        d.amount = t.getAmount();
        d.type = t.getType();
        d.categoryClientId = t.getCategoryClientId();
        d.accountClientId = t.getAccountClientId();
        d.note = t.getNote();
        d.sortOrder = t.getSortOrder();
        d.lastModified = t.getLastModified();
        d.deleted = t.getDeleted();
        return d;
    }
}
