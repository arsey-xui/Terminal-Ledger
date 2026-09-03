package com.personal.accounting.android.model;

import java.time.LocalDate;

/** 记账流水模型 */
public class Transaction {
    public String clientId;
    public LocalDate date;
    public double amount;
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
