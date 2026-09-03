package com.personal.accounting.dto;

import com.personal.accounting.entity.Account;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class AccountDto {
    private String clientId;
    private String name;
    private String type;
    private String icon;
    private BigDecimal initialBalance;
    private Integer sortOrder;
    private Long lastModified;
    private Boolean deleted;
    private LocalDateTime createdAt;

    public static AccountDto from(Account a) {
        AccountDto d = new AccountDto();
        d.clientId = a.getClientId();
        d.name = a.getName();
        d.type = a.getType();
        d.icon = a.getIcon();
        d.initialBalance = a.getInitialBalance();
        d.sortOrder = a.getSortOrder();
        d.lastModified = a.getLastModified();
        d.deleted = a.getDeleted();
        d.createdAt = a.getCreatedAt();
        return d;
    }
}
