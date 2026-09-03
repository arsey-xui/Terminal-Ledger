package com.personal.accounting.desktop.model;

import java.math.BigDecimal;

public class Account {
    public String clientId;
    public String name;
    public String type;
    public String icon;
    public BigDecimal initialBalance = BigDecimal.ZERO;
    public int sortOrder;
    public long lastModified;
    public boolean deleted;
    public String createdAt;
    public String updatedAt;

    @Override
    public String toString() {
        return name == null ? super.toString() : name;
    }
}
