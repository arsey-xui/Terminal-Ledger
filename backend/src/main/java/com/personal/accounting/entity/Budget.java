package com.personal.accounting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 月度预算：为每个支出分类单独设置上限。唯一键 (categoryClientId, year, month)。
 */
@Entity
@Table(name = "budget", uniqueConstraints = {
        @UniqueConstraint(name = "uk_budget_cat_month",
                columnNames = {"category_client_id", "budget_year", "budget_month"})
}, indexes = {
        @Index(name = "idx_budget_year_month", columnList = "budget_year, budget_month"),
        @Index(name = "idx_budget_last_modified", columnList = "last_modified")
})
@Getter
@Setter
@NoArgsConstructor
public class Budget extends BaseEntity {

    @Column(name = "category_client_id", nullable = false, length = 36)
    private String categoryClientId;

    @Column(name = "budget_year", nullable = false)
    private Integer year;

    @Column(name = "budget_month", nullable = false)
    private Integer month;

    @Column(name = "amount_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal amountLimit;
}
