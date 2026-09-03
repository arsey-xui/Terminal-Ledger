package com.personal.accounting.desktop.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public class Transaction {
    public String clientId;
    public LocalDate date;
    public BigDecimal amount;
    public String type; // EXPENSE / INCOME
    public String categoryClientId;
    public String accountClientId;
    public String note;
    public String photoPath;
    public String source;
    public long lastModified;
    public boolean deleted;
    public String createdAt;
    public String updatedAt;
}
