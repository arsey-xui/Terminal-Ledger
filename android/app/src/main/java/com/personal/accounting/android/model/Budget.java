package com.personal.accounting.android.model;

/** 月度预算模型 */
public class Budget {
    public String clientId;
    public String categoryClientId;
    public int year;
    public int month;
    public double amountLimit;
    public long lastModified;
    public boolean deleted;
}
