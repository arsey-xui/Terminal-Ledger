package com.personal.accounting.dto;

import com.personal.accounting.entity.Transaction;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class TransactionDto {
    private String clientId;
    private LocalDate date;
    private BigDecimal amount;
    private String type;
    private String categoryClientId;
    private String accountClientId;
    private String note;
    private String photoPath;
    private String source;
    private Long lastModified;
    private Boolean deleted;
    private LocalDateTime createdAt;

    public static TransactionDto from(Transaction t) {
        TransactionDto d = new TransactionDto();
        d.clientId = t.getClientId();
        d.date = t.getDate();
        d.amount = t.getAmount();
        d.type = t.getType();
        d.categoryClientId = t.getCategoryClientId();
        d.accountClientId = t.getAccountClientId();
        d.note = t.getNote();
        d.photoPath = t.getPhotoPath();
        d.source = t.getSource();
        d.lastModified = t.getLastModified();
        d.deleted = t.getDeleted();
        d.createdAt = t.getCreatedAt();
        return d;
    }
}
