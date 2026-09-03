package com.personal.accounting.android.model;

import androidx.annotation.NonNull;

/** 分类模型 */
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

    @NonNull
    @Override
    public String toString() {
        return name == null ? super.toString() : name;
    }
}
