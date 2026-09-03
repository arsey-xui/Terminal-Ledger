package com.personal.accounting.desktop.model;

import java.math.BigDecimal;

public class Budget {
    public String clientId;
    public String categoryClientId;
    public int year;
    public int month;
    public BigDecimal amountLimit;
    public long lastModified;
    public boolean deleted;
}
