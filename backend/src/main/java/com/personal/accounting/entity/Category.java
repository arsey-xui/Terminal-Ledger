package com.personal.accounting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 分类（餐饮、交通、购物…）。type 区分支出/收入，预算仅针对支出（EXPENSE）。
 */
@Entity
@Table(name = "category", indexes = {
        @Index(name = "idx_category_last_modified", columnList = "last_modified")
})
@Getter
@Setter
@NoArgsConstructor
public class Category extends BaseEntity {

    @Column(nullable = false, length = 64)
    private String name;

    /** EXPENSE / INCOME */
    @Column(nullable = false, length = 16)
    private String type;

    @Column(length = 64)
    private String icon;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;
}
