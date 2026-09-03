package com.personal.accounting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 账户（现金、储蓄卡、信用卡、微信、支付宝…）。支持完整 CRUD 与软删除。
 */
@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
public class Account extends BaseEntity {

    @Column(nullable = false, length = 64)
    private String name;

    /** 账户类型：CASH / DEBIT_CARD / CREDIT_CARD / WECHAT / ALIPAY / OTHER */
    @Column(length = 32)
    private String type;

    @Column(length = 64)
    private String icon;

    @Column(name = "initial_balance", precision = 19, scale = 2)
    private BigDecimal initialBalance = BigDecimal.ZERO;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;
}
