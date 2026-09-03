package com.personal.accounting.android.model;

import androidx.annotation.NonNull;

/** 账户模型（字段与后端 AccountDto 对应，便于 Gson 序列化）。 */
public class Account {
    public String clientId;
    public String name;
    public String type;
    public String icon;
    public double initialBalance;
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
