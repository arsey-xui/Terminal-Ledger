package com.personal.accounting.desktop.model;

public class Category {
    public String clientId;
    public String name;
    public String type; // EXPENSE / INCOME
    public String icon;
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
